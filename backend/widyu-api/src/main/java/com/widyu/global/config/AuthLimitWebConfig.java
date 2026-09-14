package com.widyu.global.config;

import com.widyu.auth.infrastructure.ClientIpResolver;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AuthLimitWebConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // Preserve socket peer and headers before native/framework forwarding changes them.
        var valves = new ArrayList<Valve>();
        valves.add(new OriginalPeerValve());
        valves.addAll(factory.getEngineValves());
        factory.setEngineValves(valves);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    public static final class OriginalPeerValve extends ValveBase {
        public OriginalPeerValve() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            request.setAttribute(ClientIpResolver.ORIGINAL_REQUEST, new ClientIpResolver.OriginalRequest(
                    request.getRemoteAddr(), Collections.list(request.getHeaders("X-Forwarded-For"))));
            getNext().invoke(request, response);
        }
    }
}
