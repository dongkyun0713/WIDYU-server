package com.widyu.mypage.controller;

import com.widyu.auth.dto.request.SeniorSignUpRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.mypage.application.GuardianMyPageCommandService;
import com.widyu.mypage.application.GuardianMyPageQueryService;
import com.widyu.mypage.controller.docs.GuardianMyPageDocs;
import com.widyu.mypage.dto.request.ProfileImageUploadRequest;
import com.widyu.mypage.dto.request.GuardianOrderUpdateRequest;
import com.widyu.mypage.dto.request.UpdateInviteCodeRequest;
import com.widyu.mypage.dto.request.UpdateNameRequest;
import com.widyu.mypage.dto.request.UpdatePhoneRequest;
import com.widyu.mypage.dto.request.UpdateSeniorAddressRequest;
import com.widyu.mypage.dto.response.ConnectedSeniorResponse;
import com.widyu.mypage.dto.response.FamilyCodeResponse;
import com.widyu.mypage.dto.response.FamilyMemberListResponse;
import com.widyu.mypage.dto.response.GuardianInfoResponse;
import com.widyu.mypage.dto.response.GuardianOrderUpdateResponse;
import com.widyu.mypage.dto.response.GuardianProfileDetailResponse;
import com.widyu.mypage.dto.response.SeniorProfileForGuardianResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mypage/guardian")
public class GuardianMyPageController implements GuardianMyPageDocs {

    private final GuardianMyPageQueryService guardianMyPageQueryService;
    private final GuardianMyPageCommandService guardianMyPageCommandService;

    @Override
    @GetMapping
    public ApiResponseTemplate<GuardianInfoResponse> getGuardianInfo() {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2010")
                .message("보호자 내 정보 조회 성공")
                .body(guardianMyPageQueryService.getGuardianInfo());
    }

    @Override
    @GetMapping("/profile")
    public ApiResponseTemplate<GuardianProfileDetailResponse> getProfileDetail() {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2011")
                .message("보호자 프로필 조회 성공")
                .body(guardianMyPageQueryService.getProfileDetail());
    }

