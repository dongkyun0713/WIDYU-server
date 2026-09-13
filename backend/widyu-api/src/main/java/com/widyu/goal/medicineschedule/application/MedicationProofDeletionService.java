package com.widyu.goal.medicineschedule.application;

import com.widyu.goal.medicineschedule.event.MedicationProofImagesDeletionEvent;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.member.Member;
import com.widyu.medicine.MedicationProof;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationProofDeletionService {

    private final MedicationProofRepository medicationProofRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void deleteAllByMember(Member member) {
        List<MedicationProof> proofs = medicationProofRepository.findAllByMember(member);
        List<String> imageUrls = extractImageUrls(proofs);
        medicationProofRepository.deleteAll(proofs);
        eventPublisher.publishEvent(new MedicationProofImagesDeletionEvent(member.getId(), imageUrls));

        log.info("회원 복약 인증 레코드 삭제: memberId={}, proofCount={}, imageCount={}",
                member.getId(), proofs.size(), imageUrls.size());
    }

    private List<String> extractImageUrls(List<MedicationProof> proofs) {
        return proofs.stream()
                .flatMap(proof -> proof.getProofImageUrls().stream())
                .toList();
    }
}
