package com.jaf.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jaf.proto.CoverageProto.CoverageEvent;
import com.jaf.proto.CoverageProto.SubscribeRequest;
import com.jaf.proto.CoverageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoverageServerTest {

    private CoverageServer server;
    private Path socketPath;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Epoll.isAvailable(), "Epoll required for UDS gRPC tests");
        CoverageRuntime.reset();
        socketPath = Files.createTempFile("jaf-coverage", ".sock");
        Files.deleteIfExists(socketPath);
        server = new CoverageServer(socketPath);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (socketPath != null) {
            try {
                Files.deleteIfExists(socketPath);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void publishesEventsToSubscribers() throws Exception {
        EpollEventLoopGroup group = new EpollEventLoopGroup();
        ManagedChannel channel =
                NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath.toString()))
                        .channelType(EpollDomainSocketChannel.class)
                        .eventLoopGroup(group)
                        .usePlaintext()
                        .build();

        CountDownLatch latch = new CountDownLatch(1);

        CoverageServiceGrpc.newStub(channel)
                .subscribe(
                        SubscribeRequest.getDefaultInstance(),
                        new StreamObserver<CoverageEvent>() {
                            @Override
                            public void onNext(CoverageEvent value) {
                                latch.countDown();
                            }

                            @Override
                            public void onError(Throwable t) {
                                latch.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                // no-op
                            }
                        });

        Thread.sleep(100); // allow subscription to propagate
        assertTrue(server.awaitFirstClient(5, TimeUnit.SECONDS));
        CoverageRuntime.enterEdge(1234);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Did not receive coverage event over gRPC");

        channel.shutdownNow();
        channel.awaitTermination(3, TimeUnit.SECONDS);
        group.shutdownGracefully().syncUninterruptibly();
    }
}
