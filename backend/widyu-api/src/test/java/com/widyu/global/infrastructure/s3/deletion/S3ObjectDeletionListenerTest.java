package com.widyu.global.infrastructure.s3.deletion;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3ObjectDeletionListener 단위 테스트")
class S3ObjectDeletionListenerTest {

    @Mock private S3ObjectDeletionTaskService taskService;

    @InjectMocks private S3ObjectDeletionListener listener;

    @Test
    @DisplayName("커밋 후 이벤트를 받으면 S3 파일을 삭제한다")
    void 커밋_후_이벤트를_받으면_S3_파일을_삭제한다() {
        // given
        S3ObjectDeletionEvent event = new S3ObjectDeletionEvent(List.of(1L));

        // when
        listener.deleteImagesAfterCommit(event);

        // then
        verify(taskService).process(1L);
    }

    @Test
    @DisplayName("사진 삭제가 실패해도 나머지 사진 삭제를 계속한다")
    void 사진_삭제가_실패해도_나머지_사진_삭제를_계속한다() {
        // given
        S3ObjectDeletionEvent event = new S3ObjectDeletionEvent(List.of(1L, 2L));
        willThrow(new IllegalStateException()).given(taskService).process(1L);

        // when
        listener.deleteImagesAfterCommit(event);

        // then
        verify(taskService).process(1L);
        verify(taskService).process(2L);
    }
}
