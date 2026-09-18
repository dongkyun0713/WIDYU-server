package com.widyu.global.infrastructure.s3;

import org.springframework.web.multipart.MultipartFile;

public interface S3Service {
    String uploadFile(MultipartFile file, String filePath);
    /** 메모리 위의 바이트를 지정한 키에 그대로 올린다(LLD-0041). 같은 키의 재전송은 덮어쓴다. */
    void uploadBytes(String objectKey, byte[] bytes, String contentType);
    String generateFilePath(String directory, String fileName);
    boolean deleteFile(String url);
    S3DeleteResult deleteFileByKey(String objectKey);
    String extractObjectKey(String url);
}
