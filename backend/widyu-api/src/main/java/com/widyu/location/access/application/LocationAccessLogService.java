package com.widyu.location.access.application;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.application.ConsentService;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.properties.LocationAccessProperties;
import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.LocationAccessPath;
import com.widyu.location.access.dto.response.LocationAccessLogResponse;
import com.widyu.location.access.repository.LocationAccessLogRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자의 위치 열람을 남기고 시니어에게 알린다(LLD-0056 5절, ADR-0036 결정 3·4).
 *
 * <p>기록은 {@code REQUIRES_NEW}로 쓴다. 위치 조회 트랜잭션이 뒤에서 무슨 일을 하든
 * 「봤다」는 사실은 남아야 하고, 반대로 기록이 깨져도 조회는 성공해야 한다. 후자는
 * 호출부가 {@code try/catch}로 지킨다 — 프록시가 커밋 시점에 던지는 예외는 이 메서드
 * 안에서 잡을 수 없기 때문이다.
 *
 * <p>로그에는 회원 식별자와 경로만 남긴다. 좌표·이름·전화번호는 남기지 않는다(LLD-0029).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationAccessLogService {

    /** 보호자 이름을 찾지 못했을 때 통보 문구에 쓰는 말. 이름이 없다고 통보를 거를 수는 없다. */
    private static final String UNKNOWN_VIEWER = "보호자";

    private static final String NOTICE_TITLE = "위치 조회 알림";
    private static final int DEFAULT_RANGE_DAYS = 30;

    private final LocationAccessLogRepository locationAccessLogRepository;
    private final ConsentService consentService;
    private final MemberRepository memberRepository;
    private final FcmService fcmService;
    private final LocationAccessProperties locationAccessProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long viewerMemberId, Long seniorMemberId, LocationAccessPath path) {
        if (viewerMemberId == null || seniorMemberId == null) {
            return;
        }
        // 본인이 자기 위치를 보는 것은 「타인의 위치 조회」가 아니다(ADR-0036 결정 3).
        if (viewerMemberId.equals(seniorMemberId)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocationAccessLog accessLog = locationAccessLogRepository.save(
                LocationAccessLog.of(viewerMemberId, seniorMemberId, path, now));

        if (consentService.isGranted(seniorMemberId, ConsentKey.LOCATION_NOTICE_BATCHED)) {
            // 모아서 통보에 동의했으면 다이제스트가 가져간다. notified_at은 비워 둔다.
            return;
        }
        notifyImmediately(accessLog, now);
    }

    /**
     * 같은 보호자가 쿨다운 안에 다시 봤으면 FCM을 보내지 않고 {@code notified_at}도 비워 둔다.
     * 비워 두어야 다음 다이제스트가 「합쳐진 건」까지 세어 알린다(LLD-0056 5.3).
     */
    private void notifyImmediately(LocationAccessLog accessLog, LocalDateTime now) {
        LocalDateTime cooldownFrom = now.minusMinutes(locationAccessProperties.immediateCooldownMin());
        boolean notifiedWithinCooldown = locationAccessLogRepository
                .existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                        accessLog.getSeniorMemberId(), accessLog.getViewerMemberId(), cooldownFrom);
        if (notifiedWithinCooldown) {
            return;
        }

        fcmService.sendMessageToUser(accessLog.getSeniorMemberId(), FcmSendDto.builder()
                .title(NOTICE_TITLE)
                .content(viewerName(accessLog.getViewerMemberId()) + "님이 내 위치를 확인했어요")
                .fcmCategory(FcmCategory.LOCATION_NOTICE)
                .scheme("")
                .image(null)
                .relatedMemberId(accessLog.getViewerMemberId())
                .emergency(false)
                .build());
        accessLog.markNotified(now);
        log.info("위치 열람 즉시 통보 seniorMemberId={} viewerMemberId={} path={}",
                accessLog.getSeniorMemberId(), accessLog.getViewerMemberId(), accessLog.getPath());
    }

    /** 기간을 주지 않으면 최근 30일이다(LLD-0056 3절). */
    @Transactional(readOnly = true)
    public Page<LocationAccessLogResponse> myLogs(
            Long seniorMemberId, LocalDateTime from, LocalDateTime to, int page, int size) {
        LocalDateTime end = to;
        if (end == null) {
            end = LocalDateTime.now();
        }
        LocalDateTime start = from;
        if (start == null) {
            start = end.minusDays(DEFAULT_RANGE_DAYS);
        }

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("accessedAt").descending());
        Page<LocationAccessLog> logs = locationAccessLogRepository
                .findBySeniorMemberIdAndAccessedAtBetween(seniorMemberId, start, end, pageRequest);

        Map<Long, String> names = viewerNames(logs.getContent());
        return logs.map(accessLog ->
                LocationAccessLogResponse.of(accessLog, names.get(accessLog.getViewerMemberId())));
    }

    /** 한 페이지의 보호자 이름을 한 번에 읽는다. 행마다 조회하면 N+1이 된다. */
    private Map<Long, String> viewerNames(List<LocationAccessLog> logs) {
        List<Long> viewerIds = logs.stream()
                .map(LocationAccessLog::getViewerMemberId)
                .distinct()
                .toList();
        if (viewerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Member viewer : memberRepository.findAllById(viewerIds)) {
            names.put(viewer.getId(), viewer.getName());
        }
        return names;
    }

    private String viewerName(Long viewerMemberId) {
        return memberRepository.findById(viewerMemberId)
                .map(Member::getName)
                .orElse(UNKNOWN_VIEWER);
    }
}
