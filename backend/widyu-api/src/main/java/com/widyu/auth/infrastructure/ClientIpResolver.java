package com.widyu.auth.infrastructure;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import com.widyu.global.properties.AuthProxyProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {
    public static final String ORIGINAL_REQUEST = ClientIpResolver.class.getName() + ".original";
    private final HttpServletRequest request;
    private final List<Cidr> trusted;

    public ClientIpResolver(HttpServletRequest request, AuthProxyProperties properties) {
        this.request = request;
        this.trusted = properties.trustedCidrs().stream().map(Cidr::parse).toList();
    }

    public String resolve() {
        // A rewritten servlet remoteAddr is not a safe fallback for an absent server contract.
        if (!(request.getAttribute(ORIGINAL_REQUEST) instanceof OriginalRequest original)) {
            throw new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        }
        InetAddress peer = literal(original.peer());
        if (peer == null) {
            throw new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        }
        if (!isTrusted(peer)) {
            return peer.getHostAddress();
        }
        String header = String.join(",", original.forwardedFor());
        if (header.isBlank() || header.length() > 8192) {
            return peer.getHostAddress();
        }
        String[] chain = header.split(",", -1);
        for (int i = chain.length - 1; i >= 0; i--) {
            InetAddress hop = literal(chain[i].trim());
            if (hop == null) {
                return peer.getHostAddress();
            }
            if (!isTrusted(hop)) {
                return hop.getHostAddress();
            }
        }
        return peer.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(cidr -> cidr.contains(address.getAddress()));
    }

    private static InetAddress literal(String value) {
        if (value == null || value.isEmpty() || !value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value); // Numeric literals only; no DNS lookup.
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    public record OriginalRequest(String peer, List<String> forwardedFor) {
        public OriginalRequest {
            forwardedFor = List.copyOf(forwardedFor);
        }
    }

    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress address = literal(parts[0]);
            if (parts.length != 2 || address == null || !parts[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix > address.getAddress().length * 8) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR prefix");
            }
            return new Cidr(address.getAddress(), prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
