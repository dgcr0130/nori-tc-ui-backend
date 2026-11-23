package com.nori.tc.ui.core.ai;

import com.nori.tc.ui.core.ai.TcAiRedisFacts.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 원천 실패·필터·시각 없는 행을 TC 자료형만으로 판정합니다. */
class TcAiRedisReadServiceTest {
    private static final Instant FROM = Instant.parse("2026-11-23T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(10);
    private final TcAiReadPort.RedisReads redis = mock(TcAiReadPort.RedisReads.class);
    private final TcAiReadService service = new TcAiReadService(mock(TcAiReadPort.class), redis);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void eachSourceFailureAndEmptyAreDistinctForBothTools(int failures) {
        for (Source source : Source.values()) {
            if ((failures & (1 << source.ordinal())) != 0) {
                when(redis.readDlq(eq(source), any())).thenThrow(new SourceReadException(new IllegalStateException()));
                when(redis.readCommLogs(eq(source), anyString(), any(), any(), any()))
                        .thenThrow(new SourceReadException(new IllegalStateException()));
            } else {
                when(redis.readDlq(eq(source), any())).thenReturn(List.of());
                when(redis.readCommLogs(eq(source), anyString(), any(), any(), any())).thenReturn(List.of());
            }
        }
        var dlq = service.readDlq(null, null, null, deadline());
        var logs = service.readCommLogs("EQP", FROM, TO, deadline());
        var expected = failures == 3 ? ReadStatus.SOURCE_UNAVAILABLE : ReadStatus.PARTIAL;
        assertEquals(expected, dlq.status());
        assertEquals(expected, logs.status());
        assertEquals(dlq.missingFields(), logs.missingFields());
        assertEquals(switch (failures) {
            case 0 -> List.of("completeness");
            case 1 -> List.of("gateway", "completeness");
            case 2 -> List.of("business", "completeness");
            default -> List.of("gateway", "business");
        }, dlq.missingFields());
        assertTrue(dlq.items().isEmpty());
        assertTrue(logs.items().isEmpty());
    }

    @Test
    void preservesReadOrderAcrossFiltersAndSortsUnknownTimeLast() {
        var g0 = fact(Source.GATEWAY, 0, "EQP", 0);
        var g1 = fact(Source.GATEWAY, 1, "EQP", FROM.toEpochMilli());
        var g2 = fact(Source.GATEWAY, 2, "EQP", FROM.toEpochMilli());
        var b0 = fact(Source.BUSINESS, 0, "EQP", -1);
        var b1 = fact(Source.BUSINESS, 1, "EQP", TO.toEpochMilli());
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenReturn(List.of(g0, g1, g2));
        when(redis.readDlq(eq(Source.BUSINESS), any())).thenReturn(List.of(b0, b1));
        var all = service.readDlq(null, null, null, deadline());
        assertEquals(List.of(b1, g1, g2, g0, b0), all.items());
        assertNull(g0.time());
        assertNull(b0.time());
        assertEquals(List.of("completeness"), all.missingFields());
        var period = service.readDlq("EQP", FROM, TO, deadline());
        assertEquals(List.of(g1, g2), period.items());
        assertEquals(List.of("occurredAt", "completeness"), period.missingFields());
        assertEquals("gateway:key:2", period.items().getLast().ref());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void oneFailedSourcePreservesTheOtherSourcesFactsForBothTools(int failedSource) {
        for (Source source : Source.values()) {
            if (source.ordinal() == failedSource) {
                when(redis.readDlq(eq(source), any())).thenThrow(new SourceReadException(new IllegalStateException()));
                when(redis.readCommLogs(eq(source), anyString(), any(), any(), any()))
                        .thenThrow(new SourceReadException(new IllegalStateException()));
            } else {
                when(redis.readDlq(eq(source), any())).thenReturn(List.of(fact(source, 0, "EQP", FROM.toEpochMilli())));
                when(redis.readCommLogs(eq(source), anyString(), any(), any(), any())).thenReturn(List.of(log(source, 0, FROM)));
            }
        }
        Source survivor = Source.values()[1 - failedSource];
        assertEquals(survivor, service.readDlq(null, null, null, deadline()).items().getFirst().source());
        assertEquals(survivor, service.readCommLogs("EQP", FROM, TO, deadline()).items().getFirst().source());
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void otherEquipmentWithUnknownTimeDoesNotCreateMissingFields(Source source) {
        for (Source current : Source.values()) {
            when(redis.readDlq(eq(current), any())).thenReturn(List.of());
        }
        when(redis.readDlq(eq(source), any())).thenReturn(List.of(
                fact(source, 0, "EQ2", 0), fact(source, 1, "EQ2", -1)));
        var filtered = service.readDlq("EQ1", FROM, TO, deadline());
        assertEquals(ReadStatus.PARTIAL, filtered.status());
        assertTrue(filtered.items().isEmpty());
        assertEquals(List.of("completeness"), filtered.missingFields());
        var unscoped = service.readDlq(null, FROM, TO, deadline());
        assertTrue(unscoped.items().isEmpty());
        assertEquals(List.of("occurredAt", "completeness"), unscoped.missingFields());
    }

    @Test
    void unknownEquipmentAndTimeAreReportedOnceInFixedOrder() {
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenThrow(new SourceReadException(new IllegalArgumentException()));
        when(redis.readDlq(eq(Source.BUSINESS), any())).thenReturn(List.of(
                fact(Source.BUSINESS, 0, null, 0), fact(Source.BUSINESS, 1, " ", -1),
                fact(Source.BUSINESS, 2, "OTHER", FROM.toEpochMilli()),
                fact(Source.BUSINESS, 3, "EQP", FROM.toEpochMilli())));
        var filtered = service.readDlq("EQP", FROM, TO, deadline());
        assertEquals(1, filtered.items().size());
        assertEquals(List.of("gateway", "eqpId", "occurredAt", "completeness"), filtered.missingFields());
        var all = service.readDlq(null, null, null, deadline());
        assertEquals(4, all.items().size());
        assertEquals(List.of("gateway", "completeness"), all.missingFields());
    }

    @Test
    void communicationUsesHalfOpenPeriodAndStableSourceOrder() {
        when(redis.readCommLogs(eq(Source.GATEWAY), anyString(), any(), any(), any())).thenReturn(List.of(
                log(Source.GATEWAY, 0, FROM.minusMillis(1)), log(Source.GATEWAY, 1, FROM),
                log(Source.GATEWAY, 2, FROM), log(Source.GATEWAY, 3, TO)));
        when(redis.readCommLogs(eq(Source.BUSINESS), anyString(), any(), any(), any())).thenReturn(List.of(
                log(Source.BUSINESS, 0, FROM), log(Source.BUSINESS, 1, TO.minusMillis(1))));
        assertEquals(List.of("business:key:1", "gateway:key:1", "gateway:key:2", "business:key:0"),
                service.readCommLogs("EQP", FROM, TO, deadline()).items().stream().map(CommLogFact::ref).toList());
    }

    @Test
    void doesNotApplyAnItemLimit() {
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenReturn(IntStream.range(0, 110)
                .mapToObj(i -> fact(Source.GATEWAY, i, "EQP", FROM.toEpochMilli())).toList());
        when(redis.readDlq(eq(Source.BUSINESS), any())).thenReturn(List.of());
        assertEquals(110, service.readDlq(null, null, null, deadline()).items().size());
    }

    @Test
    void deadlineAndInterruptStopBeforeReadingAndPreserveInterrupt() {
        assertEquals(ReadStatus.TIMEOUT, service.readDlq(null, null, null, Instant.EPOCH).status());
        assertEquals(ReadStatus.TIMEOUT, service.readCommLogs("EQP", FROM, TO, Instant.EPOCH).status());
        Thread.currentThread().interrupt();
        try {
            assertEquals(ReadStatus.TIMEOUT, service.readDlq(null, null, null, deadline()).status());
            assertEquals(ReadStatus.TIMEOUT, service.readCommLogs("EQP", FROM, TO, deadline()).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        verifyNoInteractions(redis);
    }

    @Test
    void expiryAfterFirstSourceDiscardsItsFactsAndSkipsSecondSource() {
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenThrow(new ReadTimeoutException());
        assertEquals(ReadStatus.TIMEOUT, service.readDlq(null, null, null, deadline()).status());
        verify(redis, never()).readDlq(eq(Source.BUSINESS), any());
    }

    private static Instant deadline() { return Instant.now().plusSeconds(30); }
    private static DlqFact fact(Source source, int order, String eqp, long time) {
        DlqEntry entry = source == Source.GATEWAY
                ? new GatewayEntry("id", eqp, null, null, null, null, null, null, time, null, 0, 0, null, null)
                : new BusinessEntry("id", "UI_BACKEND", null, null, null, time, null, null, null, eqp, null, null, null, null, null, null);
        return new DlqFact(source, "key", order, entry);
    }
    private static CommLogFact log(Source source, int order, Instant time) {
        return new CommLogFact(source, "key", order, time.toEpochMilli(), null, null);
    }
}
