package com.widyu.global.infrastructure.s3.deletion;

import java.util.List;
public record S3ObjectDeletionEvent(List<Long> taskIds) {

    public S3ObjectDeletionEvent {
        taskIds = taskIds.stream().filter(java.util.Objects::nonNull).toList();
    }
}
