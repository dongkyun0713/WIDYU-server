package com.widyu.admin.application;

import com.widyu.admin.dto.response.AdminSearchResponse;
import com.widyu.admin.dto.response.AdminSearchResponse.MemberHit;
import com.widyu.admin.dto.response.AdminSearchResponse.PaymentHit;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.pay.Payment;
import com.widyu.pay.repository.PaymentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSearchService {

    private final MemberRepository memberRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyRepository familyRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public AdminSearchResponse search(String q) {
        String trimmed = q.trim();

        Map<Long, MemberHit> memberMap = new LinkedHashMap<>();
        List<PaymentHit> payments = new ArrayList<>();

        // 숫자 → memberId 직접 조회
        if (trimmed.matches("\\d+")) {
            memberRepository.findById(Long.parseLong(trimmed))
                    .ifPresent(m -> memberMap.put(m.getId(), toMemberHit(m)));
        }

        // 이름 검색 (최대 5명)
        if (trimmed.length() >= 2) {
            memberRepository.findTop20ByNameContainingOrderByIdDesc(trimmed)
                    .stream().limit(5)
                    .forEach(m -> memberMap.put(m.getId(), toMemberHit(m)));
        }

        // 전화번호 검색 (최대 3명)
        if (trimmed.matches("[0-9\\-]+") && trimmed.length() >= 3) {
            memberRepository.findTop3ByPhoneNumberContainingOrderByIdDesc(trimmed)
                    .forEach(m -> memberMap.put(m.getId(), toMemberHit(m)));
        }

        // 6자 → familyCode
        if (trimmed.length() == 6) {
            familyRepository.findByFamilyCode(trimmed).ifPresent(family -> {
                seniorProfileRepository.findAllByFamilyId(family.getId())
                        .forEach(sp -> memberMap.put(sp.getMember().getId(), toMemberHit(sp.getMember())));
                familyMembershipRepository.findAllByFamilyIdWithGuardian(family.getId())
                        .forEach(fm -> memberMap.put(fm.getGuardian().getId(), toMemberHit(fm.getGuardian())));
            });
        }

        // 7자 → inviteCode
        if (trimmed.length() == 7) {
            seniorProfileRepository.findByInviteCode(trimmed)
                    .ifPresent(sp -> memberMap.put(sp.getMember().getId(), toMemberHit(sp.getMember())));
        }

        // orderId → 결제 조회
        paymentRepository.findByOrderId(trimmed)
                .ifPresent(p -> payments.add(toPaymentHit(p)));

        return AdminSearchResponse.of(new ArrayList<>(memberMap.values()), payments);
    }

    private MemberHit toMemberHit(Member m) {
        return new MemberHit(m.getId(), m.getName(), m.getPhoneNumber(), m.getType(), m.getStatus());
    }

    private PaymentHit toPaymentHit(Payment p) {
        return new PaymentHit(p.getId(), p.getOrderId(), p.getMember().getName(), p.getAmount(), p.getStatus());
    }
}