    @Override
    @PatchMapping("/profile/name")
    public ApiResponseTemplate<Void> updateName(@RequestBody @Valid UpdateNameRequest request) {
        guardianMyPageCommandService.updateName(request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2012")
                .message("이름 수정 성공")
                .build();
    }

    @Override
    @PatchMapping(value = "/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseTemplate<Void> updateProfileImage(@ModelAttribute @Valid ProfileImageUploadRequest request) {
        guardianMyPageCommandService.updateProfileImage(request.image());
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2013")
                .message("프로필 이미지 수정 성공")
                .build();
    }

    @Override
    @PostMapping("/seniors")
    public ApiResponseTemplate<Void> addSenior(@RequestBody @Valid SeniorSignUpRequest request) {
        guardianMyPageCommandService.addSenior(request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2027")
                .message("부모님 추가 성공")
                .build();
    }

    @Override
    @PostMapping("/phone/sms")
    public ApiResponseTemplate<Void> sendPhoneChangeSms(@RequestBody @Valid UpdatePhoneRequest request) {
        guardianMyPageCommandService.sendPhoneChangeSms(request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2025")
                .message("인증 SMS가 발송되었습니다.")
                .build();
    }

    @Override
    @PostMapping("/phone/verify")
    public ApiResponseTemplate<Void> verifyPhoneChangeCode(@RequestBody @Valid SmsCodeRequest request) {
        guardianMyPageCommandService.verifyPhoneChangeCode(request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2026")
                .message("인증이 완료되었습니다.")
                .build();
    }

    @Override
    @PatchMapping("/phone")
    public ApiResponseTemplate<Void> updatePhone(@RequestBody @Valid UpdatePhoneRequest request) {
        guardianMyPageCommandService.updatePhone(request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2028")
                .message("전화번호 변경이 완료되었습니다.")
                .build();
    }

    @Override
    @GetMapping("/seniors")
    public ApiResponseTemplate<ConnectedSeniorResponse> getConnectedSeniors() {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2014")
                .message("연결된 시니어 목록 조회 성공")
                .body(guardianMyPageQueryService.getConnectedSeniors());
    }

    @Override
    @GetMapping("/seniors/{memberId}")
    public ApiResponseTemplate<SeniorProfileForGuardianResponse> getSeniorProfile(@PathVariable Long memberId) {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2015")
                .message("시니어 프로필 조회 성공")
                .body(guardianMyPageQueryService.getSeniorProfile(memberId));
    }

    @Override
    @GetMapping("/family-code")
    public ApiResponseTemplate<FamilyCodeResponse> getFamilyCode() {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2022")
                .message("가족코드 조회 성공")
                .body(guardianMyPageQueryService.getFamilyCode());
    }

    @Override
    @PatchMapping("/seniors/{memberId}/name")
    public ApiResponseTemplate<Void> updateSeniorName(@PathVariable Long memberId,
                                                       @RequestBody @Valid UpdateNameRequest request) {
        guardianMyPageCommandService.updateSeniorName(memberId, request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2023")
                .message("시니어 이름 수정 성공")
                .build();
    }

    @Override
    @PatchMapping("/seniors/{memberId}/phone")
    public ApiResponseTemplate<Void> updateSeniorPhone(@PathVariable Long memberId,
                                                        @RequestBody @Valid UpdatePhoneRequest request) {
        guardianMyPageCommandService.updateSeniorPhone(memberId, request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2016")
                .message("시니어 전화번호 수정 성공")
                .build();
    }

    @Override
    @PatchMapping("/seniors/{memberId}/address")
    public ApiResponseTemplate<Void> updateSeniorAddress(@PathVariable Long memberId,
                                                          @RequestBody @Valid UpdateSeniorAddressRequest request) {
        guardianMyPageCommandService.updateSeniorAddress(memberId, request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2017")
                .message("시니어 주소 수정 성공")
                .build();
    }

    @Override
    @PatchMapping(value = "/seniors/{memberId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseTemplate<Void> updateSeniorProfileImage(@PathVariable Long memberId,
                                                               @ModelAttribute @Valid ProfileImageUploadRequest request) {
        guardianMyPageCommandService.updateSeniorProfileImage(memberId, request.image());
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2018")
                .message("시니어 프로필 이미지 수정 성공")
                .build();
    }

    @Override
    @PatchMapping("/seniors/{memberId}/invite-code")
    public ApiResponseTemplate<Void> updateSeniorInviteCode(@PathVariable Long memberId,
                                                             @RequestBody @Valid UpdateInviteCodeRequest request) {
        guardianMyPageCommandService.updateSeniorInviteCode(memberId, request);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2024")
                .message("시니어 초대코드 수정 성공")
                .build();
    }

    @Override
    @GetMapping("/family/members")
    public ApiResponseTemplate<FamilyMemberListResponse> getFamilyMembers() {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2019")
                .message("가족 멤버 목록 조회 성공")
                .body(guardianMyPageQueryService.getFamilyMembers());
    }

    @Override
    @PatchMapping("/family/members/{memberId}/leader")
    public ApiResponseTemplate<Void> changeLeader(@PathVariable Long memberId) {
        guardianMyPageCommandService.changeLeader(memberId);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2020")
                .message("방장 변경 성공")
                .build();
    }

    @Override
    @PatchMapping("/family/members/order")
    public ApiResponseTemplate<GuardianOrderUpdateResponse> updateGuardianOrder(
            @RequestBody @Valid GuardianOrderUpdateRequest request) {
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2029")
                .message("보호자 순서 변경 성공")
                .body(guardianMyPageCommandService.updateGuardianOrder(request));
    }

    @Override
    @DeleteMapping("/family/members/{memberId}")
    public ApiResponseTemplate<Void> deleteFamilyMember(@PathVariable Long memberId) {
        guardianMyPageCommandService.deleteFamilyMember(memberId);
        return ApiResponseTemplate.ok()
                .code("MYPAGE_2021")
                .message("가족 멤버 삭제 성공")
                .build();
    }
}
