package com.widyu.global.infrastructure.s3;

import org.springframework.web.multipart.MultipartFile;

public interface S3Service {
    String uploadFile(MultipartFile file, String filePath);
    String generateFilePath(String directory, String fileName);
    boolean deleteFile(String url);
}
