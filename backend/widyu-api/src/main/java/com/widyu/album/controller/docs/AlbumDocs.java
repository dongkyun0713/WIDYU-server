package com.widyu.album.controller.docs;

import com.widyu.album.dto.request.AlbumUpdateRequest;
import com.widyu.album.dto.request.AlbumUploadRequest;
import com.widyu.album.dto.response.AlbumDetailResponse;
import com.widyu.album.dto.response.AlbumFeedResponse;
import com.widyu.album.dto.response.AlbumUnlockResponse;
import com.widyu.album.dto.response.AlbumUploadAcceptedResponse;
import com.widyu.album.dto.response.AlbumUploadResponse;
import com.widyu.album.dto.response.LikedAlbumsResponse;
import com.widyu.album.dto.response.AlbumMediaResponse;
import com.widyu.global.dto.CursorPage;
import com.widyu.global.response.ApiResponseTemplate;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Album-CRUD", description = "앨범 관리 API - 업로드, 조회, 수정, 삭제")
public interface AlbumDocs {

    // ========== 앨범 업로드 ==========
    
    @Operation(
            summary = "앨범 업로드",
            description = """
                    새로운 앨범을 업로드합니다.
                    
                    **업로드 제한사항:**
                    - 전체 미디어: 최대 8개 (사진 + 동영상 합계)
                    - 사진: 최대 8개 (각각 최대 10MB)
                    - 동영상: 최대 3개 (각각 최대 2GB, 압축 후 500MB 이하)
                    - 게시글 내용: 최대 2,200자 (공백 포함)
                    
                    **지원 파일 형식:**
                    - 사진: JPG, JPEG, PNG, GIF, WEBP, BMP, SVG
                    - 동영상: MP4, MOV, AVI, MKV, WEBM, FLV, WMV
                    
                    **자동 처리 기능:**
                    - 동영상 압축 (크기에 따라 자동 실행)
                    - 동영상 썸네일 자동 생성
                    - 동영상 길이 자동 추출
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "앨범 업로드 요청 접수",
                    content = @Content(
                            schema = @Schema(implementation = AlbumUploadAcceptedResponse.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2001",
                                              "message": "앨범 업로드 요청이 접수되었습니다.",
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
                                            name = "파일 크기 초과",
                                            value = """
                                                    {
                                                      "code": "FILE_4001",
                                                      "message": "파일 크기가 허용 범위를 초과했습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "인증 실패",
                                    value = """
                                            {
                                              "code": "AUTH_4010",
                                              "message": "인증이 필요합니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ResponseEntity<ApiResponseTemplate<AlbumUploadAcceptedResponse>> uploadAlbum(@Valid AlbumUploadRequest request);

    // ========== 앨범 피드 조회 ==========
    
    @Operation(
            summary = "앨범 피드 조회",
            description = """
                    앨범 피드 탭(ALBM1) - 최신순으로 정렬된 앨범 게시물을 무한 스크롤로 조회합니다.
                    
                    **기능 특징:**
                    - 업로드 일시 최신순으로 게시물 나열
                    - 무한 스크롤 지원 (커서 기반 페이지네이션)
                    - 각 게시물의 좋아요, 댓글, 조회수 정보 포함
                    - 열람자 정보 (최대 3명까지)
                    - 현재 사용자의 수정 가능 여부 표시
                    
                    **무한 스크롤 사용법:**
                    1. 첫 번째 요청: cursor 없이 호출
                    2. 다음 요청: 응답의 nextCursor를 cursor로 사용
                    
                    **날짜 필터링:**
                    - date 파라미터로 특정 날짜의 앨범만 조회 가능
                    - 형식: yyyy-MM-dd (예: 2024-12-21)
                    - 캘린더 탭에서 활용
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "앨범 피드 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = CursorPage.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2010",
                                              "message": "앨범 피드 조회 성공",
                                              "data": {
                                                "items": [
                                                  {
                                                    "albumId": 123,
                                                    "authorName": "홍길동",
                                                    "authorProfileImage": null,
                                                    "content": "가족 여행 사진입니다!",
                                                    "mediaUrls": ["https://s3.bucket.com/albums/photos/1/photo1.jpg"],
                                                    "thumbnailUrls": ["https://s3.bucket.com/albums/photos/1/photo1.jpg"],
                                                    "primaryMediaType": "PHOTO",
                                                    "mediaCount": 1,
                                                    "photoCount": 1,
                                                    "videoCount": 0,
                                                    "likeCount": 5,
                                                    "commentCount": 3,
                                                    "viewCount": 12,
                                                    "viewers": [
                                                      {"name": "김엄마", "profileImage": null}
                                                    ],
                                                    "createdAt": "2024-12-21T14:30:00",
                                                    "canEdit": true,
                                                    "isUnlocked": false,
                                                    "price": 50,
                                                    "videoDuration": null
                                                  }
                                                ],
                                                "hasNext": true,
                                                "nextCursor": "2024-12-21T14:30:00|123"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<CursorPage<AlbumFeedResponse>> getAlbumFeed(
            @Parameter(description = "응답의 nextCursor 값 (createdAt|albumId 형식)", example = "2024-12-21T14:30:00|123")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "캘린더용 날짜 파라미터 (yyyy-MM-dd 형식)", example = "2024-12-21")
            @RequestParam(value = "date", required = false) String date
    );

    @Operation(
            summary = "미디어 피드 조회",
            description = """
                    미디어 무한 스크롤 조회 API - 앨범의 각 미디어를 개별 아이템으로 반환합니다.
                    
                    **기능 특징:**
                    - 각 앨범의 미디어를 개별 MediaItem으로 분리
                    - postId 기반 커서 무한 스크롤
                    - 동영상 썸네일 지원
                    - 미디어 타입 구분 (image/video)
                    - 동영상 길이 정보 포함 (초 단위, 이미지는 null)
                    - price는 소속 앨범 전체의 잠금 해제 가격(50포인트)이며, 미디어별 별도 과금이 아닙니다.
                    - 페이지 크기: 10개 고정
                    
                    **무한 스크롤 사용법:**
                    1. 첫 번째 요청: cursor 없이 호출
                    2. 다음 요청: 응답의 nextCursor를 cursor로 사용
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "미디어 피드 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = CursorPage.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2011",
                                              "message": "미디어 피드 조회 성공",
                                              "data": {
                                                "items": [
                                                  {
                                                    "id": 12001,
                                                    "postId": 120,
                                                    "type": "video",
                                                    "duration": 52,
                                                    "thumbnailUrl": "https://s3.bucket.com/thumbnails/video_thumb.jpg",
                                                    "createdAt": "2024-12-21T14:30:00",
                                                    "price": 50
                                                  },
                                                  {
                                                    "id": 11901,
                                                    "postId": 119,
                                                    "type": "image",
                                                    "duration": null,
                                                    "thumbnailUrl": "https://s3.bucket.com/albums/photo.jpg",
                                                    "createdAt": "2024-12-21T14:25:00",
                                                    "price": 50
                                                  }
                                                ],
                                                "hasNext": true,
                                                "nextCursor": "2024-12-21T14:25:00|119"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<CursorPage<AlbumMediaResponse>> getMediaFeed(
            @Parameter(description = "응답의 nextCursor 값 (createdAt|albumId 형식)", example = "2024-12-21T14:25:00|119")
            @RequestParam(value = "cursor", required = false) String cursor
    );

    // ========== 앨범 수정 ==========
    
    @Operation(
            summary = "앨범 수정",
            description = """
                    앨범의 게시글 내용을 수정합니다.
                    
                    **권한:**
                    - 본인의 앨범만 수정 가능
                    
                    **수정 가능 항목:**
                    - 게시글 내용 (content)만 수정 가능
                    - 최대 2,200자 (공백 포함)
                    
                    **수정 불가 항목:**
                    - 미디어 파일은 수정 불가 (삭제 후 재업로드 필요)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "앨범 수정 성공",
                    content = @Content(
                            schema = @Schema(implementation = AlbumUploadResponse.class),
                            examples = @ExampleObject(
                                    name = "수정 성공",
                                    value = """
                                            {
                                              "code": "ALBM_2002",
                                              "message": "앨범이 수정되었습니다.",
                                              "data": {
                                                "albumId": 123,
                                                "content": "수정된 게시글 내용입니다!",
                                                "mediaUrls": [
                                                  "https://s3.bucket.com/albums/photos/1/photo1.jpg"
                                                ],
                                                "mediaCount": 1,
                                                "photoCount": 1,
                                                "videoCount": 0,
                                                "authorName": "홍길동",
                                                "createdAt": "2024-12-21T14:30:00"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (본인 앨범이 아님)",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "권한 없음",
                                    value = """
                                            {
                                              "code": "AUTH_4030",
                                              "message": "접근 권한이 없습니다. - 본인의 앨범만 수정할 수 있습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<AlbumUploadResponse> updateAlbum(
            @Parameter(description = "앨범 ID", required = true, example = "123")
            @PathVariable Long albumId,
            @Valid @RequestBody AlbumUpdateRequest request
    );

    // ========== 앨범 삭제 ==========
    
    @Operation(
            summary = "앨범 삭제",
            description = """
                    앨범을 소프트 삭제합니다.
                    
                    **권한:**
                    - 본인의 앨범만 삭제 가능
                    
                    **삭제 방식:**
                    - 소프트 삭제 (물리적 삭제 X)
                    - Status를 DELETED로 변경
                    - 피드에서 제외되지만 데이터는 보존
                    
                    **주의사항:**
                    - 삭제된 앨범은 복구 불가능 (현재)
                    - S3 파일은 삭제되지 않음 (추후 배치 처리)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "앨범 삭제 성공",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "삭제 성공",
                                    value = """
                                            {
                                              "code": "ALBM_2003",
                                              "message": "앨범이 삭제되었습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (본인 앨범이 아님)",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "권한 없음",
                                    value = """
                                            {
                                              "code": "AUTH_4030",
                                              "message": "접근 권한이 없습니다. - 본인의 앨범만 삭제할 수 있습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<Void> deleteAlbum(
            @Parameter(description = "앨범 ID", required = true, example = "123")
            @PathVariable Long albumId
    );

    // ========== 앨범 좋아요 ==========
    
    @Operation(
            summary = "앨범 좋아요 추가",
            description = """
                    앨범에 좋아요를 추가합니다.
                    
                    **기능:**
                    - 좋아요를 누르지 않은 앨범에만 좋아요 추가 가능
                    - 이미 좋아요한 앨범에는 에러 반환
                    - 자동으로 좋아요 수 증가
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "좋아요 추가 성공",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2004",
                                              "message": "앨범 좋아요가 완료되었습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "이미 좋아요한 앨범",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "이미 좋아요한 앨범",
                                    value = """
                                            {
                                              "code": "ALBUM_4001",
                                              "message": "이미 좋아요한 앨범입니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<Void> likeAlbum(
            @Parameter(description = "앨범 ID", required = true, example = "123")
            @PathVariable Long albumId
    );

    @Operation(
            summary = "앨범 좋아요 삭제",
            description = """
                    앨범에서 좋아요를 삭제합니다.
                    
                    **기능:**
                    - 좋아요를 누른 앨범에서만 좋아요 삭제 가능
                    - 좋아요를 누르지 않은 앨범에는 에러 반환
                    - 자동으로 좋아요 수 감소
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "좋아요 삭제 성공",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2005",
                                              "message": "앨범 좋아요가 취소되었습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "좋아요하지 않은 앨범",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "좋아요하지 않은 앨범",
                                    value = """
                                            {
                                              "code": "ALBUM_4002",
                                              "message": "좋아요하지 않은 앨범입니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<Void> unlikeAlbum(
            @Parameter(description = "앨범 ID", required = true, example = "123")
            @PathVariable Long albumId
    );

    @Operation(
            summary = "좋아요한 앨범 목록 조회",
            description = """
                    현재 사용자가 좋아요한 앨범의 ID 목록을 조회합니다.
                    
                    **기능:**
                    - 현재 로그인한 사용자가 좋아요한 앨범 ID들을 반환
                    - 삭제된 앨범은 제외
                    - 앨범 ID만 반환 (상세 정보는 별도 API로 조회)
                    
                    **용도:**
                    - 앨범 피드에서 좋아요 상태 표시
                    - 마이페이지 좋아요 목록
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "좋아요한 앨범 목록 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = LikedAlbumsResponse.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2006",
                                              "message": "좋아요한 앨범 목록 조회 성공",
                                              "data": {
                                                "albumIds": [123, 456, 789]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "인증 실패",
                                    value = """
                                            {
                                              "code": "AUTH_4010",
                                              "message": "인증이 필요합니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<LikedAlbumsResponse> getLikedAlbumIds();

    // ========== 앨범 상세 조회 ==========
    
    @Operation(
            summary = "앨범 상세 조회",
            description = """
                    특정 앨범의 상세 정보를 조회합니다.
                    
                    **기능:**
                    - 앨범의 모든 정보 (작성자, 미디어, 좋아요, 댓글 등)
                    - 댓글과 대댓글 포함 (최대 2단계)
                    - 현재 사용자의 수정 권한 표시 (canEdit)
                    - 댓글별 수정 권한 표시
                    
                    **권한:**
                    - 보호자: 모든 앨범 조회 가능
                    - 부모(시니어): 본인 앨범 또는 해금한 앨범 조회 가능 (해금 비용: 50포인트)

                    **포함 정보:**
                    - 게시물 기본 정보 (내용, 미디어, 통계)
                    - 작성자 정보
                    - 조회자 목록
                    - 댓글 및 대댓글 (계층 구조)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "앨범 상세 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = AlbumDetailResponse.class),
                            examples = @ExampleObject(
                                    name = "성공 응답",
                                    value = """
                                            {
                                              "code": "ALBM_2007",
                                              "message": "앨범 상세 조회 성공",
                                              "data": {
                                                "postId": 101,
                                                "content": "오늘 소풍 다녀왔어요!",
                                                "mediaUrls": [
                                                  "https://s3.aws.com/album/1.jpg"
                                                ],
                                                "likeCount": 12,
                                                "commentCount": 3,
                                                "viewCount": 45,
                                                "createdAt": "2025-09-13T11:42:36",
                                                "author": {
                                                  "memberId": 10,
                                                  "name": "홍길동",
                                                  "profileImage": null
                                                },
                                                "viewers": [
                                                  {
                                                    "memberId": 12,
                                                    "name": "김철수",
                                                    "profileImage": null
                                                  }
                                                ],
                                                "price": 50,
                                                "comments": [
                                                  {
                                                    "commentId": 201,
                                                    "content": "정말 즐거워 보이네요!",
                                                    "createdAt": "2025-09-13T12:00:00",
                                                    "author": {
                                                      "memberId": 13,
                                                      "name": "이영희",
                                                      "profileImage": null
                                                    },
                                                    "canEdit": false,
                                                    "replies": [
                                                      {
                                                        "commentId": 202,
                                                        "content": "네, 날씨도 좋았어요 ☀️",
                                                        "createdAt": "2025-09-13T12:05:00",
                                                        "author": {
                                                          "memberId": 10,
                                                          "name": "홍길동",
                                                          "profileImage": null
                                                        },
                                                        "canEdit": true
                                                      }
                                                    ]
                                                  }
                                                ],
                                                "canEdit": true
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "인증 실패",
                                    value = """
                                            {
                                              "code": "AUTH_4010",
                                              "message": "인증이 필요합니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "접근 권한 없음 (해금되지 않은 앨범)",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "해금 필요",
                                    value = """
                                            {
                                              "code": "ALBUM_4031",
                                              "message": "앨범을 보려면 해금이 필요합니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<AlbumDetailResponse> getAlbumDetail(
            @Parameter(description = "앨범 ID", required = true, example = "123")
            @PathVariable Long albumId
    );

    // ========== 앨범 해금 ==========
    
    @Operation(
            summary = "앨범 해금",
            description = """
                    포인트를 사용하여 다른 사용자의 앨범을 해금합니다.
                    
                    **기능:**
                    - 해금 가격: 50포인트 고정
                    - 본인 앨범은 해금 불가 (자동으로 접근 가능)
                    - 이미 해금된 앨범은 해금 불가
                    - 포인트 부족 시 해금 불가
                    
                    **해금 후 혜택:**
                    - 앨범 상세 조회 가능
                    - 댓글 작성 가능
                    - 좋아요 가능
                    
                    **포인트 시스템:**
                    - 해금 시 포인트 차감
                    - 앨범 작성자에게 포인트 지급 (추후 구현)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "앨범 해금 성공",
                    content = @Content(
                            schema = @Schema(implementation = AlbumUnlockResponse.class),
                            examples = @ExampleObject(
                                    name = "해금 성공",
                                    value = """
                                            {
                                              "code": "ALBM_2008",
                                              "message": "앨범 해금이 완료되었습니다.",
                                              "data": {
                                                "unlockId": 1,
                                                "albumId": 123,
                                                "albumTitle": "오늘 소풍 다녀왔어요!",
                                                "unlockedAt": "2025-09-13T12:00:00",
                                                "remainingPoints": 50,
                                                "message": "앨범이 해금되었습니다."
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            examples = {
                                    @ExampleObject(
                                            name = "본인 앨범 해금 시도",
                                            value = """
                                                    {
                                                      "code": "ALBUM_UNLOCK_4002",
                                                      "message": "본인의 앨범은 해금할 수 없습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "이미 해금된 앨범",
                                            value = """
                                                    {
                                                      "code": "ALBUM_UNLOCK_4001",
                                                      "message": "이미 해금된 앨범입니다.",
                                                      "data": null
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "포인트 부족",
                                            value = """
                                                    {
                                                      "code": "ALBUM_UNLOCK_4003",
                                                      "message": "포인트가 부족하여 해금할 수 없습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (시니어 전용 기능)",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "시니어 전용",
                                    value = """
                                            {
                                              "code": "ALBUM_UNLOCK_4030",
                                              "message": "시니어 회원만 앨범을 해금할 수 있습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "앨범을 찾을 수 없음",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "앨범 없음",
                                    value = """
                                            {
                                              "code": "ALBUM_4040",
                                              "message": "앨범을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "인증 실패",
                                    value = """
                                            {
                                              "code": "AUTH_4010",
                                              "message": "인증이 필요합니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            )
    })
    ApiResponseTemplate<AlbumUnlockResponse> unlockAlbum(
            @Parameter(description = "해금할 앨범 ID", required = true, example = "123")
            @PathVariable Long albumId
    );
}
