package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.global.config.AuthLimitWebConfig;
import com.widyu.global.properties.AuthProxyProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.List;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.web.filter.ForwardedHeaderFilter;

class AuthProxyServerTest {
    @ParameterizedTest
    @ValueSource(strings = {"none", "native", "framework"})
    @DisplayName("실제 Tomcat forwarding을 사용해도 원래 peer와 헤더를 보존한다")
    void 실제_서버가_forwarding_이전_주소를_보존한다(String strategy) throws Exception {
        // given
        var factory = new TomcatServletWebServerFactory(0);
        if (strategy.equals("native")) {
            factory.addEngineValves(new RemoteIpValve());
        }
        new AuthLimitWebConfig().customize(factory);
        var server = factory.getWebServer(context -> {
            if (strategy.equals("framework")) {
                context.addFilter("forwarding", new ForwardedHeaderFilter())
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            }
            context.addServlet("ip", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    String untrusted = new ClientIpResolver(request, new AuthProxyProperties(null)).resolve();
                    String trusted = new ClientIpResolver(request,
                            new AuthProxyProperties(List.of("127.0.0.1/32"))).resolve();
                    response.getWriter().write(untrusted + "|" + trusted + "|" + request.getRemoteAddr());
                }
            }).addMapping("/*");
        });
        try {
            server.start();
            // when
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/"))
                    .header("X-Forwarded-For", "192.0.2.99, 198.51.100.1").build(),
                    HttpResponse.BodyHandlers.ofString());
            // then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).startsWith("127.0.0.1|198.51.100.1|");
            if (!strategy.equals("none")) {
                assertThat(response.body()).doesNotEndWith("|127.0.0.1");
            }
        } finally {
            server.stop();
            server.destroy();
        }
    }
}
