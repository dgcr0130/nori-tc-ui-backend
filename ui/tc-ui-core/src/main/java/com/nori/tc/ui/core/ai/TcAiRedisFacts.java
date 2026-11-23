package com.nori.tc.ui.core.ai;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redis 원천 자료를 TC 소유 타입으로 보존하며 공통 HTTP 자료형과 분리합니다. */
public final class TcAiRedisFacts {
    private TcAiRedisFacts() {}

    public enum Source {
        GATEWAY("gateway"), BUSINESS("business");
        private final String value;
        Source(String value) { this.value = value; }
        public String value() { return value; }
    }

    public sealed interface DlqEntry permits GatewayEntry, BusinessEntry {
        String equipment();
        long occurredAt();
    }

    public record GatewayEntry(String dlqId, String equipmentId, String traceId, String commInterfaceType,
            String socketType, String stage, String reasonCode, String reasonMessage, long occurredAt,
            String payloadRefKey, long rawLen, long b64Len, Map<String, String> tags, Long ttlSeconds)
            implements DlqEntry {
        public GatewayEntry { tags = copyTags(tags); }
        @Override public String equipment() { return equipmentId; }
    }

    public record BusinessEntry(String dlqId, String source, String stage, String reasonCode,
            String reasonMessage, long occurredAt, String topic, Integer partition, Long offset,
            String eqpId, String messageType, String messageName, String traceId, String payloadRef,
            Map<String, String> tags, Long ttlSeconds) implements DlqEntry {
        public BusinessEntry { tags = copyTags(tags); }
        @Override public String equipment() { return eqpId; }
    }

    public record DlqFact(Source source, String key, int readOrder, DlqEntry entry) {
        public Instant time() { return entry.occurredAt() > 0 ? Instant.ofEpochMilli(entry.occurredAt()) : null; }
        public String ref() { return source.value() + ":" + key + ":" + readOrder; }
    }

    public record CommLogFact(Source source, String key, int readOrder, long timestampMs,
            String direction, String messageName) {
        public Instant time() { return Instant.ofEpochMilli(timestampMs); }
        public String ref() { return source.value() + ":" + key + ":" + readOrder; }
    }

    /** Redis 명령 전후에 검사하여 취소된 처리가 다음 읽기로 진행하지 않게 합니다. */
    public record ReadBudget(Instant deadline) {
        public ReadBudget { Objects.requireNonNull(deadline); }
        public void check() {
            if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) {
                throw new ReadTimeoutException();
            }
        }
    }

    /** 기한 경과는 source 장애와 합치지 않습니다. */
    public static final class ReadTimeoutException extends RuntimeException {}

    /** 원천 전체를 폐기해야 하는 읽기·변환 실패입니다. */
    public static final class SourceReadException extends RuntimeException {
        public SourceReadException(RuntimeException cause) { super(cause); }
    }

    public enum ReadStatus { PARTIAL, SOURCE_UNAVAILABLE, TIMEOUT }

    public record ReadResult<T>(ReadStatus status, List<T> items, List<String> missingFields) {
        public ReadResult { items = List.copyOf(items); missingFields = List.copyOf(missingFields); }
    }

    private static Map<String, String> copyTags(Map<String, String> tags) {
        return tags == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }
}
