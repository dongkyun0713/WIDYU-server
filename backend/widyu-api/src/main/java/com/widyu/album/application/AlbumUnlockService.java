package com.widyu.album.application;

import com.widyu.album.dto.response.AlbumUnlockResponse;
import com.widyu.album.Album;
import com.widyu.album.AlbumUnlock;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlbumUnlockService {

    private final AlbumUnlockRepository albumUnlockRepository;
    private final AlbumRepository albumRepository;
    private final MemberUtil memberUtil;
    private final ApplicationEventPublisher eventPublisher;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyAccessService familyAccessService;
    private final SeniorProfileService seniorProfileService;

    @Transactional
    public AlbumUnlockResponse unlockAlbum(Long albumId) {
        Member currentMember = memberUtil.getCurrentMember();

        Album album = albumRepository.findByIdAndStatus(albumId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        familyAccessService.verifySameFamily(currentMember, album.getMember());

        // 1. 본인 앨범 해금 방지
        if (album.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.ALBUM_UNLOCK_SELF_NOT_ALLOWED);
        }

        // 2. 시니어가 올린 앨범은 해금 대상이 아님 (포인트 차감 방지)
        if (!album.requiresUnlock()) {
            throw new BusinessException(ErrorCode.ALBUM_UNLOCK_NOT_REQUIRED);
        }

        // 3. 시니어 타입 검증
        if (currentMember.getType() != MemberType.SENIOR) {
            throw new BusinessException(ErrorCode.ALBUM_UNLOCK_SENIOR_ONLY);
        }

        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        // 4. 이미 해금된 앨범인지 확인
        if (albumUnlockRepository.existsByAlbumAndMember(album, currentMember)) {
            throw new BusinessException(ErrorCode.ALBUM_ALREADY_UNLOCKED);
        }

        // 5. 포인트 잔액 확인
        if (!hasEnoughBalance(seniorProfile)) {
            throw new BusinessException(ErrorCode.ALBUM_UNLOCK_INSUFFICIENT_BALANCE);
        }

        // 6. 포인트 차감 및 내역 기록
        seniorProfileService.deductPointsFromMember(currentMember.getId(), Album.UNLOCK_PRICE, "앨범 해금");

        // 7. 해금 기록 생성
        AlbumUnlock albumUnlock = AlbumUnlock.createUnlock(album, currentMember);
        AlbumUnlock savedUnlock = albumUnlockRepository.save(albumUnlock);

        // 앨범 잠금 해제 알림 이벤트 발행
        eventPublisher.publishEvent(new AlbumUnlockedEvent(
                albumId,
                currentMember.getId()
        ));

        return AlbumUnlockResponse.from(savedUnlock, seniorProfile.getPoints());
    }

    @Transactional(readOnly = true)
    public boolean isAlbumUnlocked(Album album, Member member) {
        return albumUnlockRepository.existsByAlbumAndMember(album, member);
    }

    private boolean hasEnoughBalance(SeniorProfile seniorProfile) {
        return seniorProfile.hasEnoughPoints(Album.UNLOCK_PRICE);
    }

}
