package com.widyu.album.application;

import com.widyu.album.Album;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlbumPermissionService {

    private final AlbumUnlockService albumUnlockService;
    private final FamilyAccessService familyAccessService;

    public void checkFamilyAccess(Album album, Member me) {
        if (album.getMember().getId().equals(me.getId())) {
            return;
        }
        familyAccessService.verifySameFamily(me, album.getMember());
    }

    // 잠금 해제 상태 판정. 응답의 isUnlocked도 같은 규칙을 쓴다.
    public boolean isUnlockedFor(Album album, Member me) {
        // 같은 가족의 보호자(GUARDIAN)는 잠금 대상이 아니다
        if (me.getType() == MemberType.GUARDIAN) {
            return true;
        }
        // 시니어가 올린 앨범은 해금이 필요 없다
        if (!album.requiresUnlock()) {
            return true;
        }
        return albumUnlockService.isAlbumUnlocked(album, me);
    }
}
