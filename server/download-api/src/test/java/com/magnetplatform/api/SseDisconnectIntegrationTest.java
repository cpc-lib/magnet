package com.magnetplatform.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("sse-disconnect-test")
@SpringBootTest(classes = SseDisconnectIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseDisconnectIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    StreamingController controller;

    @Test
    void tcpResetStopsSenderWithoutSecondaryConversionError(CapturedOutput output) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /disconnect-test HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            BufferedReader response = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(response.readLine()).startsWith("HTTP/1.1 200");
            String line;
            do {
                line = response.readLine();
                assertThat(line).isNotNull();
            } while (!line.contains("ready"));
            socket.setSoLinger(true, 0); // RST while the server still has events to write.
        }
        controller.clientDisconnected.countDown();
        assertThat(controller.writeFailed.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.senderStopped.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(output.getAll()).doesNotContain("Failure in @ExceptionHandler", "No converter for",
                "Servlet.service() for servlet");
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("sse-disconnect-test")
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, SecurityAutoConfiguration.class})
    @Import({GlobalExceptionHandler.class, StreamingController.class})
    static class TestApp {
        @Bean(destroyMethod = "shutdownNow")
        ExecutorService streamExecutor() {
            return Executors.newSingleThreadExecutor();
        }
    }

    @RestController
    @Profile("sse-disconnect-test")
    static class StreamingController {
        final CountDownLatch clientDisconnected = new CountDownLatch(1);
        final CountDownLatch writeFailed = new CountDownLatch(1);
        final CountDownLatch senderStopped = new CountDownLatch(1);
        private final ExecutorService executor;

        StreamingController(ExecutorService executor) {
            this.executor = executor;
        }

        @GetMapping(path = "/disconnect-test", produces = "text/event-stream")
        SseEmitter stream() throws IOException {
            SseEmitter emitter = new SseEmitter(10000L);
            AtomicBoolean closed = new AtomicBoolean();
            emitter.onCompletion(() -> closed.set(true));
            emitter.onError(error -> closed.set(true));
            emitter.send(SseEmitter.event().data("ready"));
            executor.submit(() -> {
                try {
                    if (!clientDisconnected.await(5, TimeUnit.SECONDS)) return;
                    while (!closed.get()) {
                        emitter.send(SseEmitter.event().data("x".repeat(8192)));
                        Thread.sleep(20);
                    }
                } catch (IOException error) {
                    writeFailed.countDown(); // Servlet container owns the error dispatch.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    senderStopped.countDown();
                }
            });
            return emitter;
        }
    }
}
