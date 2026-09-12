package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.dto.request.AdminFcmTestRequest;
import com.widyu.admin.dto.response.AdminMemberDetailResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.MemberRole;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminFcmService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final AdminAuditLogService adminAuditLogService;

    @Transactional(readOnly = true)
    public List<Member> searchMembers(String name) {
        return memberRepository.findTop20ByNameContainingOrderByIdDesc(name);
    }

    @Transactional(readOnly = true)
    public List<Member> getAllMembers() {
        return memberRepository.findTop50ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public AdminPageResponse<AdminMemberDetailResponse> getMemberPage(String name, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").descending());
        Page<AdminMemberDetailResponse> result;
        if (name != null && !name.isBlank()) {
            result = memberRepository.findByNameContainingOrderByIdDesc(name, pageRequest).map(AdminMemberDetailResponse::from);
        } else {
            result = memberRepository.findAllByOrderByIdDesc(pageRequest).map(AdminMemberDetailResponse::from);
        }
        return AdminPageResponse.from(result);
    }

    @Transactional
    public String sendTestNotification(AdminFcmTestRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        List<MemberFcmToken> tokens = memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(request.memberId());
        if (tokens.isEmpty()) {
            return member.getName() + "님의 등록된 FCM 토큰이 없습니다.";
        }

        FcmCategory category = request.category();
        if (category == null) {
            category = FcmCategory.ETC;
        }
        FcmSendDto fcmSendDto = FcmSendDto.builder()
                .title(request.title())
                .content(request.content())
                .fcmCategory(category)
                .scheme("")
                .image(null)
                .build();

        int sent = fcmService.sendTestMessageToUser(request.memberId(), fcmSendDto);
        String resultMsg;
        if (sent == 0) {
            resultMsg = member.getName() + "님에게 전송 시도했으나 FCM 서버 응답 실패 (토큰 " + tokens.size() + "개)";
        } else {
            resultMsg = member.getName() + "님에게 알림을 전송했습니다. (" + sent + "/" + tokens.size() + "개 성공)";
        }
        adminAuditLogService.log(
                AdminAction.FCM_TEST_SEND, "MEMBER", request.memberId(),
                "'" + request.title() + "' → " + sent + "/" + tokens.size() + "개 성공"
        );
        return resultMsg;
    }
}
