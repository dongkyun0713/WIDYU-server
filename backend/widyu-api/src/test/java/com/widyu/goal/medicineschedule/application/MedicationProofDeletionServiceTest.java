package com.widyu.goal.medicineschedule.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.global.infrastructure.s3.deletion.S3ObjectDeletionTaskService;
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
    @Mock private S3ObjectDeletionTaskService s3ObjectDeletionTaskService;

    @InjectMocks private MedicationProofDeletionService medicationProofDeletionService;

    @Test
    @DisplayName("회원 탈퇴 처리 시 복약 인증 레코드를 삭제하고 인증 사진 삭제를 예약한다")
    void 회원_탈퇴_처리_시_복약_인증_레코드를_삭제하고_사진_삭제를_예약한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        MedicationProof proof = MedicationProof.create(null, null,
                List.of("https://cdn.example.com/medication-proof/1/proof.jpg"));
        given(medicationProofRepository.findAllByMember(member)).willReturn(List.of(proof));

        // when
        medicationProofDeletionService.deleteAllByMember(member);

        // then
        verify(medicationProofRepository).deleteAll(List.of(proof));
        verify(s3ObjectDeletionTaskService).schedule(1L, List.of("https://cdn.example.com/medication-proof/1/proof.jpg"));
    }
}
