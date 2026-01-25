package com.jaf.agent;

import com.google.protobuf.ByteString;
import com.jaf.proto.CoverageProto.CoverageEvent;
import com.jaf.proto.CoverageProto.SubscribeRequest;
import com.jaf.proto.CoverageServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CoverageServer {
    private final Path socketPath;
    private final Set<StreamObserver<CoverageEvent>> observers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean clientConnected = new AtomicBoolean(false);
    private final CountDownLatch firstClientLatch = new CountDownLatch(1);

    private Server server;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    CoverageServer(Path socketPath) {
        this.socketPath = socketPath;
    }

    void start() throws IOException {
        if (!Epoll.isAvailable()) {
            throw new IOException("Epoll is required for unix domain sockets", Epoll.unavailabilityCause());
        }
        Files.deleteIfExists(socketPath);

        bossGroup = new EpollEventLoopGroup(1);
        workerGroup = new EpollEventLoopGroup();

        server =
                NettyServerBuilder.forAddress(new DomainSocketAddress(socketPath.toString()))
                        .bossEventLoopGroup(bossGroup)
                        .workerEventLoopGroup(workerGroup)
                        .channelType(EpollServerDomainSocketChannel.class)
                        .addService(new CoverageServiceImpl())
                        .build()
                        .start();
        FuzzingRequestContext.registerRequestFinishedListener(this::handleRequestFinished);
    }

    void stop() {
        FuzzingRequestContext.registerRequestFinishedListener(null);
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // best effort cleanup
        }
        firstClientLatch.countDown();
        observers.clear();
    }

    private final class CoverageServiceImpl extends CoverageServiceGrpc.CoverageServiceImplBase {
        @Override
        public void subscribe(
                SubscribeRequest request, StreamObserver<CoverageEvent> responseObserver) {
            observers.add(responseObserver);
            if (clientConnected.compareAndSet(false, true)) {
                firstClientLatch.countDown();
            }
            if (responseObserver instanceof ServerCallStreamObserver<CoverageEvent> serverObserver) {
                serverObserver.setOnCancelHandler(() -> observers.remove(responseObserver));
                serverObserver.setOnCloseHandler(() -> observers.remove(responseObserver));
            }
        }
    }

    Path getSocketPath() {
        return socketPath;
    }

    boolean hasClientConnected() {
        return clientConnected.get();
    }

    void awaitFirstClient() throws InterruptedException {
        if (clientConnected.get()) {
            return;
        }
        firstClientLatch.await();
    }

    boolean awaitFirstClient(long timeout, TimeUnit unit) throws InterruptedException {
        if (clientConnected.get()) {
            return true;
        }
        return firstClientLatch.await(timeout, unit);
    }

    private void handleRequestFinished(String requestId, byte[] traceBitmap) {
        byte[] payload = traceBitmap == null ? new byte[0] : traceBitmap;
        CoverageEvent event =
                CoverageEvent.newBuilder()
                        .setRequestId(requestId != null ? requestId : "")
                        .setTraceBitmap(ByteString.copyFrom(payload))
                        .build();
        for (StreamObserver<CoverageEvent> observer : observers) {
            observer.onNext(event);
        }
    }
}
