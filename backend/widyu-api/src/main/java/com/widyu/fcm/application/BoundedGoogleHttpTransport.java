package com.widyu.fcm.application;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Credential refresh uses a bounded HTTP exchange, including the response body. */
final class BoundedGoogleHttpTransport extends HttpTransport {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
        return new LowLevelHttpRequest() {
            private final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3));

            @Override
            public void addHeader(String name, String value) {
                builder.header(name, value);
            }

            @Override
            public LowLevelHttpResponse execute() throws IOException {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                if (getStreamingContent() != null) {
                    getStreamingContent().writeTo(body);
                }
                if (getContentType() != null) {
                    builder.header("Content-Type", getContentType());
                }
                if (getContentEncoding() != null) {
                    builder.header("Content-Encoding", getContentEncoding());
                }
                HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
                CompletableFuture<HttpResponse<byte[]>> pending = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                try {
                    return new Response(pending.get(3, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Credential refresh interrupted", exception);
                } catch (ExecutionException | TimeoutException exception) {
                    throw new IOException("Credential HTTP exchange failed", exception);
                } finally {
                    if (!pending.isDone()) {
                        pending.cancel(true);
                    }
                }
            }
        };
    }

    private static final class Response extends LowLevelHttpResponse {
        private final HttpResponse<byte[]> response;
        private final List<Map.Entry<String, String>> headers;

        private Response(HttpResponse<byte[]> response) {
            this.response = response;
            this.headers = response.headers().map().entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream().map(value -> Map.entry(entry.getKey(), value))).toList();
        }

        @Override public InputStream getContent() { return new ByteArrayInputStream(response.body()); }
        @Override public String getContentEncoding() { return response.headers().firstValue("Content-Encoding").orElse(null); }
        @Override public long getContentLength() { return response.body().length; }
        @Override public String getContentType() { return response.headers().firstValue("Content-Type").orElse(null); }
        @Override public String getStatusLine() { return Integer.toString(response.statusCode()); }
        @Override public int getStatusCode() { return response.statusCode(); }
        @Override public String getReasonPhrase() { return ""; }
        @Override public int getHeaderCount() { return headers.size(); }
        @Override public String getHeaderName(int index) { return headers.get(index).getKey(); }
        @Override public String getHeaderValue(int index) { return headers.get(index).getValue(); }
    }
}
