package com.widyu.global.infrastructure.s3;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.S3Properties;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

@Slf4j
@Service
public class S3DirectUploadServiceImpl implements S3DirectUploadService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    public S3DirectUploadServiceImpl(S3Client s3Client, @Lazy S3Presigner s3Presigner, S3Properties s3Properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.s3Properties = s3Properties;
    }

    @Override
    public String presignPut(String objectKey, String contentType, long contentLength, Duration expiry) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putObjectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    @Override
    public String presignGet(String objectKey, Duration expiry) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public String createMultipartUpload(String objectKey, String contentType) {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .contentType(contentType)
                .build();

        return s3Client.createMultipartUpload(request).uploadId();
    }

    @Override
    public String presignUploadPart(String objectKey, String uploadId, int partNumber, Duration expiry) {
        UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(expiry)
                .uploadPartRequest(uploadPartRequest)
                .build();

        return s3Presigner.presignUploadPart(presignRequest).url().toString();
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<PartETag> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(PartETag::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();

        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();

        s3Client.completeMultipartUpload(request);
        log.info("S3 multipart 업로드 완료: key={}, parts={}", objectKey, completedParts.size());
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .uploadId(uploadId)
                .build();

        s3Client.abortMultipartUpload(request);
        log.info("S3 multipart 업로드 중단: key={}", objectKey);
    }

    @Override
    public Optional<ObjectMetadata> headObject(String objectKey) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName())
                    .key(objectKey)
                    .build();

            HeadObjectResponse response = s3Client.headObject(request);
            return Optional.of(new ObjectMetadata(response.contentLength(), response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // HEAD 응답에는 에러 본문이 없어 404가 NoSuchKeyException으로 매핑되지 않을 수 있다
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public String copyObject(String sourceKey, String destinationKey) {
        CopyObjectRequest request = CopyObjectRequest.builder()
                .sourceBucket(bucketName())
                .sourceKey(sourceKey)
                .destinationBucket(bucketName())
                .destinationKey(destinationKey)
                .build();

        s3Client.copyObject(request);
        return s3Properties.s3().baseUrl() + "/" + destinationKey;
    }

    @Override
    public void deleteObject(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName())
                .key(objectKey)
                .build();

        s3Client.deleteObject(request);
    }

    @Override
    public File downloadToTempFile(String objectKey) {
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("staged_" + UUID.randomUUID(), extensionSuffix(objectKey));
            Files.delete(tempPath);

            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName())
                    .key(objectKey)
                    .build();

            s3Client.getObject(request, tempPath);
            return tempPath.toFile();
        } catch (Exception e) {
            // 다운로드 중 실패하면 부분 파일이 남을 수 있으므로 호출자에게 전달되기 전에 정리한다
            deletePartialFileQuietly(tempPath);
            log.error("S3 객체 다운로드 실패: key={}, error={}", objectKey, e.getMessage());
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private void deletePartialFileQuietly(Path tempPath) {
        if (tempPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException e) {
            log.warn("부분 다운로드 파일 삭제 실패: path={}, error={}", tempPath, e.getMessage());
        }
    }

    private String bucketName() {
        return s3Properties.s3().bucketName();
    }

    private String extensionSuffix(String objectKey) {
        int extensionIndex = objectKey.lastIndexOf('.');
        if (extensionIndex < 0) {
            return "";
        }
        return objectKey.substring(extensionIndex);
    }
}
