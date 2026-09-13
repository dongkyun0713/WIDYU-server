package com.widyu.goal.medicineschedule.application;

import com.widyu.goal.medicineschedule.event.MedicationProofImagesDeletionEvent;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofImageDeletionTaskRepository;
import com.widyu.member.Member;
import com.widyu.medicine.MedicationProof;
import java.util.List;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.medicine.MedicationProofImageDeletionTask;
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
    private final MedicationProofImageDeletionTaskRepository deletionTaskRepository;
    private final S3Service s3Service;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void deleteAllByMember(Member member) {
        List<MedicationProof> proofs = medicationProofRepository.findAllByMember(member);
        List<MedicationProofImageDeletionTask> tasks = extractImageUrls(proofs).stream().map(url -> createTask(member.getId(), url)).filter(java.util.Objects::nonNull).toList();
        List<MedicationProofImageDeletionTask> savedTasks = deletionTaskRepository.saveAll(tasks);
        medicationProofRepository.deleteAll(proofs);
        eventPublisher.publishEvent(new MedicationProofImagesDeletionEvent(
                savedTasks.stream().map(MedicationProofImageDeletionTask::getId).toList()));

        log.info("회원 복약 인증 레코드 삭제: memberId={}, proofCount={}, imageCount={}",
                member.getId(), proofs.size(), tasks.size());
    }

    private List<String> extractImageUrls(List<MedicationProof> proofs) {
        return proofs.stream()
                .flatMap(proof -> proof.getProofImageUrls().stream())
                .toList();
    }
    private MedicationProofImageDeletionTask createTask(Long memberId, String imageUrl) { try { return MedicationProofImageDeletionTask.pending(memberId, s3Service.extractObjectKey(imageUrl)); } catch (Exception e) { log.error("복약 인증 사진 삭제 작업 생성 실패: memberId={}, errorType={}", memberId, e.getClass().getSimpleName()); return null; } }
}
