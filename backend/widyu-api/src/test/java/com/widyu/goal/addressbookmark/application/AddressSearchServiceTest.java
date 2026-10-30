package com.widyu.goal.addressbookmark.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.goal.addressbookmark.client.JusoApiClient;
import feign.Request;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("AddressSearchService 단위 테스트")
class AddressSearchServiceTest {

    private static final String CONFM_KEY = "test-juso-key";
    private static final String KEYWORD = "성암로 301";

    @Mock private JusoApiClient jusoApiClient;
    @Mock private GeocodingService geocodingService;

    @InjectMocks
    private AddressSearchService addressSearchService;

    @Test
    @DisplayName("주소 API 호출이 실패하면 승인키와 검색어를 남기지 않고 주소 검색 통신 오류로 바꾼다")
    void 주소_API_호출이_실패하면_승인키와_검색어_없이_통신_오류로_바꾼다(CapturedOutput output) {
        // given: Feign 예외 메시지는 승인키와 검색어가 담긴 요청 URL을 포함한다
        ReflectionTestUtils.setField(addressSearchService, "confmKey", CONFM_KEY);
        String url = "https://business.juso.go.kr/addrlink/addrLinkApi.do?confmKey=" + CONFM_KEY + "&keyword=" + KEYWORD;
        Request request = Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
        given(jusoApiClient.searchAddress(CONFM_KEY, KEYWORD, 1, 10, "json"))
                .willThrow(new RetryableException(-1, "Read timed out executing GET " + url,
                        Request.HttpMethod.GET, (Long) null, request));

        // when
        Throwable thrown = catchThrowable(() -> addressSearchService.search(KEYWORD, 1, 10));

        // then: 전역 오류 로그는 이 예외의 메시지와 원인 체인을 남기므로 둘 다 요청 URL을 담지 않아야 한다
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ErrorCode.JUSO_COMMUNICATION_ERROR);
        assertThat(thrown.getMessage()).doesNotContain(CONFM_KEY, KEYWORD);
        assertThat(thrown.getCause()).isNull();
        assertThat(output).contains("errorType=RetryableException");
        assertThat(output).doesNotContain(CONFM_KEY, KEYWORD);
    }
}
