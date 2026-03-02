package com.skylo.iot.api_server.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class GrpcServerLifecycle {

    private final int grpcPort;
    private final BillingApiGrpcService billingApiGrpcService;
    private final GrpcAuthInterceptor grpcAuthInterceptor;

    private Server server;

    public GrpcServerLifecycle(@Value("${grpc.server.port}") int grpcPort,
                               BillingApiGrpcService billingApiGrpcService,
                               GrpcAuthInterceptor grpcAuthInterceptor) {
        this.grpcPort = grpcPort;
        this.billingApiGrpcService = billingApiGrpcService;
        this.grpcAuthInterceptor = grpcAuthInterceptor;
    }

    @jakarta.annotation.PostConstruct
    public void start() throws IOException {
        this.server = ServerBuilder
                .forPort(grpcPort)
                .addService(ServerInterceptors.intercept(billingApiGrpcService, grpcAuthInterceptor))
                .build()
                .start();

        log.info("grpcServerLifecycle action=started port={}", grpcPort);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("grpcServerLifecycle action=stopped port={}", grpcPort);
        }
    }
}
