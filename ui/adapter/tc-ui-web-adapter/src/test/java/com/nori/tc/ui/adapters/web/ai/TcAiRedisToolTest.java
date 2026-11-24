package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.*;
import com.nori.ai.integration.tool.AiToolEnvelopeAssembler;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiReadService;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Redis 처리기의 입력·한 건 JSON 값과 이름표 본문·시각·공통 외피 일관성을 확인합니다. */
class TcAiRedisToolTest {
    private static final Instant FROM = Instant.parse("2026-11-23T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TcAiReadPort.RedisReads redis = mock(TcAiReadPort.RedisReads.class);
    private final TcAiToolConfiguration tools = new TcAiToolConfiguration(
            new TcAiReadService(mock(TcAiReadPort.class), redis));

    @BeforeEach
    void emptySources() {
        for (Source source : Source.values()) {
            when(redis.readDlq(eq(source), any())).thenReturn(List.of());
            when(redis.readCommLogs(eq(source), anyString(), any(), any(), any())).thenReturn(List.of());
        }
    }

    @Test
    void dlqValuesPreserveDeclarationOrderNullsNativeNumbersTagsAndInnerSource() {
        var g = new GatewayEntry("g", "EQP", null, "SECS", null, "stage", "code", null,
                FROM.toEpochMilli(), null, 123, 456, Map.of("tag", "값"), null);
        var b = new BusinessEntry("b", "UI_BACKEND", null, null, null, FROM.toEpochMilli(), null,
                3, 4L, "EQP", null, "S3F17", null, null, null, 30L);
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenReturn(List.of(new DlqFact(Source.GATEWAY, "g-key", 2, g)));
        when(redis.readDlq(eq(Source.BUSINESS), any())).thenReturn(List.of(new DlqFact(Source.BUSINESS, "b-key", 7, b)));
        var result = dlq(Map.of());
        assertEquals(AiToolResult.Status.PARTIAL, result.status());
        assertEquals(List.of("completeness"), result.missingFields());
        var first = result.items().getFirst();
        assertEquals("gateway:g-key:2", first.ref());
        assertEquals(FROM, first.time());
        assertEquals(String.join("\n",
                "[DLQ 데드레터 큐 목록 항목]",
                "출처(source): gateway",
                "내용 > DLQ ID(dlqId): g",
                "내용 > 설비 ID(equipmentId): EQP",
                "내용 > 추적 ID(traceId): 없음",
                "내용 > 통신 방식(commInterfaceType): SECS",
                "내용 > 소켓 유형(socketType): 없음",
                "내용 > 실패 단계(stage): stage",
                "내용 > 실패 사유 코드(reasonCode): code",
                "내용 > 실패 사유 메시지(reasonMessage): 없음",
                "내용 > 발생 시각(occurredAt): " + FROM.toEpochMilli(),
                "내용 > 원문 참조 키(payloadRefKey): 없음",
                "내용 > 원문 길이(rawLen): 123",
                "내용 > Base64 길이(b64Len): 456",
                "내용 > 태그 > tag: 값",
                "내용 > 보존 시간 초(ttlSeconds): 없음"), first.text());
        var tree = JSON.valueToTree(first.values());
        assertEquals(List.of("source", "entry"), tree.propertyNames().stream().toList());
        assertEquals(List.of("dlqId", "equipmentId", "traceId", "commInterfaceType", "socketType", "stage",
                "reasonCode", "reasonMessage", "occurredAt", "payloadRefKey", "rawLen", "b64Len", "tags", "ttlSeconds"),
                tree.get("entry").propertyNames().stream().toList());
        assertTrue(tree.at("/entry/traceId").isNull());
        assertTrue(tree.at("/entry/ttlSeconds").isNull());
        assertTrue(tree.at("/entry/occurredAt").isIntegralNumber());
        assertEquals("값", tree.at("/entry/tags/tag").asString());
        var second = result.items().getLast();
        assertEquals("business:b-key:7", second.ref());
        var business = JSON.valueToTree(second.values());
        assertEquals("business", business.get("source").asString());
        assertEquals("UI_BACKEND", business.at("/entry/source").asString());
        assertEquals(List.of("dlqId", "source", "stage", "reasonCode", "reasonMessage", "occurredAt", "topic",
                "partition", "offset", "eqpId", "messageType", "messageName", "traceId", "payloadRef", "tags", "ttlSeconds"),
                business.get("entry").propertyNames().stream().toList());
        assertTrue(business.at("/entry/tags").isNull());
        assertThat(second.text().lines().toList()).contains("출처(source): business", "내용 > 출처(source): UI_BACKEND",
                "내용 > 설비 ID(eqpId): EQP", "내용 > Kafka 토픽(topic): 없음", "내용 > 파티션(partition): 3",
                "내용 > 태그(tags): 없음", "내용 > 보존 시간 초(ttlSeconds): 30");
        assertEquals(200, envelope("tc.dlq.list", result).http());
    }

    @Test
    void communicationUsesMessageNameUtcWithSecondsAndFullUnverifiedPeriodEvenWhenEmpty() {
        when(redis.readCommLogs(eq(Source.GATEWAY), anyString(), any(), any(), any())).thenReturn(List.of(
                new CommLogFact(Source.GATEWAY, "tc:gateway:comm-log:EQP", 0, FROM.toEpochMilli(), null, "S3F17")));
        var result = logs(period());
        var item = result.items().getFirst();
        assertEquals(FROM, item.time());
        assertEquals(String.join("\n",
                "[통신 로그]",
                "조회 조건 > 설비(eqp): EQP",
                "조회 조건 > 시작 시각(from): 2026-11-23T00:00:00Z",
                "조회 조건 > 종료 시각(to): 2026-11-23T00:00:10Z",
                "출처(source): gateway",
                "발생 시각(occurredAt): 2026-11-23T00:00:00Z",
                "송수신 방향(direction): 없음",
                "메시지 이름(messageName): S3F17"), item.text());
        assertEquals("{\"source\":\"gateway\",\"occurredAt\":\"2026-11-23T00:00:00Z\",\"direction\":null,\"messageName\":\"S3F17\"}",
                JSON.writeValueAsString(item.values()));
        assertEquals(List.of(AiToolResult.Warning.RETENTION_UNVERIFIED), result.warnings());
        assertEquals(List.of(new AiToolResult.TimeRange(FROM, TO)), result.retentionMeta().unverifiedRanges());
        assertEquals(200, envelope("tc.commLogs.search", result).http());
        when(redis.readCommLogs(eq(Source.GATEWAY), anyString(), any(), any(), any())).thenReturn(List.of());
        var empty = logs(period());
        assertEquals(AiToolResult.Status.PARTIAL, empty.status());
        assertTrue(empty.items().isEmpty());
        assertEquals(result.retentionMeta(), empty.retentionMeta());
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidOptionalInputBecomes400WithoutReads(Map<String, Object> input) {
        clearInvocations(redis);
        var response = envelope("tc.dlq.list", dlq(input));
        assertEquals(400, response.http());
        assertEquals("INPUT_INVALID", JSON.readTree(response.body()).get("errorCode").asString());
        verifyNoInteractions(redis);
    }

    static Stream<Map<String, Object>> invalidInputs() {
        return Stream.of(Map.of("unknown", "v"), Map.of("eqp", 1), Map.of("eqp", " "), Map.of("eqp", "😀".repeat(65)),
                Map.of("from", FROM.toString()), Map.of("to", TO.toString()),
                Map.of("from", TO.toString(), "to", FROM.toString()), Map.of("from", FROM.toString(), "to", FROM.toString()),
                Map.of("from", 1, "to", TO.toString()), Map.of("from", true, "to", TO.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-23T00:00:00+00:00", "2026-11-23T09:00:00+09:00", "2026-11-23T00:00Z",
            "2026-11-23t00:00:00z", "2026-11-23T00:00:00", "2026-13-23T00:00:00Z", "2026-11-23T24:00:00Z", "no-time"})
    void rejectsNonUtcOrMissingSeconds(String time) {
        assertEquals(AiToolResult.Status.FAILED, dlq(Map.of("from", time, "to", TO.toString())).status());
        assertEquals(AiToolResult.Status.FAILED, logs(Map.of("eqp", "EQP", "from", time, "to", TO.toString())).status());
    }

    @Test
    void instantParseAcceptsOffsetsSoExplicitUtcValidationIsNecessary() {
        assertEquals(FROM, Instant.parse("2026-11-23T09:00:00+09:00"));
    }

    @Test
    void explicitOptionalNullIsOmittedButEachRequiredNullIsRejected() {
        Map<String, Object> optional = new HashMap<>();
        optional.put("eqp", null); optional.put("from", null); optional.put("to", null);
        assertEquals(AiToolResult.Status.PARTIAL, dlq(optional).status());
        for (String key : List.of("eqp", "from", "to")) {
            Map<String, Object> input = new HashMap<>(period());
            input.put(key, null);
            assertEquals(AiToolResult.Status.FAILED, logs(input).status());
            input.remove(key);
            assertEquals(AiToolResult.Status.FAILED, logs(input).status());
        }
    }

    @Test
    void unknownDlqTimeRemainsRawAndHasNoInventedTime() {
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenReturn(List.of(new DlqFact(Source.GATEWAY, "key", 0,
                new GatewayEntry("id", "EQP", null, null, null, null, null, null, -1L, null, 0, 0, null, null))));
        var result = dlq(Map.of("eqp", "😀".repeat(64)));
        assertEquals(AiToolResult.Status.PARTIAL, result.status());
        var item = dlq(Map.of()).items().getFirst();
        assertNull(item.time());
        assertEquals(-1L, JSON.valueToTree(item.values()).at("/entry/occurredAt").asLong());
        assertEquals(400, envelope("tc.dlq.list", dlq(Map.of("from", FROM.toString(), "to", "2026-11-23T00:00:10+00:00"))).http());
    }

    @Test
    void allSourceFailuresProduce503WithNoWarningsOrDataAndDeadlineProduces504() {
        for (Source source : Source.values()) {
            when(redis.readDlq(eq(source), any())).thenThrow(new SourceReadException(new IllegalStateException()));
            when(redis.readCommLogs(eq(source), anyString(), any(), any(), any()))
                    .thenThrow(new SourceReadException(new IllegalStateException()));
        }
        for (AiToolResult failure : List.of(dlq(Map.of()), logs(period()))) {
            var response = envelope("tc.dlq.list", failure);
            assertEquals(503, response.http());
            var tree = JSON.readTree(response.body());
            assertTrue(tree.get("data").isNull());
            assertEquals(0, tree.get("warnings").size());
        }
        var timeout = tools.tcAiDlqList().handler().handle(call("tc.dlq.list", Map.of(), Instant.EPOCH));
        assertEquals(504, envelope("tc.dlq.list", timeout).http());
    }

    private AiToolResult dlq(Map<String, Object> input) {
        return tools.tcAiDlqList().handler().handle(call("tc.dlq.list", input, Instant.now().plusSeconds(30)));
    }
    private AiToolResult logs(Map<String, Object> input) {
        return tools.tcAiCommLogsSearch().handler().handle(call("tc.commLogs.search", input, Instant.now().plusSeconds(30)));
    }
    private static Map<String, Object> period() { return Map.of("eqp", "EQP", "from", FROM.toString(), "to", TO.toString()); }
    private static AiToolCall call(String id, Map<String, Object> input, Instant deadline) {
        return new AiToolCall("TC", "job", id, "7", Set.of("DLQ_READ", "COMM_LOG_READ"), input, List.of(), deadline, FROM);
    }
    private static AiToolEnvelopeAssembler.Response envelope(String id, AiToolResult result) {
        return new AiToolEnvelopeAssembler().assemble(new AiToolEnvelopeAssembler.Identity("1", "TC", "job", id), FROM, result);
    }
}
