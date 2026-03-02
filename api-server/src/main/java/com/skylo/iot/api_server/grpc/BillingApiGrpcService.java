package com.skylo.iot.api_server.grpc;

import com.google.protobuf.Timestamp;
import com.skylo.iot.api_server.grpc.generated.BillingApiGrpc;
import com.skylo.iot.api_server.grpc.generated.BillingHistoryRequest;
import com.skylo.iot.api_server.grpc.generated.BillingHistoryResponse;
import com.skylo.iot.api_server.grpc.generated.BillingRecord;
import com.skylo.iot.api_server.grpc.generated.LiveDeviceStatusRequest;
import com.skylo.iot.api_server.grpc.generated.LiveDeviceStatusResponse;
import com.skylo.iot.api_server.persistence.BillingSessionEntity;
import com.skylo.iot.api_server.repository.BillingSessionRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class BillingApiGrpcService extends BillingApiGrpc.BillingApiImplBase {

    private static final int MAX_DEVICE_ID_LENGTH = 50;

    private final StringRedisTemplate stringRedisTemplate;
    private final BillingSessionRepository billingSessionRepository;

    public BillingApiGrpcService(StringRedisTemplate stringRedisTemplate,
                                 BillingSessionRepository billingSessionRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.billingSessionRepository = billingSessionRepository;
    }

    @Override
    public void getLiveDeviceStatus(LiveDeviceStatusRequest request,
                                    StreamObserver<LiveDeviceStatusResponse> responseObserver) {
        String deviceId = validateDeviceId(request.getDeviceId());
        String sessionKey = "session:" + deviceId;
        log.info("grpcRequest method=GetLiveDeviceStatus deviceId={}", deviceId);

        Map<Object, Object> session = stringRedisTemplate.opsForHash().entries(sessionKey);

        LiveDeviceStatusResponse.Builder response = LiveDeviceStatusResponse.newBuilder()
                .setDeviceId(deviceId)
                .setAttached(session != null && !session.isEmpty());

        if (session != null && !session.isEmpty()) {
            response.setCurrentTotalBytes(parseLong(session.get("totalBytes")));

            String status = asString(session.get("status"));
            if (status != null) {
                response.setCurrentStatus(status);
            }

            Timestamp startTs = parseTimestamp(session.get("startTs"));
            if (startTs != null) {
                response.setStartTs(startTs);
            }

            Timestamp lastActiveTs = parseTimestamp(session.get("lastActive"));
            if (lastActiveTs != null) {
                response.setLastActiveTs(lastActiveTs);
            }
        }

        LiveDeviceStatusResponse builtResponse = response.build();
        responseObserver.onNext(builtResponse);
        responseObserver.onCompleted();
        log.info("grpcResponse method=GetLiveDeviceStatus deviceId={} attached={} currentTotalBytes={} currentStatus={}",
            deviceId,
            builtResponse.getAttached(),
            builtResponse.getCurrentTotalBytes(),
            builtResponse.getCurrentStatus().isBlank() ? "NA" : builtResponse.getCurrentStatus());
    }

    @Override
    public void getBillingHistory(BillingHistoryRequest request,
                                  StreamObserver<BillingHistoryResponse> responseObserver) {
        String deviceId = validateDeviceId(request.getDeviceId());
        log.info("grpcRequest method=GetBillingHistory deviceId={}", deviceId);

        List<BillingSessionEntity> sessions = billingSessionRepository
                .findTop10ByDeviceIdOrderByFinalizedAtDesc(deviceId);

        BillingHistoryResponse.Builder response = BillingHistoryResponse.newBuilder().setDeviceId(deviceId);

        for (BillingSessionEntity session : sessions) {
            BillingRecord.Builder record = BillingRecord.newBuilder()
                    .setSessionId(session.getSessionId().toString())
                    .setDeviceId(session.getDeviceId())
                    .setTotalBytes(session.getTotalBytes())
                    .setFinalStatus(session.getFinalStatus())
                    .setFinalizedByEventId(session.getFinalizedByEventId().toString());

            if (session.getStartTs() != null) {
                record.setStartTs(toProtoTs(session.getStartTs()));
            }
            if (session.getEndTs() != null) {
                record.setEndTs(toProtoTs(session.getEndTs()));
            }
            if (session.getFinalizedAt() != null) {
                record.setFinalizedAt(toProtoTs(session.getFinalizedAt()));
            }

            response.addRecords(record.build());
        }

        BillingHistoryResponse builtResponse = response.build();
        responseObserver.onNext(builtResponse);
        responseObserver.onCompleted();
        log.info("grpcResponse method=GetBillingHistory deviceId={} recordCount={}",
                deviceId,
                builtResponse.getRecordsCount());
    }

    private String validateDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("grpcValidationError field=device_id reason=blank");
            throw Status.INVALID_ARGUMENT.withDescription("device_id must not be blank").asRuntimeException();
        }

        String trimmed = deviceId.trim();
        if (trimmed.length() > MAX_DEVICE_ID_LENGTH) {
            log.warn("grpcValidationError field=device_id reason=too_long length={}", trimmed.length());
            throw Status.INVALID_ARGUMENT.withDescription("device_id length must be <= 50").asRuntimeException();
        }

        if (!trimmed.matches("^[a-zA-Z0-9._:-]+$")) {
            log.warn("grpcValidationError field=device_id reason=invalid_format value={}", trimmed);
            throw Status.INVALID_ARGUMENT
                    .withDescription("device_id contains unsupported characters")
                    .asRuntimeException();
        }

        return trimmed;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Timestamp parseTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value.toString());
            return toProtoTs(instant);
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp toProtoTs(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
