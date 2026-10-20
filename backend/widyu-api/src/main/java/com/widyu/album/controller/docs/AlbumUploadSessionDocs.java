package com.widyu.album.controller.docs;

import com.widyu.album.dto.request.AlbumUploadCompleteRequest;
import com.widyu.album.dto.request.AlbumUploadSessionCreateRequest;
import com.widyu.album.dto.response.AlbumUploadAcceptedResponse;
import com.widyu.album.dto.response.AlbumUploadSessionResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Album-Direct-Upload", description = "앨범 Presigned URL 직접 업로드 API - 세션 발급, 완료")
public interface AlbumUploadSessionDocs {

    @Operation(
            summary = "앨범 업로드 세션 발급",
            description = """
                    S3 직접 업로드를 위한 presigned URL 세션을 발급합니다.

                    **흐름:**
                    1. 파일 메타데이터(이름·타입·크기)를 전달하면 파일별 업로드 URL을 반환합니다.
                    2. 이미지: `uploadUrl`로 단건 PUT (선언한 Content-Type·Content-Length 그대로 전송)
                    3. 동영상: `parts`의 URL별로 `partSizeBytes` 단위 분할 PUT, 응답 헤더의 `ETag`를 수집
                    4. 업로드 후 완료 API(`/uploads/{sessionId}/complete`)를 호출합니다.

                    **업로드 제한사항:**
                    - 전체 미디어: 최대 8개 (사진 + 동영상 합계)
                    - 사진: 최대 8개 (각각 최대 10MB)
                    - 동영상: 최대 3개 (각각 최대 2GB)

                    **유효 시간:**
                    - presigned URL: 1시간
                    - 업로드 세션: 6시간
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "업로드 세션 발급 성공",
                    content = @Content(
                            schema = @Schema(implementation = AlbumUploadSessionResponse.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2012",
                                              "message": "업로드 세션이 발급되었습니다.",
                                              "data": {
                                                "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                                                "expiresInSeconds": 3600,
                                                "files": [
                                                  {
                                                    "index": 0,
                                                    "mediaType": "VIDEO",
                                                    "objectKey": "albums/staging/1/550e8400.../0_a1b2c3d4.mp4",
                                                    "uploadUrl": null,
                                                    "partSizeBytes": 10485760,
                                                    "parts": [
                                                      { "partNumber": 1, "uploadUrl": "https://..." }
                                                    ]
                                                  },
                                                  {
                                                    "index": 1,
                                                    "mediaType": "PHOTO",
                                                    "objectKey": "albums/staging/1/550e8400.../1_e5f6a7b8.jpg",
                                                    "uploadUrl": "https://...",
                                                    "partSizeBytes": null,
                                                    "parts": null
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (파일 크기/개수 초과, 지원하지 않는 파일 형식 등)",
                    content = @Content(
                            examples = {
                                    @ExampleObject(
                                            name = "파일 개수 초과",
                                            value = """
                                                    {
                                                      "code": "REQ_4000",
                                                      "message": "잘못된 요청입니다. - 전체 최대 8개, 사진 최대 8개, 동영상 최대 3개까지 업로드 가능합니다.",
                                                      "data": null
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "지원하지 않는 파일 형식",
                                            value = """
                                                    {
                                                      "code": "FILE_4002",
                                                      "message": "지원하지 않는 파일 형식입니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패"
            )
    })
    ApiResponseTemplate<AlbumUploadSessionResponse> createUploadSession(
            @RequestBody @Valid AlbumUploadSessionCreateRequest request
    );

    @Operation(
            summary = "앨범 업로드 완료",
            description = """
                    S3 직접 업로드 완료를 접수하고 앨범을 생성합니다.

                    **처리 내용:**
                    - 동영상 multipart 업로드를 완료합니다. (파일 index별 partNumber·ETag 필요)
                    - 업로드된 파일의 크기·Content-Type을 선언 값과 대조합니다.
                    - 동영상이 있으면 앨범을 PROCESSING 상태로 저장하고 비동기 처리(압축·썸네일) 후 ACTIVE로 전환합니다.
                    - 동일 세션에 대한 중복 호출은 동일한 albumId를 반환합니다. (멱등)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "앨범 업로드 완료 요청 접수",
                    content = @Content(
                            schema = @Schema(implementation = AlbumUploadAcceptedResponse.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2013",
                                              "message": "앨범 업로드 완료 요청이 접수되었습니다.",
                                              "data": {
                                                "albumId": 123
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "업로드 미완료 또는 파일 정보 불일치",
                    content = @Content(
                            examples = {
                                    @ExampleObject(
                                            name = "업로드 미완료",
                                            value = """
                                                    {
                                                      "code": "ALBUM_UPLOAD_4001",
                                                      "message": "완료되지 않은 업로드 파트가 있습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "파일 정보 불일치",
                                            value = """
                                                    {
                                                      "code": "ALBUM_UPLOAD_4002",
                                                      "message": "업로드된 파일이 요청 정보와 일치하지 않습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "본인의 업로드 세션이 아님",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "세션 소유자 불일치",
                                    value = """
                                            {
                                              "code": "ALBUM_UPLOAD_4030",
                                              "message": "본인의 업로드 세션만 사용할 수 있습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "세션 없음 또는 만료",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "세션 만료",
                                    value = """
                                            {
                                              "code": "ALBUM_UPLOAD_4040",
                                              "message": "업로드 세션을 찾을 수 없거나 만료되었습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "동일 세션의 완료 처리가 이미 진행 중",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "완료 처리 진행 중",
                                    value = """
                                            {
                                              "code": "ALBUM_UPLOAD_4090",
                                              "message": "업로드 완료 처리가 이미 진행 중입니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "영상 처리 대기열이 가득 참. 앨범·업로드 세션·스테이징 파일은 정리되므로 업로드 세션 생성부터 다시 시도해야 함",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "영상 처리 대기열 포화",
                                    value = """
                                            {
                                              "code": "SRV_5031",
                                              "message": "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ResponseEntity<ApiResponseTemplate<AlbumUploadAcceptedResponse>> completeUpload(
            @Parameter(description = "업로드 세션 ID", required = true) @PathVariable String sessionId,
            @RequestBody @Valid AlbumUploadCompleteRequest request
    );
}
