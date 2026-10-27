package com.widyu.sensor.controller;

import com.widyu.sensor.application.SensorWindowExportService;
import com.widyu.sensor.controller.docs.AdminSensorExportDocs;
import com.widyu.sensor.dto.response.SensorWindowExportFile;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/{memberId}/sensor-exports")
public class AdminSensorExportController implements AdminSensorExportDocs {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final SensorWindowExportService sensorWindowExportService;

    @Override
    @GetMapping
    public ResponseEntity<byte[]> export(
            @PathVariable Long memberId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "10") int padMinutes) {
        SensorWindowExportFile file = sensorWindowExportService.export(memberId, from, to, padMinutes);
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.bytes());
    }
}
