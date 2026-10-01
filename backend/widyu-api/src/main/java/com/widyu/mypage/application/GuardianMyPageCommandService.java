package com.widyu.mypage.application;

import com.widyu.auth.dto.request.SeniorSignUpRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.mypage.dto.request.UpdateInviteCodeRequest;
import com.widyu.mypage.dto.request.GuardianOrderUpdateRequest;
import com.widyu.mypage.dto.response.GuardianOrderUpdateResponse;
import com.widyu.mypage.dto.request.UpdateNameRequest;
import com.widyu.mypage.dto.request.UpdatePhoneRequest;
import com.widyu.mypage.dto.request.UpdateSeniorAddressRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class GuardianMyPageCommandService {

    private final GuardianMyPageService guardianMyPageService;
    private final MemberUtil memberUtil;
    private final FamilyAccessService familyAccessService;

    public void updateName(UpdateNameRequest request) {
        guardianMyPageService.updateName(request);
    }

    public void updateProfileImage(MultipartFile image) {
        guardianMyPageService.updateProfileImage(image);
    }

    public void addSenior(SeniorSignUpRequest request) {
        guardianMyPageService.addSenior(request);
    }

    public void sendPhoneChangeSms(UpdatePhoneRequest request) {
        guardianMyPageService.sendPhoneChangeSms(request);
    }

    public void verifyPhoneChangeCode(SmsCodeRequest request) {
        guardianMyPageService.verifyPhoneChangeCode(request);
    }

    public void updatePhone(UpdatePhoneRequest request) {
        guardianMyPageService.updatePhone(request);
    }

    public void updateSeniorName(Long memberId, UpdateNameRequest request) {
        verifyLeaderAccess(memberId);
        guardianMyPageService.updateSeniorName(memberId, request);
    }

    public void updateSeniorPhone(Long memberId, UpdatePhoneRequest request) {
        verifyLeaderAccess(memberId);
        guardianMyPageService.updateSeniorPhone(memberId, request);
    }

    public void updateSeniorAddress(Long memberId, UpdateSeniorAddressRequest request) {
        verifyLeaderAccess(memberId);
        guardianMyPageService.updateSeniorAddress(memberId, request);
    }

    public void updateSeniorProfileImage(Long memberId, MultipartFile image) {
        verifyLeaderAccess(memberId);
        guardianMyPageService.updateSeniorProfileImage(memberId, image);
    }

    public void updateSeniorInviteCode(Long memberId, UpdateInviteCodeRequest request) {
        verifyLeaderAccess(memberId);
        guardianMyPageService.updateSeniorInviteCode(memberId, request);
    }

    public void changeLeader(Long memberId) {
        verifyGuardianLeader();
        guardianMyPageService.changeLeader(memberId);
    }

    public GuardianOrderUpdateResponse updateGuardianOrder(GuardianOrderUpdateRequest request) {
        verifyGuardianLeader();
        return guardianMyPageService.updateGuardianOrder(request);
    }

    public void deleteFamilyMember(Long memberId) {
        verifyGuardianLeader();
        guardianMyPageService.deleteFamilyMember(memberId);
    }

    private void verifyLeaderAccess(Long memberId) {
        familyAccessService.verifyLeaderAccess(memberUtil.getCurrentMember().getId(), memberId);
    }

    private void verifyGuardianLeader() {
        familyAccessService.verifyGuardianLeader(memberUtil.getCurrentMember().getId());
    }
}
