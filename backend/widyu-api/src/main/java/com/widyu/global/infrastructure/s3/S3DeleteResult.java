package com.widyu.global.infrastructure.s3;

public record S3DeleteResult(boolean deleted, String errorType) {

    public static S3DeleteResult success() {
        return new S3DeleteResult(true, null);
    }

    public static S3DeleteResult failure(String errorType) {
        return new S3DeleteResult(false, errorType);
    }
}
