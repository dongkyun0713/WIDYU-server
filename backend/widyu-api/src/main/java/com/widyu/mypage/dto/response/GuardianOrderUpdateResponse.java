package com.widyu.mypage.dto.response;

import com.widyu.member.Family;

public record GuardianOrderUpdateResponse(long familyOrderRevision) {
    public static GuardianOrderUpdateResponse from(Family family) {
        return new GuardianOrderUpdateResponse(family.getFamilyOrderRevision());
    }
}
