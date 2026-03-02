package com.skylo.iot.api_server.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GrpcAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String expectedToken;

    public GrpcAuthInterceptor(@Value("${api.auth.token}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        String authorization = headers.get(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            log.warn("grpcAuthResult method={} outcome=denied reason=missing_or_invalid_bearer", method);
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid Bearer token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        String token = authorization.substring("Bearer ".length()).trim();
        if (!expectedToken.equals(token)) {
            log.warn("grpcAuthResult method={} outcome=denied reason=token_mismatch", method);
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        log.info("grpcAuthResult method={} outcome=allowed", method);

        return next.startCall(call, headers);
    }
}
