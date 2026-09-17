package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.widyu.global.error.BusinessException;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import com.widyu.member.Member;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofService 업로드 단위 테스트")
class MedicationProofServiceTest {

    @Mock private MedicationProofTransactionService transactionService;
    @Mock private MemberUtil memberUtil;
    @Mock private S3Service s3Service;

    @InjectMocks private MedicationProofService medicationProofService;

    @Test
    @DisplayName("업로드 뒤 최종 검증이 실패하면 업로드한 이미지를 삭제한다")
    void 업로드_뒤_최종_검증이_실패하면_업로드한_이미지를_삭제한다() {
        // given
        Member member = mock(Member.class);
        MultipartFile image = mock(MultipartFile.class);
        given(member.getId()).willReturn(10L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(image.getOriginalFilename()).willReturn("proof.jpg");
        given(s3Service.generateFilePath("medication-proof/10", "proof.jpg"))
                .willReturn("medication-proof/10/proof.jpg");
        given(s3Service.uploadFile(image, "medication-proof/10/proof.jpg"))
                .willReturn("https://cdn/proof.jpg");
        given(transactionService.verifyMedication(eq(10L), eq(1L), any()))
                .willThrow(new BusinessException(com.widyu.global.error.ErrorCode.BAD_REQUEST, "중복 인증"));

        // when & then
        assertThatThrownBy(() -> medicationProofService.verifyMedication(1L, List.of(image)))
                .isInstanceOf(BusinessException.class);
        then(s3Service).should().deleteFile("https://cdn/proof.jpg");
    }

    @Test
    @DisplayName("업로드 뒤 트랜잭션 서비스로 인증을 요청한다")
    void 업로드_뒤_트랜잭션_서비스로_인증을_요청한다() {
        // given
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(transactionService.verifyMedication(10L, 1L, List.of()))
                .willReturn(MedicationProofResponse.of(0L, 10L));

        // when
        medicationProofService.verifyMedication(1L, List.of());

        // then
        then(transactionService).should().verifyMedication(10L, 1L, List.of());
    }
}
