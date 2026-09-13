package com.widyu.location.realtime.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.GeoUtils;
import com.widyu.location.SeniorLocation;
import com.widyu.location.realtime.dto.LocationPoint;
import com.widyu.location.realtime.dto.LocationTrailResponse;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import com.widyu.location.realtime.dto.StayInfo;
import com.widyu.location.realtime.dto.TrackedSeniorResponse;
import com.widyu.location.realtime.event.SeniorLocationUpdatedEvent;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.location.parentlocation.repository.ParentLocationRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.parentlocation.LocationType;
import com.widyu.parentlocation.ParentLocation;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RealtimeLocationService {

    private final SeniorLocationRepository seniorLocationRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final ParentLocationRepository parentLocationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final SafeZoneAlertService safeZoneAlertService;

    private static final String LOCATION_TRAIL_KEY_PREFIX = "location:trail:";
    private static final String LOCATION_STAY_KEY_PREFIX = "location:stay:";
    private static final long TRAIL_TTL_SECONDS = 900; // 15분
    private static final long STAY_TTL_SECONDS = 86400; // 24시간
    private static final double STAY_RADIUS_METERS = 30.0; // 30m 이내면 같은 위치
    private static final double SAFE_ZONE_RADIUS_METERS = 75.0; // 안전구역 반경 75m (지름 150m)

    @Transactional
    public LocationUpdateResponse updateAndBroadcast(LocationUpdateRequest request,
                                                      Long authenticatedMemberId) {

        Long memberId = request.memberId();

        // 1. 권한 검증: 시니어 본인인지 확인
        if (!memberId.equals(authenticatedMemberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 위치만 업데이트할 수 있습니다.");
        }

        // 2. 시니어 프로필 조회 (memberId로)
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 시니어입니다."));

        Member seniorMember = seniorProfile.getMember();

        // 3. Redis에 최신 위치 저장 (memberId 기준)
        SeniorLocation location = SeniorLocation.of(
                memberId,
                request.latitude(),
                request.longitude()
        );
        seniorLocationRepository.save(location);
        eventPublisher.publishEvent(new SeniorLocationUpdatedEvent(memberId, request.latitude(), request.longitude()));

        // 4. Redis List에 이동 경로 저장 (15분 TTL, memberId 기준)
        String trailKey = LOCATION_TRAIL_KEY_PREFIX + memberId;
        LocationPoint point = LocationPoint.of(request.latitude(), request.longitude());

        Long listSize = redisTemplate.opsForList().leftPush(trailKey, point);
        redisTemplate.expire(trailKey, TRAIL_TTL_SECONDS, TimeUnit.SECONDS);

        log.info("이동 경로 저장: memberId={}, pointCount={}", memberId, listSize);

        // 5. 체류 시간 및 위치 타입 계산 (memberId 기준)
        String stayKey = LOCATION_STAY_KEY_PREFIX + memberId;
        StayInfo stayInfo = calculateStayInfo(
                stayKey, request.latitude(), request.longitude(), seniorMember);

        // 6. Response 객체 생성
        LocationUpdateResponse response = LocationUpdateResponse.of(
                memberId,
                seniorMember.getName(),
                seniorMember.getProfileImage(),
                request.latitude(),
                request.longitude(),
                stayInfo.startTime(),
                stayInfo.locationType(),
                stayInfo.locationName()
        );

        // 7. 시니어별 방으로 브로드캐스트 (memberId 기준)
        String destination = String.format("/topic/location/senior/%d", memberId);
        messagingTemplate.convertAndSend(destination, response);

        log.info("시니어 방으로 위치 브로드캐스트 완료 - memberId: {}, destination: {}",
                 memberId, destination);

        return response;
    }

    /**
     * 보호자가 추적 가능한 시니어 목록 조회
     */
    public List<TrackedSeniorResponse> getTrackedSeniors(Long guardianId) {
        FamilyMembership myMembership = familyMembershipRepository.findByGuardianId(guardianId)
                .orElse(null);

        if (myMembership == null) {
            return List.of();
        }

        List<SeniorProfile> seniors = seniorProfileRepository
                .findAllByFamilyIdWithMember(myMembership.getFamily().getId());

        List<Long> seniorMemberIds = seniors.stream()
                .map(s -> s.getMember().getId())
                .toList();

        Map<Long, SeniorLocation> locationMap = new java.util.HashMap<>();
        seniorLocationRepository.findAllById(seniorMemberIds)
                .forEach(l -> locationMap.put(l.getSeniorId(), l));

        return seniors.stream()
                .map(senior -> {
                    Long memberId = senior.getMember().getId();
                    SeniorLocation location = locationMap.get(memberId);

                    if (location != null) {
                        return TrackedSeniorResponse.of(senior, location.getLatitude(), location.getLongitude());
                    }

                    String stayKey = LOCATION_STAY_KEY_PREFIX + memberId;
                    try {
                        Object stayObj = redisTemplate.opsForValue().get(stayKey);
                        if (stayObj instanceof StayInfo stayInfo) {
                            return TrackedSeniorResponse.of(senior, stayInfo.latitude(), stayInfo.longitude());
                        }
                    } catch (Exception e) {
                        log.warn("StayInfo 역직렬화 실패 (stale data) - stayKey: {}, 삭제 후 재생성", stayKey, e);
                        redisTemplate.delete(stayKey);
                    }

                    return TrackedSeniorResponse.of(senior, null, null);
                })
                .toList();
    }

    public Boolean getOutingStatus(Long memberId) {
        StayInfo stayInfo = getStayInfo(memberId);
        if (stayInfo == null) {
            return null;
        }
        return !LocationType.HOME.name().equals(stayInfo.locationType());
    }

    /**
     * 특정 시니어의 마지막 위치 조회 (REST API용)
     * @param memberId 시니어의 Member ID
     * @param guardianId 보호자의 Member ID
     */
    public LocationUpdateResponse getLastLocation(Long memberId, Long guardianId) {

        // 시니어 프로필 조회 (memberId로)
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 시니어입니다."));

        if (!familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(guardianId, seniorProfile.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어의 위치를 조회할 권한이 없습니다.");
        }

        Member seniorMember = seniorProfile.getMember();

        // 체류 시간 및 위치 타입 정보 조회 (memberId 기준)
        LocalDateTime stayStartTime = LocalDateTime.now();
        String locationType = null;
        StayInfo stayInfo = null;

        stayInfo = getStayInfo(memberId);
        if (stayInfo != null) {
            stayStartTime = stayInfo.startTime();
            locationType = stayInfo.locationType();
        }

        // SeniorLocation(5분 TTL) 우선 조회, 만료 시 StayInfo(24시간 TTL)로 fallback
        Optional<SeniorLocation> locationOpt = seniorLocationRepository.findBySeniorId(memberId);

        double latitude;
        double longitude;

        if (locationOpt.isPresent()) {
            latitude = locationOpt.get().getLatitude();
            longitude = locationOpt.get().getLongitude();
        } else if (stayInfo != null) {
            latitude = stayInfo.latitude();
            longitude = stayInfo.longitude();
        } else {
            throw new BusinessException(ErrorCode.NOT_FOUND, "최근 위치 정보가 없습니다.");
        }

        String locationName = getStayLocationName(stayInfo);

        return LocationUpdateResponse.of(
                memberId,
                seniorMember.getName(),
                seniorMember.getProfileImage(),
                latitude,
                longitude,
                stayStartTime,
                locationType,
                locationName
        );
    }

    private StayInfo getStayInfo(Long memberId) {
        String stayKey = LOCATION_STAY_KEY_PREFIX + memberId;
        try {
            Object stayObj = redisTemplate.opsForValue().get(stayKey);
            if (stayObj instanceof StayInfo stayInfo) {
                return stayInfo;
            }
        } catch (Exception e) {
            log.warn("StayInfo 역직렬화 실패 (stale data) - stayKey: {}, 삭제 후 재생성", stayKey, e);
            redisTemplate.delete(stayKey);
        }
        return null;
    }

    /**
     * 특정 시니어의 15분 이동 경로 조회
     * @param memberId 시니어의 Member ID
     * @param guardianId 보호자의 Member ID
     */
    public LocationTrailResponse getLocationTrail(Long memberId, Long guardianId) {

        // 시니어 프로필 조회 (memberId로)
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 시니어입니다."));

        if (!familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(guardianId, seniorProfile.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어의 위치를 조회할 권한이 없습니다.");
        }

        Member seniorMember = seniorProfile.getMember();

        // Redis에서 이동 경로 조회 (memberId 기준)
        String trailKey = LOCATION_TRAIL_KEY_PREFIX + memberId;
        List<Object> rawTrail = redisTemplate.opsForList().range(trailKey, 0, -1);

        // LocationPoint로 변환 (LinkedHashMap으로 역직렬화된 경우 처리)
        List<LocationPoint> trail = new ArrayList<>();
        if (rawTrail != null) {
            for (Object obj : rawTrail) {
                if (obj instanceof LocationPoint point) {
                    trail.add(point);
                } else if (obj instanceof LinkedHashMap<?, ?> map) {
                    // GenericJackson2JsonRedisSerializer로 역직렬화 시 LinkedHashMap으로 변환되는 경우
                    Double lat = getDoubleOrNull(map.get("latitude"));
                    Double lng = getDoubleOrNull(map.get("longitude"));
                    LocalDateTime timestamp = parseTimestamp(map.get("timestamp"));
                    if (lat != null && lng != null) {
                        trail.add(new LocationPoint(lat, lng, timestamp));
                    }
                }
            }
        }

        java.util.Collections.reverse(trail);

        log.info("이동 경로 조회 완료 - memberId: {}, 포인트 개수: {}", memberId, trail.size());

        return LocationTrailResponse.of(
                memberId,
                seniorMember.getName(),
                seniorMember.getProfileImage(),
                trail
        );
    }

    /**
     * 체류 정보 계산 (체류 시작 시간 + 위치 타입)
     * - 이전 위치와 30m 이내면 기존 체류 정보 유지
     * - 30m 초과 이동 시 새로운 체류 정보 설정
     * - 안전구역(HOME, OTHER) 75m 반경 내면 해당 타입, 아니면 null
     * - 안전구역 이탈 시 보호자에게 알림 전송
     */
    private StayInfo calculateStayInfo(String stayKey, Double newLat, Double newLng, Member member) {
        Object stayObj = null;
        try {
            stayObj = redisTemplate.opsForValue().get(stayKey);
        } catch (Exception e) {
            log.warn("StayInfo 역직렬화 실패 (stale data) - stayKey: {}, 삭제 후 재생성", stayKey, e);
            redisTemplate.delete(stayKey);
        }
        String previousLocationType = null;

        if (stayObj instanceof StayInfo previousStay) {
            previousLocationType = previousStay.locationType();

            boolean isSameLocation = GeoUtils.isWithinRadius(
                    previousStay.latitude(), previousStay.longitude(),
                    newLat, newLng,
                    STAY_RADIUS_METERS
            );

            if (isSameLocation) {
                log.debug("같은 위치 유지 - stayKey: {}, 체류 시작: {}, 위치 타입: {}",
                        stayKey, previousStay.startTime(), previousStay.locationType());
                return previousStay;
            }
        }

        // 새로운 위치로 이동 → 안전구역 매칭
        ParentLocation matchedZone = determineMatchingSafeZone(member, newLat, newLng);
        String locationType = getLocationTypeName(matchedZone);
        String locationName = getLocationName(matchedZone);

        // 안전구역 이탈 감지 및 알림 전송
        safeZoneAlertService.handleSafeZoneTransition(member.getId(), previousLocationType, locationType);

        StayInfo newStay = StayInfo.of(newLat, newLng, locationType, locationName);
        redisTemplate.opsForValue().set(stayKey, newStay, STAY_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("새로운 위치로 이동 - stayKey: {}, 체류 시작: {}, 위치 타입: {}",
                stayKey, newStay.startTime(), locationType);

        return newStay;
    }

    /**
     * 현재 위치가 어떤 안전구역 내에 있는지 판단
     * - 등록된 모든 안전구역(HOME, OTHER)과 75m 반경(지름 150m) 내 비교
     * - HOME 안전구역 내 → HOME
     * - OTHER 안전구역 내 → OTHER
     * - 어떤 안전구역에도 없으면 → null
     */
    private ParentLocation determineMatchingSafeZone(Member member, Double lat, Double lng) {
        List<ParentLocation> safeZones = parentLocationRepository.findAllByMember(member);

        for (ParentLocation safeZone : safeZones) {
            boolean isWithinSafeZone = GeoUtils.isWithinRadius(
                    lat, lng, safeZone.getLatitude(), safeZone.getLongitude(), SAFE_ZONE_RADIUS_METERS);
            if (isWithinSafeZone) {
                return safeZone;
            }
        }

        return null;
    }

    /**
     * Redis에서 역직렬화된 timestamp 파싱
     */
    private LocalDateTime parseTimestamp(Object timestampObj) {
        if (timestampObj == null) {
            return LocalDateTime.now();
        }
        if (timestampObj instanceof String str) {
            return LocalDateTime.parse(str);
        }
        if (timestampObj instanceof LinkedHashMap<?, ?> map) {
            // LocalDateTime이 LinkedHashMap으로 역직렬화된 경우
            int year = getIntOrDefault(map.get("year"), 2024);
            int month = getIntOrDefault(map.get("monthValue"), 1);
            int day = getIntOrDefault(map.get("dayOfMonth"), 1);
            int hour = getIntOrDefault(map.get("hour"), 0);
            int minute = getIntOrDefault(map.get("minute"), 0);
            int second = getIntOrDefault(map.get("second"), 0);
            int nano = getIntOrDefault(map.get("nano"), 0);
            return LocalDateTime.of(year, month, day, hour, minute, second, nano);
        }
        return LocalDateTime.now();
    }

    private int getIntOrDefault(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private Double getDoubleOrNull(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private String getStayLocationName(StayInfo stayInfo) {
        if (stayInfo == null) {
            return null;
        }
        return stayInfo.locationName();
    }

    private String getLocationTypeName(ParentLocation matchedZone) {
        if (matchedZone == null) {
            return null;
        }
        return matchedZone.getLocationType().name();
    }

    private String getLocationName(ParentLocation matchedZone) {
        if (matchedZone == null) {
            return null;
        }
        return matchedZone.getName();
    }

    /**
     * 4분마다 실행 — SeniorLocation(5분 TTL)과 location:stay(24시간 TTL)를 갱신해
     * 시니어가 정지 중이어도 위치 데이터가 만료되지 않도록 유지한다.
     */
    @Scheduled(fixedRate = 240_000)
    public void refreshLocationTtl() {
        Iterable<SeniorLocation> activeLocations = seniorLocationRepository.findAll();
        for (SeniorLocation location : activeLocations) {
            if (location == null) {
                continue;
            }
            seniorLocationRepository.save(location);
            String stayKey = LOCATION_STAY_KEY_PREFIX + location.getSeniorId();
            redisTemplate.expire(stayKey, STAY_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }
}
