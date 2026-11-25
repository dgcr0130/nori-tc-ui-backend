package com.nori.tc.ui.core.ai;

import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.domain.eqp.UiEqp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TcAiConnectionReadServiceTest {
    final TcAiReadPort port = mock(TcAiReadPort.class);
    final TcAiReadService service = new TcAiReadService(port, mock(TcAiReadPort.RedisReads.class));

    @Test void validObservedConnectionDoesNotRequireOperationalLotCompleteness() {
        when(port.readConnection("ETC_001")).thenReturn(Optional.of(new EqpRuntimeStateView("REMOTE", "RUN", "OFFLINE", "STARTED")));
        var result = service.readConnectionState("ETC_001");
        assertEquals(TcAiReadService.Status.SUCCESS, result.status());
        assertEquals(List.of(), result.missingFields());
        assertEquals("OFFLINE", result.runtimeState().connectionState());
        verify(port).readConnection("ETC_001");
        verifyNoMoreInteractions(port);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "UNKNOWN", "unknown"})
    void missingObservationIsExplicitlyPartial(String state) {
        when(port.readConnection("ETC_001")).thenReturn(Optional.of(new EqpRuntimeStateView(null, null, state, null)));
        var result = service.readConnectionState("ETC_001");
        assertEquals(TcAiReadService.Status.PARTIAL, result.status());
        assertEquals(List.of("connectionState"), result.missingFields());
        assertEquals(state, result.runtimeState().connectionState());
    }
    @Test void missingEquipmentAndFailedSourceAreNotMissingConnection() {
        assertEquals(TcAiReadService.Status.EMPTY, service.readConnectionState("MISSING").status());
        var failure = new IllegalStateException("시험 조회 실패");
        when(port.readConnection("ETC_001")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.readConnectionState("ETC_001")));
    }
    @Test void exactEquipmentLineDistinguishesUnassignedMissingInactiveAndFailure() {
        assertEquals(TcAiReadService.LineStatus.UNASSIGNED, service.readRegistrationLine(equipment(0)).status());
        verifyNoInteractions(port);
        assertEquals(TcAiReadService.LineStatus.NOT_FOUND, service.readRegistrationLine(equipment(1501)).status());
        when(port.readLine(1501)).thenReturn(Optional.of(new TcAiReadPort.LineFacts("LINE_A", "A line", false)));
        var line = service.readRegistrationLine(equipment(1501));
        assertEquals(TcAiReadService.LineStatus.RESOLVED, line.status());
        assertEquals("A line", line.line().lineName());
        assertFalse(line.line().active());
        when(port.readLine(1501)).thenThrow(new IllegalStateException("시험 라인 실패"));
        assertThrows(IllegalStateException.class, () -> service.readRegistrationLine(equipment(1501)));
    }
    private static UiEqp equipment(long line) {
        return new UiEqp(181L, "ETC_001", line, null, null, false, null, null, 6100,
                77, "params-v9", null, null, true, null, null, null, null);
    }
}
