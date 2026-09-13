package com.widyu.goal.medicineschedule.application;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.member.Member;
import com.widyu.medicine.MedicationProof;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationProofDeletionService {

    private final MedicationProofRepository medicationProofRepository;
    private final S3Service s3Service;

    @Transactional
    public void deleteAllByMember(Member member) {
        List<MedicationProof> proofs = medicationProofRepository.findAllByMember(member);
        int failedFileDeletionCount = deleteProofImages(proofs);
        medicationProofRepository.deleteAll(proofs);

        log.info("회원 복약 인증 데이터 삭제: memberId={}, proofCount={}, failedFileDeletionCount={}",
                member.getId(), proofs.size(), failedFileDeletionCount);
    }

    private int deleteProofImages(List<MedicationProof> proofs) {
        int failedCount = 0;
        for (MedicationProof proof : proofs) {
            for (String imageUrl : proof.getProofImageUrls()) {
                if (!s3Service.deleteFile(imageUrl)) {
                    failedCount++;
                }
            }
        }
        return failedCount;
    }
}
