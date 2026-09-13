package com.widyu.global.infrastructure.s3;

import com.widyu.global.properties.S3Properties;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    @Override
    public String uploadFile(MultipartFile file, String filePath) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.s3().bucketName())
                    .key(filePath)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            
            String fileUrl = s3Properties.s3().baseUrl() + "/" + filePath;
            log.info("S3 파일 업로드 성공: {}", filePath);
            return fileUrl;
            
        } catch (IOException e) {
            log.error("S3 파일 업로드 실패: filePath={}, error={}", filePath, e.getMessage());
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public boolean deleteFile(String fileUrl) {
        try {
            String fileName = extractFileNameFromUrl(fileUrl);
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(s3Properties.s3().bucketName())
                    .key(fileName)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("S3 파일 삭제 성공");
            return true;
        } catch (Exception e) {
            log.error("S3 파일 삭제 실패: errorType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String generateFilePath(String directory, String fileName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String extension = getFileExtension(fileName);
        
        return String.format("%s/%s_%s.%s", directory, timestamp, uuid, extension);
    }

    private String getFileExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "unknown";
        }
        return originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
    }

    private String extractFileNameFromUrl(String fileUrl) {
        String baseUrl = s3Properties.s3().baseUrl();
        if (fileUrl == null || !fileUrl.startsWith(baseUrl)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_URL);
        }
        return fileUrl.substring(baseUrl.length() + 1);
    }
}
