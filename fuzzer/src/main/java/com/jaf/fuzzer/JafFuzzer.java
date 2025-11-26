package com.jaf.fuzzer;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class JafFuzzer {

    private static final String SOCKET_PATH = "/tmp/jaf-coverage.sock";
    private static final int DEFAULT_DURATION_SECONDS = 10;

    private JafFuzzer() {
        // Utility class
    }

    public static void main(String[] args) throws InterruptedException {
        int durationSeconds = parseDuration(args);
        if (!Epoll.isAvailable()) {
            System.err.println("Epoll is not available; unable to connect to coverage socket.");
            return;
        }

        EpollEventLoopGroup group = new EpollEventLoopGroup();
        ManagedChannel channel =
                NettyChannelBuilder.forAddress(new DomainSocketAddress(SOCKET_PATH))
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
                                System.out.println(
                                        "[JAF] Request finished: id="
                                                + value.getRequestId()
                                                + ", newCoverage="
                                                + value.getHasNewCoverage());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("Coverage stream error: " + t.getMessage());
                                latch.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                latch.countDown();
                            }
                        });

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    channel.shutdownNow();
                                    try {
                                        channel.awaitTermination(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    group.shutdownGracefully().syncUninterruptibly();
                                }));

        if (durationSeconds <= 0) {
            latch.await();
        } else {
            latch.await(durationSeconds, TimeUnit.SECONDS);
        }

        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        group.shutdownGracefully().syncUninterruptibly();
        System.out.println("JAF fuzzer exiting.");
    }

    private static int parseDuration(String[] args) {
        if (args == null) {
            return DEFAULT_DURATION_SECONDS;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--duration=")) {
                String value = arg.substring("--duration=".length());
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    System.err.println("Invalid duration value: " + value);
                }
            }
        }
        return DEFAULT_DURATION_SECONDS;
    }
}
