package com.widyu.run.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.run.dto.response.RunExportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Run Export", description = "측정회차 내보내기 API (운영자)")
public interface AdminRunExportDocs {

    @Operation(
            summary = "회차 내보내기 요청",
            description = """
                    닫힌 측정회차를 `run_<run_id>.zip` 하나로 내보냅니다. 회차가 크면 즉시 만들 수 없어
                    큐에 넣고 워커가 하나씩 조립합니다. 응답은 `202`이며 상태 조회로 진행을 확인합니다.

                    **같은 회차에 진행 중인 잡이 있으면 새로 만들지 않고 그것을 돌려줍니다.**
                    열린 회차는 `RUN_4003`입니다 — 자료가 계속 들어오는 중이라 목록을 확정할 수 없습니다.

                    zip 구성: `manifest.json`·`run.json`·`clock_mappings.json`·`quality.json`·
                    `streams/<스트림>_<기기>.jsonl`. 형식 정본은 「서버 내보내기 형식서」입니다.
                    """
    )
    @ApiResponse(responseCode = "202", description = "접수 또는 진행 중인 잡 반환")
    @ApiResponse(
            responseCode = "400",
            description = "열린 회차",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4003", "message": "닫힌 측정회차만 내보낼 수 있습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<RunExportResponse> requestExport(String runId);

    @Operation(
            summary = "회차 내보내기 상태 조회",
            description = """
                    `QUEUED` → `RUNNING` → `DONE`/`FAILED`로 옮겨갑니다.

                    `DONE`이면 **조회 시점에 15분짜리 presigned URL을 발급해** `downloadUrl`에 담습니다.
                    저장해 두지 않는 이유는 만료된 URL을 돌려주지 않기 위해서입니다.

                    `FAILED`면 `errorType`에 예외 **클래스명만** 담깁니다. 메시지와 자료 값은 담지 않습니다.
                    실패한 잡은 다시 요청하면 됩니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "code": "200",
                      "message": "내보내기 상태를 조회했습니다.",
                      "data": {
                        "exportId": "exp-0f3a…",
                        "runId": "run-0f3a…",
                        "status": "DONE",
                        "requestedAtMs": 1760000000000,
                        "startedAtMs": 1760000005000,
                        "finishedAtMs": 1760000012000,
                        "fileName": "run_run-0f3a….zip",
                        "bytes": 18234112,
                        "sha256": "…",
                        "downloadUrl": "https://…",
                        "downloadExpiresAtMs": 1760000912000,
                        "errorType": null
                      }
                    }
                    """))
    )
    @ApiResponse(
            responseCode = "404",
            description = "회차 또는 내보내기 없음",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4042", "message": "내보내기 작업을 찾을 수 없습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<RunExportResponse> getExport(String runId, String exportId);
}
