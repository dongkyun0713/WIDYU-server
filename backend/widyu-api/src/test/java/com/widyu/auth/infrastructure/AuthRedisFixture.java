package com.widyu.auth.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Every test owns a loopback-only, nonpersistent Redis process. Never uses application Redis settings. */
final class AuthRedisFixture implements AutoCloseable {
    private final Process process;
    private final LettuceConnectionFactory factory;
    final StringRedisTemplate redis;

    AuthRedisFixture() throws Exception {
        String password = UUID.randomUUID().toString();
        RunningRedis running = startRedis(password);
        process = running.process();
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", running.port());
        configuration.setPassword(password);
        factory = new LettuceConnectionFactory(configuration,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500))
                        .shutdownTimeout(Duration.ZERO).build());
        try {
            factory.afterPropertiesSet();
            factory.start();
            redis = new StringRedisTemplate(factory);
            try (var connection = factory.getConnection()) {
                String processId = connection.serverCommands().info("server").getProperty("process_id");
                if (!Long.toString(process.pid()).equals(processId)) {
                    throw new IOException("Isolated Redis process identity mismatch");
                }
                connection.ping();
            }
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    private static RunningRedis startRedis(String password) throws Exception {
        // Reserve on the same IPv4 interface as Redis. Retry only startup port races.
        for (int attempt = 0; attempt < 3; attempt++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            Process candidate = new ProcessBuilder("redis-server", "--bind", "127.0.0.1",
                    "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                    "--requirepass", password)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (candidate.isAlive() && System.nanoTime() < deadline) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                        if (candidate.isAlive()) {
                            return new RunningRedis(candidate, port);
                        }
                    } catch (IOException exception) {
                        // Wait for the process socket before creating Lettuce and its reconnect state.
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException exception) {
                candidate.destroyForcibly();
                throw exception;
            }
            candidate.destroyForcibly();
            candidate.waitFor(3, TimeUnit.SECONDS);
        }
        throw new IOException("Isolated test Redis could not bind an IPv4 loopback port");
    }

    void stopRedis() throws Exception {
        process.destroy();
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            factory.destroy();
        } finally {
            stopRedis();
        }
    }

    private record RunningRedis(Process process, int port) {
    }
}
