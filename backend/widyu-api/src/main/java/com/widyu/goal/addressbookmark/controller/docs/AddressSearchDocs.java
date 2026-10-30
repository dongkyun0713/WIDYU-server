package com.widyu.goal.addressbookmark.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.goal.addressbookmark.dto.response.AddressSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Address Search", description = "주소 검색 API (도로명주소 공공 API + Kakao 좌표 변환)")
public interface AddressSearchDocs {

    @Operation(
            summary = "주소 검색",
            description = "키워드로 도로명주소를 검색합니다. 각 결과에 Kakao Local API로 변환한 위도·경도가 포함됩니다. 좌표 변환에 실패한 항목은 latitude/longitude가 null로 반환됩니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "주소 검색 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(value = """
                            {
                                "code": "200",
                                "message": "주소 검색 성공",
                                "data": {
                                    "addresses": [
                                        {
                                            "roadAddr": "서울특별시 마포구 성암로 301",
                                            "jibunAddr": "서울특별시 마포구 상암동 1595",
                                            "zipNo": "03921",
                                            "bdNm": "MBC",
                                            "siNm": "서울특별시",
                                            "sggNm": "마포구",
                                            "emdNm": "상암동",
                                            "latitude": 37.5666103,
                                            "longitude": 126.9783882
                                        }
                                    ],
                                    "totalCount": 1,
                                    "currentPage": 1,
                                    "countPerPage": 10
                                }
                            }
                            """)
            )
    )
    @ApiResponse(
            responseCode = "502",
            description = "도로명주소 API 호출 실패(타임아웃, 연결 실패, 5xx 응답)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(value = """
                            {
                                "code": "JUSO_5000",
                                "message": "주소 검색 서비스와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요."
                            }
                            """)
            )
    )
    ApiResponseTemplate<AddressSearchResponse> searchAddress(
            @Parameter(description = "검색 키워드", required = true, example = "성암로 301") String keyword,
            @Parameter(description = "페이지 번호 (1부터 시작)", example = "1") int page,
            @Parameter(description = "페이지당 결과 수 (최대 100)", example = "10") int size
    );
}
