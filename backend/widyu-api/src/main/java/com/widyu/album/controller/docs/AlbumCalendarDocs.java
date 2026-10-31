package com.widyu.album.controller.docs;

import com.widyu.album.dto.FamilyAlbumPageResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Album-Calendar", description = "앨범 캘린더 조회 API")
public interface AlbumCalendarDocs {

    @Operation(
            summary = "앨범 캘린더 날짜 조회",
            description = """
                    특정 연도(year)와 월(month)에 현재 가족이 작성한 앨범이 존재하는 일(day) 목록을 조회합니다.
                    반환값은 앨범이 존재하는 날짜들의 정수 배열입니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "성공적으로 조회됨",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "200",
                                      "message": "OK",
                                      "data": [2, 5, 7, 15, 23]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<Integer>> getDaysWithEvents(
            @Parameter(
                    name = "year",
                    description = "조회할 연도",
                    in = ParameterIn.QUERY,
                    required = true,
                    example = "2025"
            )
            @Min(value = 2000, message = "year는 2000 이상 2100 이하여야 합니다.")
            @Max(value = 2100, message = "year는 2000 이상 2100 이하여야 합니다.")
            int year,

            @Parameter(
                    name = "month",
                    description = "조회할 월",
                    in = ParameterIn.QUERY,
                    required = true,
                    example = "9"
            )
            @Min(value = 1, message = "month는 1 이상 12 이하여야 합니다.")
            @Max(value = 12, message = "month는 1 이상 12 이하여야 합니다.")
            int month
    );

    @Operation(
            summary = "가족 앨범 피드 조회 (커서 기반)",
            description = """
                    특정 날짜(date)에 가족(본인, 보호자, 자녀)의 앨범 피드를 커서 기반으로 조회합니다.
                    - 기본 페이지 크기는 5개이며, 추가 데이터가 있으면 `hasNext = true` 와 `nextCursor` 가 반환됩니다.
                    - `cursor` 파라미터는 마지막 앨범 ID를 의미합니다.
                    - 시니어가 보호자 앨범을 아직 해금하지 않았으면 `isUnlocked: false`이고, 원본 `mediaUrls`는 빈 배열이며 `thumbnailUrls`만 담깁니다.
                    - 사진은 썸네일이 원본과 같은 URL이라 잠금 전에도 사진이 보일 수 있습니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "성공적으로 조회됨",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "200",
                                      "message": "OK",
                                      "data": {
                                        "albums": [
                                          {
                                            "albumId": 101,
                                            "content": "오늘 소풍 다녀왔어요!",
                                            "mediaUrls": ["https://s3.aws.com/album/1.jpg"],
                                            "thumbnailUrls": ["https://s3.aws.com/album/1.jpg"],
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
                                            "isUnlocked": true
                                          }
                                        ],
                                        "hasNext": true,
                                        "nextCursor": 100
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<FamilyAlbumPageResponse> getFamilyAlbumsByDate(
            @Parameter(
                    name = "date",
                    description = "조회할 날짜 (yyyy-MM-dd)",
                    in = ParameterIn.QUERY,
                    required = true,
                    example = "2025-09-13"
            )
            LocalDate date,

            @Parameter(
                    name = "cursor",
                    description = "마지막으로 조회된 앨범 ID (페이징 커서)",
                    in = ParameterIn.QUERY,
                    required = false,
                    example = "101"
            )
            Long cursor
    );
}
