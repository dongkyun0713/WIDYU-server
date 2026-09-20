package com.widyu.run.application;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.run.CollectionRun;
import com.widyu.run.RunExport;
import com.widyu.run.repository.CollectionRunRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * 내보내기 큐를 도는 워커(LLD-0050 5.2, ADR-0032 결정 2).
 * {@code @Async}가 아니라 폴링이라 프로세스가 재시작돼도 큐가 남는다.
 * 상태 갱신은 {@code REQUIRES_NEW}로 짧게, 자료 읽기는 조립기의 {@code readOnly}로 나눈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunExportWorker {

    private static final String CONTENT_TYPE_ZIP = "application/zip";

    private final RunExportTransactions transactions;
    private final CollectionRunRepository collectionRunRepository;
    private final RunExportAssembler runExportAssembler;
    private final S3Service s3Service;

    @Scheduled(fixedDelayString = "${sensor.export.poll-delay-ms:5000}")
    public void poll() {
        Optional<RunExport> claimed = transactions.claim();
        if (claimed.isEmpty()) {
            return;
        }
        run(claimed.get());
    }

    private void run(RunExport export) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("widyu-export-");
            CollectionRun run = collectionRunRepository.findByRunId(export.getRunId()).orElseThrow();
            runExportAssembler.build(run, workDir);

            Path zip = workDir.resolve("run_%s.zip".formatted(export.getRunId()));
            zipDirectory(workDir, zip);
            byte[] zipBytes = Files.readAllBytes(zip);
            s3Service.uploadLocalFile(s3Key(export), zip.toFile(), CONTENT_TYPE_ZIP);
            transactions.finish(export.getId(), s3Key(export), zipBytes.length, sha256(zipBytes));
            log.info("회차 내보내기 완료: exportId={}, runId={}, bytes={}",
                    export.getExportId(), export.getRunId(), zipBytes.length);
        } catch (Exception e) {
            // 자료 값은 남기지 않는다. 예외 클래스명만 남긴다.
            log.error("회차 내보내기 실패: exportId={}, runId={}, errorType={}",
                    export.getExportId(), export.getRunId(), e.getClass().getSimpleName());
            transactions.fail(export.getId(), e.getClass().getSimpleName());
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String s3Key(RunExport export) {
        return "exports/%s/%s/run_%s.zip"
                .formatted(export.getRunId(), export.getExportId(), export.getRunId());
    }

    /** zip 안의 경로는 최상위 파일과 {@code streams/}뿐이다(형식서 §1). */
    private void zipDirectory(Path workDir, Path zip) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            List<Path> entries;
            try (var walk = Files.walk(workDir)) {
                entries = walk.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(zip))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            for (Path entry : entries) {
                zipOut.putNextEntry(new ZipEntry(workDir.relativize(entry).toString()));
                Files.copy(entry, zipOut);
                zipOut.closeEntry();
            }
        }
    }

    private void deleteQuietly(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("내보내기 임시 디렉터리 삭제 실패: errorType={}", e.getClass().getSimpleName());
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
