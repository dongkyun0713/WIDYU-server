package com.widyu.goal.medicineschedule.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.medicine.MedicationProof;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofDeletionService 단위 테스트")
class MedicationProofDeletionServiceTest {

    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private S3Service s3Service;

    @InjectMocks private MedicationProofDeletionService medicationProofDeletionService;

    @Test
    @DisplayName("회원 탈퇴 처리 시 복약 인증 사진과 레코드를 삭제한다")
    void 회원_탈퇴_처리_시_복약_인증_사진과_레코드를_삭제한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        MedicationProof proof = createProof("https://cdn.example.com/medication-proof/1/proof.jpg");
        given(medicationProofRepository.findAllByMember(member)).willReturn(List.of(proof));
        given(s3Service.deleteFile("https://cdn.example.com/medication-proof/1/proof.jpg")).willReturn(true);

        // when
        medicationProofDeletionService.deleteAllByMember(member);

        // then
        verify(s3Service).deleteFile("https://cdn.example.com/medication-proof/1/proof.jpg");
        verify(medicationProofRepository).deleteAll(List.of(proof));
    }

    @Test
    @DisplayName("인증 사진 삭제가 실패해도 복약 인증 레코드를 삭제한다")
    void 인증_사진_삭제가_실패해도_복약_인증_레코드를_삭제한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        MedicationProof proof = createProof("https://cdn.example.com/medication-proof/1/proof.jpg");
        given(medicationProofRepository.findAllByMember(member)).willReturn(List.of(proof));
        given(s3Service.deleteFile("https://cdn.example.com/medication-proof/1/proof.jpg")).willReturn(false);

        // when
        medicationProofDeletionService.deleteAllByMember(member);

        // then
        verify(medicationProofRepository).deleteAll(List.of(proof));
    }

    private MedicationProof createProof(String imageUrl) {
        MedicationProof proof = MedicationProof.create(null, null, List.of(imageUrl));
        return proof;
    }
}
