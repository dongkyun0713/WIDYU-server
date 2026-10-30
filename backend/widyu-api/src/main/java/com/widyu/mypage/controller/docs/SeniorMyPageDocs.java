package com.widyu.mypage.controller.docs;

import com.widyu.mypage.dto.request.ProfileImageUploadRequest;
import com.widyu.mypage.dto.request.UpdateNameRequest;
import com.widyu.mypage.dto.request.UpdatePhoneRequest;
import com.widyu.mypage.dto.response.EmergencyContactResponse;
import com.widyu.mypage.dto.response.FamilyCodeResponse;
import com.widyu.mypage.dto.response.PointHistoryResponse;
import com.widyu.mypage.dto.response.SeniorInfoResponse;
import com.widyu.mypage.dto.response.SeniorProfileDetailResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Senior MyPage", description = "시니어 마이페이지 API")
public interface SeniorMyPageDocs {

    @Operation(summary = "시니어 내 정보 조회", description = "프로필 이미지, 이름, 포인트를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<SeniorInfoResponse> getSeniorInfo();

    @Operation(summary = "가족코드 조회", description = "보호자가 가족 참여 시 사용하는 영문+숫자 6자리 가족코드를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가족코드 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<FamilyCodeResponse> getFamilyCode();

    @Operation(summary = "프로필 설정 조회", description = "이름, 생년월일, 전화번호, 주소, 초대코드를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로필 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<SeniorProfileDetailResponse> getProfileDetail();

    @Operation(summary = "이름 수정", description = "시니어의 이름을 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이름 수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<Void> updateName(UpdateNameRequest request);

    @Operation(summary = "프로필 이미지 수정", description = "프로필 이미지를 S3에 업로드하고 기존 이미지를 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 수정 성공"),
            @ApiResponse(responseCode = "400", description = "파일 오류"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<Void> updateProfileImage(ProfileImageUploadRequest request);

    @Operation(summary = "전화번호 수정", description = "시니어의 전화번호를 수정합니다. 본인 번호를 다시 저장하는 것은 허용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전화번호 수정 성공"),
            @ApiResponse(responseCode = "400", description = "AUTH_4021: 다른 회원이 이미 쓰는 전화번호"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<Void> updatePhoneNumber(UpdatePhoneRequest request);

    @Operation(summary = "포인트 내역 조회", description = "현재 포인트와 포인트 적립/사용 내역을 최신순으로 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "포인트 내역 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<PointHistoryResponse> getPointHistory();

    @Operation(summary = "비상연락처 조회", description = "대표 비상연락처와 가족 구성원 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비상연락처 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<EmergencyContactResponse> getEmergencyContacts();

    @Operation(summary = "대표 비상연락처 변경", description = "가족 구성원 중 대표 비상연락처를 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대표 번호 변경 성공"),
            @ApiResponse(responseCode = "404", description = "가족 구성원을 찾을 수 없음")
    })
    ApiResponseTemplate<Void> updateRepresentativeContact(Long memberId);
}
