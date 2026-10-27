package com.widyu.sensor.dto.response;

/** 시간대 내보내기 zip 한 개(LLD-0108). 컨트롤러가 첨부 파일 응답으로 바꾼다. */
public record SensorWindowExportFile(String fileName, byte[] bytes) {

    public static SensorWindowExportFile of(String fileName, byte[] bytes) {
        return new SensorWindowExportFile(fileName, bytes);
    }
}
