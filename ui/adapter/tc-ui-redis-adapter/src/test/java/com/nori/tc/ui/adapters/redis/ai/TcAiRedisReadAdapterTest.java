package com.nori.tc.ui.adapters.redis.ai;

import com.nori.tc.ui.core.ai.TcAiRedisFacts.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 typeless 직렬화기와 명령 대역으로 연결·커서·source 폐기 경계를 확인합니다. */
class TcAiRedisReadAdapterTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private final RedisKeyCommands keys = mock(RedisKeyCommands.class);
    private final RedisStringCommands strings = mock(RedisStringCommands.class);
    private final RedisTemplate<String, Object> dlq = new RedisTemplate<>();
    private final RedisTemplate<String, Object> comm = mock();
    private final ZSetOperations<String, Object> zset = mock();
    private final GenericJacksonJsonRedisSerializer serializer = new GenericJacksonJsonRedisSerializer(mapper);
    private TcAiRedisReadAdapter adapter;

    @BeforeEach
    void setup() {
        when(factory.getConnection()).thenReturn(connection);
        when(connection.keyCommands()).thenReturn(keys);
        when(connection.stringCommands()).thenReturn(strings);
        dlq.setConnectionFactory(factory);
        dlq.setKeySerializer(new StringRedisSerializer());
        dlq.setValueSerializer(serializer);
        dlq.afterPropertiesSet();
        when(comm.opsForZSet()).thenReturn(zset);
        adapter = new TcAiRedisReadAdapter(dlq, comm, mapper);
    }

    @Test
    void typelessGatewayAndBusinessPreserveNativeFieldsAndCloseEachConnection() {
        var cursor = cursor("g");
        when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(strings.get(bytes("g"))).thenReturn(serializer.serialize(Map.of("dlqId", "g", "equipmentId", "EQP",
                "occurredAt", 1L, "rawLen", 2L, "b64Len", 3L, "tags", Map.of("a", "b"))));
        var result = adapter.readDlq(Source.GATEWAY, budget());
        var entry = (GatewayEntry) result.getFirst().entry();
        assertEquals("EQP", entry.equipmentId());
        assertEquals(2, entry.rawLen());
        assertEquals(Map.of("a", "b"), entry.tags());
        assertNull(entry.reasonMessage());
        verify(cursor).close();
        verify(connection).close();

        var businessCursor = cursor("b");
        when(keys.scan(any(ScanOptions.class))).thenReturn(businessCursor);
        when(strings.get(bytes("b"))).thenReturn(serializer.serialize(Map.of("dlqId", "b", "source", "UI_BACKEND",
                "eqpId", "EQP", "occurredAt", 2L, "partition", 3, "offset", 4L)));
        var business = adapter.readDlq(Source.BUSINESS, budget()).getFirst();
        assertEquals("business:b:0", business.ref());
        assertEquals("UI_BACKEND", ((BusinessEntry) business.entry()).source());
        assertEquals(3, ((BusinessEntry) business.entry()).partition());
        assertEquals(4L, ((BusinessEntry) business.entry()).offset());
        verify(businessCursor).close();
        verify(connection, times(2)).close();
        var captor = org.mockito.ArgumentCaptor.forClass(ScanOptions.class);
        verify(keys, times(2)).scan(captor.capture());
        assertEquals("tc:gateway:dlq:*", captor.getAllValues().getFirst().getPattern());
        assertEquals("tc:business:core:dlq:*", captor.getAllValues().getLast().getPattern());
    }

    @Test
    void deletionBetweenScanAndGetIsSkippedWithoutConsumingReadOrder() {
        var cursor = cursor("gone", "present");
        when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(strings.get(bytes("present"))).thenReturn(serializer.serialize(Map.of("dlqId", "present", "occurredAt", 0L, "rawLen", 0L, "b64Len", 0L)));
        var result = adapter.readDlq(Source.GATEWAY, budget());
        assertEquals(1, result.size());
        assertEquals("gateway:present:0", result.getFirst().ref());
        verify(cursor).close();
        verify(connection).close();
    }

    @Test
    void malformedIndividualKeyDiscardsSourceAndClosesResources() {
        for (Object malformed : List.of("wrong-type", Map.of("occurredAt", "not-a-number"))) {
            var cursor = cursor("valid", "invalid");
            when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
            when(strings.get(bytes("valid"))).thenReturn(serializer.serialize(Map.of("dlqId", "valid", "occurredAt", 0L, "rawLen", 0L, "b64Len", 0L)));
            when(strings.get(bytes("invalid"))).thenReturn(serializer.serialize(malformed));
            assertThrows(SourceReadException.class, () -> adapter.readDlq(Source.GATEWAY, budget()));
            verify(cursor).close();
        }
        verify(connection, times(2)).close();
    }

    @Test
    void scanAndGetFailuresRemainSourceFailures() {
        when(keys.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("대역 SCAN 실패"));
        assertThrows(SourceReadException.class, () -> adapter.readDlq(Source.BUSINESS, budget()));
        verify(connection).close();
        var cursor = cursor("key");
        doReturn(cursor).when(keys).scan(any(ScanOptions.class));
        when(strings.get(any())).thenThrow(new IllegalStateException("대역 GET 실패"));
        assertThrows(SourceReadException.class, () -> adapter.readDlq(Source.GATEWAY, budget()));
        verify(cursor).close();
        verify(connection, times(2)).close();
    }

    @Test
    void interruptionDuringScanStopsBeforeGetAndClosesResources() {
        var cursor = cursor("key");
        when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(invocation -> { Thread.currentThread().interrupt(); return true; });
        try {
            assertThrows(ReadTimeoutException.class, () -> adapter.readDlq(Source.GATEWAY, budget()));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(cursor).close();
            verify(connection).close();
            verify(strings, never()).get(any());
        } finally { Thread.interrupted(); }
    }

    @Test
    void deadlineDuringGetStopsAndClosesResources() {
        var cursor = cursor("key", "unread");
        when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
        var budget = new ReadBudget(Instant.now().plusMillis(100));
        when(strings.get(bytes("key"))).thenAnswer(invocation -> {
            while (Instant.now().isBefore(budget.deadline())) Thread.onSpinWait();
            return serializer.serialize(Map.of("dlqId", "key", "occurredAt", 0L, "rawLen", 0L, "b64Len", 0L));
        });
        assertThrows(ReadTimeoutException.class, () -> adapter.readDlq(Source.GATEWAY, budget));
        verify(cursor).close();
        verify(connection).close();
        verify(strings, never()).get(bytes("unread"));
    }

    @Test
    void expiredAndInterruptedBudgetNeverAcquireConnection() {
        assertThrows(ReadTimeoutException.class, () -> adapter.readDlq(Source.GATEWAY, new ReadBudget(Instant.EPOCH)));
        Thread.currentThread().interrupt();
        try { assertThrows(ReadTimeoutException.class, () -> adapter.readCommLogs(Source.GATEWAY, "EQP",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), budget())); }
        finally { Thread.interrupted(); }
        verify(factory, never()).getConnection();
        verifyNoInteractions(comm);
    }

    @Test
    void communicationReadsRequestedScoresWithoutRetentionCutoffAndPreservesOrder() {
        var from = Instant.parse("2000-01-01T00:00:00.000000001Z");
        var to = from.plusMillis(10);
        Set<Object> values = new LinkedHashSet<>();
        values.add(serializer.deserialize(serializer.serialize(Map.of("timestampMs", from.toEpochMilli() + 1,
                "direction", "SEND", "messageName", "S3F17"))));
        values.add(serializer.deserialize(serializer.serialize(Map.of("timestampMs", from.toEpochMilli() + 2))));
        when(zset.rangeByScore("tc:gateway:comm-log:EQP", (double) from.toEpochMilli() + 1,
                (double) to.toEpochMilli())).thenReturn(values);
        var result = adapter.readCommLogs(Source.GATEWAY, "EQP", from, to, budget());
        assertEquals(2, result.size());
        assertEquals("S3F17", result.getFirst().messageName());
        assertEquals("gateway:tc:gateway:comm-log:EQP:1", result.getLast().ref());
        assertNull(result.getLast().direction());
    }

    @Test
    void communicationCommandOrConversionFailureIsNotEmpty() {
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Set.of("wrong-type"));
        assertThrows(SourceReadException.class, () -> adapter.readCommLogs(Source.BUSINESS, "EQP", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), budget()));
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenThrow(new IllegalStateException("대역 실패"));
        assertThrows(SourceReadException.class, () -> adapter.readCommLogs(Source.GATEWAY, "EQP", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), budget()));
    }

    @Test
    void subMillisecondPeriodStillChecksTheSourceInsteadOfHidingItsFailure() {
        when(zset.rangeByScore("tc:gateway:comm-log:EQP", 1D, 0D)).thenThrow(new IllegalStateException("시험 원천 장애"));
        assertThrows(SourceReadException.class, () -> adapter.readCommLogs(Source.GATEWAY, "EQP",
                Instant.EPOCH.plusNanos(1), Instant.EPOCH.plusNanos(2), budget()));
    }

    @Test
    @SuppressWarnings("deprecation") // Spring Data 4.0.6 템플릿이 호출하는 실제 위임 메서드를 대역으로 둡니다.
    void realCommunicationTemplateReleasesConnectionOnSuccessFailureAndCancellation() {
        byte[] row = serializer.serialize(Map.of("timestampMs", 1L, "messageName", "S3F17"));
        when(connection.zRangeByScore(any(byte[].class), eq(0D), eq(999D))).thenReturn(Set.of(row));
        var real = new TcAiRedisReadAdapter(dlq, dlq, mapper);
        assertEquals("S3F17", real.readCommLogs(Source.GATEWAY, "EQP", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), budget()).getFirst().messageName());
        verify(connection).close();
        when(connection.zRangeByScore(any(byte[].class), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("시험 명령 실패"));
        assertThrows(SourceReadException.class, () -> real.readCommLogs(Source.BUSINESS, "EQP", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), budget()));
        verify(connection, times(2)).close();
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return Set.of(row); })
                .when(connection).zRangeByScore(any(byte[].class), anyDouble(), anyDouble());
        try {
            assertThrows(ReadTimeoutException.class, () -> real.readCommLogs(Source.GATEWAY, "EQP", Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1), budget()));
            verify(connection, times(3)).close();
        } finally { Thread.interrupted(); }
    }

    private static ReadBudget budget() { return new ReadBudget(Instant.now().plusSeconds(30)); }
    private static byte[] bytes(String key) { return key.getBytes(StandardCharsets.UTF_8); }
    private static Cursor<byte[]> cursor(String... names) {
        Cursor<byte[]> cursor = mock();
        var iterator = List.of(names).stream().map(TcAiRedisReadAdapterTest::bytes).iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(cursor.next()).thenAnswer(invocation -> iterator.next());
        return cursor;
    }
}
