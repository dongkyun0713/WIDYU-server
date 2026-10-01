package com.widyu.member.application;

import com.widyu.album.dto.response.UnlockedAlbumIdsResponse;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.fcm.event.point.dto.PointChangedEvent;
import com.widyu.member.dto.response.SeniorPointsResponse;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistory;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.LinkedHashSet;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.retry.RetryOnPointConflict;
import com.widyu.global.util.MemberUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeniorProfileService {

    private final MemberUtil memberUtil;
    private final AlbumUnlockRepository albumUnlockRepository;
    private final FamilyAccessService familyAccessService;
    private final SeniorProfileRepository seniorProfileRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public SeniorPointsResponse getLeftPoints() {
        Member currentMember = memberUtil.getCurrentMember();

        // 시니어 타입 검증
        if (currentMember.getType() != MemberType.SENIOR) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시니어 회원만 접근할 수 있습니다.");
        }

        SeniorProfile seniorProfile = currentMember.getSeniorProfile();
        if (seniorProfile == null) {
            throw new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND);
        }

        log.info("시니어 포인트 조회: memberId={}, points={}", currentMember.getId(), seniorProfile.getPoints());
        return SeniorPointsResponse.from(seniorProfile);
    }

    @Transactional(readOnly = true)
    public UnlockedAlbumIdsResponse getUnlockedAlbums() {
        Member currentMember = memberUtil.getCurrentMember();

        // 시니어 타입 검증
        if (currentMember.getType() != MemberType.SENIOR) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시니어 회원만 접근할 수 있습니다.");
        }

        LinkedHashSet<Long> unlockedAlbumIds = new LinkedHashSet<>(
                albumUnlockRepository.findUnlockedAlbumIdsByMember(currentMember));
        List<Long> familyMemberIds = familyAccessService.getFamilyMemberIds(currentMember);
        unlockedAlbumIds.addAll(albumUnlockRepository
                .findActiveAlbumIdsByMemberIdsAndMemberType(familyMemberIds, MemberType.SENIOR));

        log.info("해금된 앨범 ID 조회: memberId={}, count={}",
                currentMember.getId(), unlockedAlbumIds.size());

        return UnlockedAlbumIdsResponse.from(List.copyOf(unlockedAlbumIds));
    }

    @RetryOnPointConflict
    @Transactional
    public void addPointsToMember(Long memberId, Long points) {
        addPointsToMember(memberId, points, "포인트 적립");
    }

    @RetryOnPointConflict
    @Transactional
    public void addPointsToMember(Long memberId, Long points, String description) {
        addPointsToMember(memberId, points, description, null);
    }

    @RetryOnPointConflict
    @Transactional
    public void addPointsToMember(Long memberId, Long points, String description, String operationKey) {
        PointHistory history = applyEarnedPoints(memberId, points, description, operationKey);
        if (history != null) {
            eventPublisher.publishEvent(PointChangedEvent.from(memberId, history));
        }
    }

    @RetryOnPointConflict
    @Transactional
    public void addGoalRewardPoints(Long memberId, Long points, String description, String operationKey) {
        applyEarnedPoints(memberId, points, description, operationKey);
    }

    private PointHistory applyEarnedPoints(Long memberId, Long points, String description, String operationKey) {
        if (points == null || points <= 0) {
            log.warn("유효하지 않은 포인트 적립 시도: memberId={}, points={}", memberId, points);
            return null;
        }

        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        seniorProfile.addPoints(points);
        PointHistory history = pointHistoryRepository.save(
                PointHistory.earn(seniorProfile, points, description, operationKey));
        log.info("포인트 적립 완료: memberId={}, addedPoints={}, totalPoints={}",
                memberId, points, seniorProfile.getPoints());
        return history;
    }

    @RetryOnPointConflict
    @Transactional
    public void deductPointsFromMember(Long memberId, Long points, String description) {
        deductPointsFromMember(memberId, points, description, null);
    }

    @RetryOnPointConflict
    @Transactional
    public void deductPointsFromMember(Long memberId, Long points, String description, String operationKey) {
        if (points == null || points <= 0) {
            log.warn("유효하지 않은 포인트 차감 시도: memberId={}, points={}", memberId, points);
            return;
        }

        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        if (!seniorProfile.hasEnoughPoints(points)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "결제 취소에 필요한 포인트가 부족합니다.");
        }

        seniorProfile.deductPoints(points);
        PointHistory history = pointHistoryRepository.save(
                PointHistory.use(seniorProfile, points, description, operationKey));
        eventPublisher.publishEvent(PointChangedEvent.from(memberId, history));
        log.info("포인트 차감 완료: memberId={}, deductedPoints={}, totalPoints={}",
                memberId, points, seniorProfile.getPoints());
    }
}
