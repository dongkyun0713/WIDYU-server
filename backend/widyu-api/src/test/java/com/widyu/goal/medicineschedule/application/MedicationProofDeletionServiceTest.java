package com.widyu.goal.medicineschedule.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.goal.medicineschedule.event.MedicationProofImagesDeletionEvent;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.medicine.MedicationProof;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofDeletionService 단위 테스트")
class MedicationProofDeletionServiceTest {

    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private MedicationProofDeletionService medicationProofDeletionService;

    @Test
    @DisplayName("회원 탈퇴 처리 시 복약 인증 레코드를 삭제하고 사진 삭제 이벤트를 발행한다")
    void 회원_탈퇴_처리_시_복약_인증_레코드를_삭제하고_사진_삭제_이벤트를_발행한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        MedicationProof proof = createProof("https://cdn.example.com/medication-proof/1/proof.jpg");
        given(medicationProofRepository.findAllByMember(member)).willReturn(List.of(proof));

        // when
        medicationProofDeletionService.deleteAllByMember(member);

        // then
        verify(medicationProofRepository).deleteAll(List.of(proof));
        ArgumentCaptor<MedicationProofImagesDeletionEvent> eventCaptor =
                ArgumentCaptor.forClass(MedicationProofImagesDeletionEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MedicationProofImagesDeletionEvent event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.memberId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(event.imageUrls())
                .containsExactly("https://cdn.example.com/medication-proof/1/proof.jpg");
    }

    @Test
    @DisplayName("인증 사진이 없을 때도 빈 삭제 이벤트를 발행한다")
    void 인증_사진이_없을_때도_빈_삭제_이벤트를_발행한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        MedicationProof proof = createProof();
        given(medicationProofRepository.findAllByMember(member)).willReturn(List.of(proof));

        // when
        medicationProofDeletionService.deleteAllByMember(member);

        // then
        verify(medicationProofRepository).deleteAll(List.of(proof));
        ArgumentCaptor<MedicationProofImagesDeletionEvent> eventCaptor =
                ArgumentCaptor.forClass(MedicationProofImagesDeletionEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().imageUrls()).isEmpty();
    }

    private MedicationProof createProof(String imageUrl) {
        MedicationProof proof = MedicationProof.create(null, null, List.of(imageUrl));
        return proof;
    }

    private MedicationProof createProof() {
        return MedicationProof.create(null, null, List.of());
    }
}
