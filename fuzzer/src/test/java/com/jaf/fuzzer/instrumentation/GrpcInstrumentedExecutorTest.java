package com.jaf.fuzzer.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.proto.CoverageProto.CoverageEvent;
import com.jaf.proto.CoverageProto.SubscribeRequest;
import com.jaf.proto.CoverageServiceGrpc;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class GrpcInstrumentedExecutorTest {

    private static Server grpcServer;
    private static TestCoverageService coverageService;
    private static HttpServer httpServer;
    private static TestHttpHandler httpHandler;
    private static URI targetUri;
    private static ManagedChannel channel;

    @BeforeAll
    static void setUp() throws IOException {
        coverageService = new TestCoverageService();
        grpcServer = ServerBuilder.forPort(0).addService(coverageService).build().start();

        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpHandler = new TestHttpHandler();
        httpServer.createContext("/fuzz", httpHandler);
        httpServer.start();
        targetUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/fuzz");

        channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", grpcServer.getPort())
                        .usePlaintext()
                        .build();
    }

    @AfterAll
    static void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (grpcServer != null) {
            grpcServer.shutdownNow();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void runDeliversBodyAndWaitsForCoverageEvent() throws Exception {
        var bodies = new LinkedBlockingQueue<String>();
        var requestIds = new LinkedBlockingQueue<String>();
        httpHandler.setDelegate(
                exchange -> {
                    String requestId = exchange.getRequestHeaders().getFirst("X-Fuzzing-Request-Id");
                    requestIds.add(requestId);
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] responseBody = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, responseBody.length);
                    exchange.getResponseBody().write(responseBody);
                    exchange.close();
                });

        AtomicInteger counter = new AtomicInteger();
        GrpcInstrumentedExecutor executor =
                new GrpcInstrumentedExecutor(
                        channel,
                        HttpClient.newHttpClient(),
                        targetUri,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        () -> "req-" + counter.incrementAndGet());

        try {
            coverageService.awaitSubscriber(2, TimeUnit.SECONDS);
            byte[] payload = "{\"hello\":1}".getBytes(StandardCharsets.UTF_8);
            CompletableFuture<ExecutionResult> run =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return executor.run(payload);
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });

            String requestId = requestIds.poll(2, TimeUnit.SECONDS);
            assertNotNull(requestId, "request id not recorded");
            coverageService.emit(requestId, true);
            ExecutionResult result = run.get(2, TimeUnit.SECONDS);
            assertFalse(result.crashed);
            assertEquals(1, result.edges.size());
            assertEquals("ok", new String(result.stderr, StandardCharsets.UTF_8));
            assertEquals("req-1", requestId);
            assertEquals("{\"hello\":1}", bodies.poll());

            CompletableFuture<ExecutionResult> run2 =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return executor.run("{}".getBytes(StandardCharsets.UTF_8));
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });
            String requestId2 = requestIds.poll(2, TimeUnit.SECONDS);
            assertNotNull(requestId2);
            coverageService.emit(requestId2, false);
            ExecutionResult result2 = run2.get(2, TimeUnit.SECONDS);
            assertFalse(result2.crashed);
            assertTrue(result2.edges.isEmpty());
            assertEquals("req-2", requestId2);
        } finally {
            executor.close();
            httpHandler.setDelegate(null);
        }
    }

    private static final class TestCoverageService extends CoverageServiceGrpc.CoverageServiceImplBase {
        private final List<StreamObserver<CoverageEvent>> observers = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.CountDownLatch subscriptionLatch = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void subscribe(SubscribeRequest request, StreamObserver<CoverageEvent> responseObserver) {
            observers.add(responseObserver);
            subscriptionLatch.countDown();
        }

        void emit(String requestId, boolean hasNewCoverage) {
            if (observers.isEmpty()) {
                throw new IllegalStateException("no subscribers");
            }
            CoverageEvent event =
                    CoverageEvent.newBuilder()
                            .setRequestId(Objects.requireNonNullElse(requestId, ""))
                            .setHasNewCoverage(hasNewCoverage)
                            .build();
            for (StreamObserver<CoverageEvent> observer : observers) {
                observer.onNext(event);
            }
        }

        void awaitSubscriber(long timeout, TimeUnit unit) throws InterruptedException {
            subscriptionLatch.await(timeout, unit);
        }
    }

    private static final class TestHttpHandler implements HttpHandler {
        private volatile HttpHandler delegate;

        void setDelegate(HttpHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            HttpHandler handler = delegate;
            if (handler != null) {
                handler.handle(exchange);
            } else {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            }
        }
    }
}
