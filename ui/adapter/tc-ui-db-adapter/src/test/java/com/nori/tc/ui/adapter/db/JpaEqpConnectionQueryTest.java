package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.*;
import com.nori.tc.db.domain.common.eqp.EqpStateType;
import com.nori.tc.db.domain.eqp.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 혼합 100행 밖 CONN도 정확히 조회하며 최신 빈 관측을 과거 정상값으로 바꾸지 않습니다. */
class JpaEqpConnectionQueryTest {
    final TcEqpStore equipment = mock(TcEqpStore.class);
    final TcEqpStateHistStore history = mock(TcEqpStateHistStore.class);
    final TcEqpStateStore states = mock(TcEqpStateStore.class);
    final JpaEqpQueryPort port = new JpaEqpQueryPort(equipment, mock(TcEqpParamStore.class),
            mock(TcEqpParamVersionStore.class), states, history);

    void exists() {
        var row = mock(TcEqp.class);
        when(row.eqpKey()).thenReturn(181L);
        when(equipment.findByEqpId("ETC_001")).thenReturn(Optional.of(row));
    }
    @Test void latestConnOutsideMixedHundredIsNotLost() {
        exists();
        when(history.findAllByEqpKey(eq(181L), any())).thenReturn(IntStream.range(0, 100)
                .mapToObj(i -> new TcEqpStateHist(i + 1000, 181, EqpStateType.OPER, "DOWN", "RUN",
                        OffsetDateTime.parse("2026-11-25T10:00Z"), null, null)).toList());
        when(history.findLatestConnStateByEqpKeyIn(List.of(181L))).thenReturn(List.of(conn("OFFLINE")));
        assertEquals("OFFLINE", port.findRuntimeStateByEqpId("ETC_001").orElseThrow().connectionState());
        verify(history).findLatestConnStateByEqpKeyIn(List.of(181L));
        verify(history, never()).findAllByEqpKey(anyLong(), any());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "UNKNOWN"})
    void latestMissingObservationIsNotReplacedWithAnOlderValidState(String value) {
        exists();
        when(history.findLatestConnStateByEqpKeyIn(List.of(181L))).thenReturn(List.of(conn(value)));
        var result = port.findRuntimeStateByEqpId("ETC_001").orElseThrow();
        assertEquals(value == null ? null : value.trim(), result.connectionState());
        verify(history, never()).findAllByEqpKey(anyLong(), any());
    }
    @Test void noConnAndMissingEquipmentRemainDifferent() {
        assertTrue(port.findRuntimeStateByEqpId("ETC_001").isEmpty());
        verifyNoInteractions(history, states);
        exists();
        assertNull(port.findRuntimeStateByEqpId("ETC_001").orElseThrow().connectionState());
    }
    @Test void exactConnFailurePropagatesToExistingSourceFailureBoundary() {
        exists();
        var failure = new IllegalStateException("시험 조회 실패");
        when(history.findLatestConnStateByEqpKeyIn(List.of(181L))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> port.findRuntimeStateByEqpId("ETC_001")));
    }
    private static TcEqpStateHist conn(String value) {
        return new TcEqpStateHist(900, 181, EqpStateType.CONN, "ONLINE", value,
                OffsetDateTime.parse("2026-11-24T10:00Z"), null, null);
    }
}
