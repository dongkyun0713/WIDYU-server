package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.widyu.global.error.BusinessException;
import com.widyu.global.properties.AuthProxyProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "10.0.0.2|198.51.100.1|198.51.100.1",
            "10.0.0.2|192.0.2.99, 198.51.100.1, 10.1.0.3|198.51.100.1",
            "192.0.2.1|198.51.100.1|192.0.2.1",
            "10.0.0.2|garbage, 198.51.100.1|198.51.100.1",
            "10.0.0.2|198.51.100.1, garbage|10.0.0.2",
            "10.0.0.2|10.1.0.3|10.0.0.2",
            "2001:db8:1::2|2001:db8:2::1, 2001:db8:1::3|2001:db8:2:0:0:0:0:1",
            "10.0.0.2|::ffff:192.0.2.1|192.0.2.1"
    })
    @DisplayName("신뢰 peer의 체인을 오른쪽부터 검사하면 첫 비신뢰 IP를 반환한다")
    void 신뢰_체인을_오른쪽부터_제거한다(String peer, String header, String expected) {
        // given
        var request = request(peer, List.of(header));
        // when / then
        assertThat(new ClientIpResolver(request,
                new AuthProxyProperties(List.of("10.0.0.0/8", "2001:db8:1::/48"))).resolve()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.1", "010.0.0.1", "999.0.0.1", "[::1]", "fe80::1%eth0", "1.2.3.4:80", "unknown", ",", ""})
    @DisplayName("신뢰 헤더의 마지막 IP가 유효하지 않으면 직접 peer를 사용한다")
    void 잘못된_IP는_peer로_돌아간다(String header) {
        // given / when / then
        assertThat(new ClientIpResolver(request("10.0.0.2", List.of(header)),
                new AuthProxyProperties(List.of("10.0.0.0/8"))).resolve()).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("헤더가 여러 줄이면 수신 순서를 유지하고 servlet 재작성 주소를 무시한다")
    void 여러_헤더와_재작성된_주소를_처리한다() {
        // given
        var request = request("10.0.0.2", List.of("192.0.2.99", "198.51.100.1, 10.1.0.3"));
        request.setRemoteAddr("192.0.2.99");
        // when / then
        assertThat(new ClientIpResolver(request, new AuthProxyProperties(List.of("10.0.0.0/8"))).resolve())
                .isEqualTo("198.51.100.1");
        assertThat(new ClientIpResolver(request, new AuthProxyProperties(null)).resolve()).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("원래 peer 보존 계약이 없으면 인증 제한 요청을 차단한다")
    void 원래_peer_없이는_503을_반환한다() {
        // given / when / then
        assertThatThrownBy(() -> new ClientIpResolver(new MockHttpServletRequest(),
                new AuthProxyProperties(null)).resolve()).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/8", "10.0.0.0/33", "::/129", "10.0.0.0", "10.0.0.0/-1"})
    @DisplayName("신뢰 CIDR이 잘못되면 설정 오류를 반환한다")
    void 잘못된_CIDR을_거부한다(String cidr) {
        // given / when / then
        assertThatThrownBy(() -> new ClientIpResolver(new MockHttpServletRequest(),
                new AuthProxyProperties(List.of(cidr)))).isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest request(String peer, List<String> headers) {
        var request = new MockHttpServletRequest();
        request.setAttribute(ClientIpResolver.ORIGINAL_REQUEST, new ClientIpResolver.OriginalRequest(peer, headers));
        return request;
    }
}
