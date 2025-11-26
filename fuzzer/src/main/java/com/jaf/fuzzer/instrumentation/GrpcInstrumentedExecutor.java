package com.jaf.fuzzer.instrumentation;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.proto.CoverageProto.CoverageEvent;
import com.jaf.proto.CoverageProto.SubscribeRequest;
import com.jaf.proto.CoverageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Instrumented executor that proxies fuzz inputs to the HTTP SUT while listening to coverage events
 * from the agent over gRPC. Each request is tagged with a unique {@code X-Fuzzing-Request-Id}
 * header so the agent can attribute coverage.
 */
public final class GrpcInstrumentedExecutor implements InstrumentedExecutor, AutoCloseable {

    private static final String HEADER_NAME = "X-Fuzzing-Request-Id";

    private final ManagedChannel channel;
    private final CoverageServiceGrpc.CoverageServiceStub stub;
    private final HttpClient httpClient;
    private final URI targetUri;
    private final Duration requestTimeout;
    private final Duration coverageTimeout;
    private final Supplier<String> requestIdSupplier;
    private final AtomicInteger edgeCounter;
    private final EventLoopGroup eventLoopGroup;

    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    private volatile boolean shutdown;

    private GrpcInstrumentedExecutor(
            ManagedChannel channel,
            CoverageServiceGrpc.CoverageServiceStub stub,
            HttpClient httpClient,
            URI targetUri,
            Duration requestTimeout,
            Duration coverageTimeout,
            Supplier<String> requestIdSupplier,
            AtomicInteger edgeCounter,
            EventLoopGroup eventLoopGroup) {
        this.channel = channel;
        this.stub = stub != null ? stub : CoverageServiceGrpc.newStub(channel);
        this.httpClient = httpClient;
        this.targetUri = targetUri;
        this.requestTimeout = requestTimeout;
        this.coverageTimeout = coverageTimeout;
        this.requestIdSupplier = requestIdSupplier;
        this.edgeCounter = edgeCounter;
        this.eventLoopGroup = eventLoopGroup;
        startSubscription();
    }

    public static GrpcInstrumentedExecutor forUnixDomainSocket(
            String socketPath, URI targetUri, Duration requestTimeout, Duration coverageTimeout)
            throws IOException {
        if (!Epoll.isAvailable()) {
            throw new IOException("epoll is required for Unix domain sockets", Epoll.unavailabilityCause());
        }
        EventLoopGroup group = new EpollEventLoopGroup();
        ManagedChannel channel =
                NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath))
                        .eventLoopGroup(group)
                        .channelType(EpollDomainSocketChannel.class)
                        .usePlaintext()
                        .build();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(requestTimeout)
                        .build();
        return new GrpcInstrumentedExecutor(
                channel,
                CoverageServiceGrpc.newStub(channel),
                client,
                targetUri,
                requestTimeout,
                coverageTimeout,
                () -> UUID.randomUUID().toString(),
                new AtomicInteger(),
                group);
    }

    /** Visible for testing. */
    GrpcInstrumentedExecutor(
            ManagedChannel channel,
            HttpClient httpClient,
            URI targetUri,
            Duration requestTimeout,
            Duration coverageTimeout,
            Supplier<String> requestIdSupplier) {
        this(
                channel,
                CoverageServiceGrpc.newStub(channel),
                httpClient,
                targetUri,
                requestTimeout,
                coverageTimeout,
                requestIdSupplier,
                new AtomicInteger(),
                null);
    }

    @Override
    public ExecutionResult run(byte[] input) throws Exception {
        Objects.requireNonNull(input, "input");
        if (shutdown) {
            throw new IllegalStateException("Executor has been shut down");
        }
        String requestId = requestIdSupplier.get();
        CompletableFuture<Boolean> coverageFuture = new CompletableFuture<>();
        pending.put(requestId, coverageFuture);

        String requestBody = new String(input, StandardCharsets.UTF_8);
        System.out.println("[Fuzzer] Request " + requestId + " payload: " + requestBody);

        HttpRequest request =
                HttpRequest.newBuilder(targetUri)
                        .timeout(requestTimeout)
                        .header(HEADER_NAME, requestId)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(input))
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            pending.remove(requestId);
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException | RuntimeException e) {
            pending.remove(requestId);
            coverageFuture.completeExceptionally(e);
            throw e;
        }

        boolean newCoverage = awaitCoverage(requestId, coverageFuture);
        boolean crashed = response.statusCode() >= 500;
        byte[] stderr = response.body() != null ? response.body() : new byte[0];
        Set<Integer> edges =
                newCoverage
                        ? Collections.singleton(edgeCounter.incrementAndGet())
                        : Collections.emptySet();
        return new ExecutionResult(crashed, edges, stderr);
    }

    @Override
    public void close() {
        shutdown = true;
        for (CompletableFuture<Boolean> future : pending.values()) {
            future.complete(false);
        }
        pending.clear();
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    private boolean awaitCoverage(String requestId, CompletableFuture<Boolean> future)
            throws Exception {
        try {
            return future.get(coverageTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId, future);
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        }
    }

    private void startSubscription() {
        StreamObserver<CoverageEvent> observer =
                new StreamObserver<>() {
                    @Override
                    public void onNext(CoverageEvent value) {
                        String requestId = value.getRequestId();
                        if (requestId == null || requestId.isBlank()) {
                            return;
                        }
                        CompletableFuture<Boolean> future = pending.remove(requestId);
                        if (future != null) {
                            future.complete(value.getHasNewCoverage());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        for (Map.Entry<String, CompletableFuture<Boolean>> entry : pending.entrySet()) {
                            entry.getValue().completeExceptionally(t);
                        }
                        pending.clear();
                        if (!shutdown) {
                            restartSubscription();
                        }
                    }

                    @Override
                    public void onCompleted() {
                        if (!shutdown) {
                            restartSubscription();
                        }
                    }
                };
        try {
            stub.withWaitForReady().subscribe(SubscribeRequest.getDefaultInstance(), observer);
        } catch (StatusRuntimeException e) {
            if (!shutdown) {
                restartSubscription();
            }
        }
    }

    private void restartSubscription() {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!shutdown) {
            startSubscription();
        }
    }
}
