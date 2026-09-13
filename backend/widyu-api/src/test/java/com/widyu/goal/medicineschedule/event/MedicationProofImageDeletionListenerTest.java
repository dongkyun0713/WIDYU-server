package com.widyu.goal.medicineschedule.event;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.global.infrastructure.s3.S3Service;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofImageDeletionListener 단위 테스트")
class MedicationProofImageDeletionListenerTest {

    @Mock private S3Service s3Service;

    @InjectMocks private MedicationProofImageDeletionListener listener;

    @Test
    @DisplayName("커밋 후 이벤트를 받으면 복약 인증 사진을 삭제한다")
    void 커밋_후_이벤트를_받으면_복약_인증_사진을_삭제한다() {
        // given
        String imageUrl = "https://cdn.example.com/medication-proof/1/proof.jpg";
        MedicationProofImagesDeletionEvent event = new MedicationProofImagesDeletionEvent(1L, List.of(imageUrl));
        given(s3Service.deleteFile(imageUrl)).willReturn(true);

        // when
        listener.deleteImagesAfterCommit(event);

        // then
        verify(s3Service).deleteFile(imageUrl);
    }

    @Test
    @DisplayName("사진 삭제가 실패해도 나머지 사진 삭제를 계속한다")
    void 사진_삭제가_실패해도_나머지_사진_삭제를_계속한다() {
        // given
        String failedImageUrl = "https://cdn.example.com/medication-proof/1/failed.jpg";
        String succeededImageUrl = "https://cdn.example.com/medication-proof/1/succeeded.jpg";
        MedicationProofImagesDeletionEvent event = new MedicationProofImagesDeletionEvent(
                1L, List.of(failedImageUrl, succeededImageUrl));
        given(s3Service.deleteFile(failedImageUrl)).willReturn(false);
        given(s3Service.deleteFile(succeededImageUrl)).willReturn(true);

        // when
        listener.deleteImagesAfterCommit(event);

        // then
        verify(s3Service).deleteFile(failedImageUrl);
        verify(s3Service).deleteFile(succeededImageUrl);
    }
}
