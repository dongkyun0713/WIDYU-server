package com.widyu.global.infrastructure.s3;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Presigned URL 기반 S3 직접 업로드(발급·완료·검증·정리)를 지원하는 서비스
 */
public interface S3DirectUploadService {

    record PartETag(int partNumber, String eTag) {
    }

    record ObjectMetadata(long contentLength, String contentType) {
    }

    String presignPut(String objectKey, String contentType, long contentLength, Duration expiry);

    /** 내보내기 zip 다운로드용 presigned GET(LLD-0050 5.8). */
    String presignGet(String objectKey, Duration expiry);

    String createMultipartUpload(String objectKey, String contentType);

    String presignUploadPart(String objectKey, String uploadId, int partNumber, Duration expiry);

    void completeMultipartUpload(String objectKey, String uploadId, List<PartETag> parts);

    void abortMultipartUpload(String objectKey, String uploadId);

    Optional<ObjectMetadata> headObject(String objectKey);

    String copyObject(String sourceKey, String destinationKey);

    void deleteObject(String objectKey);

    File downloadToTempFile(String objectKey);
}
