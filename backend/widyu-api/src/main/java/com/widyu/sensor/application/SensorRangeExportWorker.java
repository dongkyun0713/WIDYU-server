package com.widyu.sensor.application;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.sensor.SensorRangeExport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SensorRangeExportWorker {

    private final SensorRangeExportTransactions transactions;
    private final SensorRangeExportAssembler assembler;
    private final S3Service s3Service;

    @Scheduled(fixedDelayString = "${sensor.export.poll-delay-ms:5000}")
    public void poll() {
        Optional<SensorRangeExport> claimed = transactions.claim();
        claimed.ifPresent(this::run);
    }

    private void run(SensorRangeExport export) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("widyu-sensor-range-export-");
            assembler.build(export, workDir);
            Path zip = workDir.resolve("sensor_%s.zip".formatted(export.getExportId()));
            zipDirectory(workDir, zip);

            String s3Key = "exports/sensor-range/%d/%s/%d.zip"
                    .formatted(export.getMemberId(), export.getExportId(), export.getStartedAtMs());
            s3Service.uploadLocalFile(s3Key, zip.toFile(), "application/zip");
            if (transactions.finish(export.getId(), export.getStartedAtMs(), s3Key,
                    Files.size(zip), sha256(zip))) {
                log.info("센서 시간 범위 내보내기 완료: exportId={}", export.getExportId());
            }
        } catch (Exception e) {
            log.error("센서 시간 범위 내보내기 실패: exportId={}, errorType={}",
                    export.getExportId(), e.getClass().getSimpleName());
            transactions.fail(export.getId(), export.getStartedAtMs(), e.getClass().getSimpleName());
        } finally {
            deleteQuietly(workDir);
        }
    }

    private void zipDirectory(Path workDir, Path zip) throws IOException {
        try (OutputStream output = Files.newOutputStream(zip);
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            List<Path> entries;
            try (var walk = Files.walk(workDir)) {
                entries = walk.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(zip))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            for (Path entry : entries) {
                zipOutput.putNextEntry(new ZipEntry(workDir.relativize(entry).toString()));
                Files.copy(entry, zipOutput);
                zipOutput.closeEntry();
            }
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private void deleteQuietly(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("센서 시간 범위 내보내기 임시 디렉터리 삭제 실패: errorType={}",
                    e.getClass().getSimpleName());
        }
    }
}
