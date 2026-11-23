package com.nori.tc.ui.core.ai;

import com.nori.tc.ui.core.ai.TcAiReadPort.*;
import com.nori.tc.ui.core.ai.TcAiReadService.*;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.domain.eqp.UiEqp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LOT 후보·종료·판단 불가의 경계를 저장소와 분리해 확인합니다. */
class TcAiDbReadServiceTest {
    private final TcAiReadPort port = mock(TcAiReadPort.class);
    private final TcAiReadService service = new TcAiReadService(port);
    private static final UiEqp EQP = new UiEqp(1L, "EQP", 2, null, null, false, null,
            null, 0, 3, null, null, null, true, null, null, null, null);

    @Test
    void missingEquipmentIsEmpty() {
        when(port.readEquipment("EQP")).thenReturn(Optional.empty());
        var result = service.readOperationalSnapshot("EQP");
        assertEquals(Status.EMPTY, result.status());
        assertNull(result.snapshot());
        assertTrue(result.missingFields().isEmpty());
    }

    @Test
    void noWorksIsUnknownAndAbsentRuntimeHasFourNulls() {
        var result = read(List.of());
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(LotStatus.UNKNOWN, result.snapshot().lotStatus());
        assertEquals(List.of("currentLot"), result.missingFields());
        assertEquals(List.of(), result.snapshot().activeWorks());
        assertEquals(new EqpRuntimeStateView(null, null, null, null), result.snapshot().runtimeState());
        assertNull(result.snapshot().currentLot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"end", "completed", "closed"})
    void endedOnlyDoesNotExposeHistoricalLots(final String state) {
        var result = read(List.of(work(3, state, null, List.of(lot(30, 3)))));
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(LotStatus.ENDED, result.snapshot().lotStatus());
        assertTrue(result.snapshot().activeWorks().isEmpty());
        assertNull(result.snapshot().currentLot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "START", "unknown"})
    void nonEndedInactiveWorkDoesNotImplyEnded(final String state) {
        assertEquals(LotStatus.UNKNOWN, read(List.of(work(3, state, null, List.of()))).snapshot().lotStatus());
    }

    @Test
    void activeWorkWithoutLotsIsPreserved() {
        var result = read(List.of(work(3, "start", null, List.of())));
        assertEquals(Status.PARTIAL, result.status());
        assertTrue(result.snapshot().activeWorks().getFirst().lots().isEmpty());
    }

    @Test
    void startWithEndTimeIsNotAnActiveOrEndedWork() {
        var result = read(List.of(work(3, "start", OffsetDateTime.parse("2026-11-23T10:00Z"), List.of(lot(30, 3)))));
        assertEquals(LotStatus.UNKNOWN, result.snapshot().lotStatus());
        assertTrue(result.snapshot().activeWorks().isEmpty());
    }

    @Test
    void oneKnownLotAndAnotherEmptyActiveWorkUseCandidateCount() {
        var onlyLot = lot(30, 3);
        var result = read(List.of(work(4, "start", null, List.of()), work(3, "start", null, List.of(onlyLot))));
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(LotStatus.ACTIVE, result.snapshot().lotStatus());
        assertEquals(onlyLot, result.snapshot().currentLot());
        assertEquals(List.of(3L, 4L), result.snapshot().activeWorks().stream().map(Work::workKey).toList());
        assertTrue(result.snapshot().activeWorks().getLast().lots().isEmpty());
    }

    @Test
    void multipleCandidatesRemainConflictAndSortBothKeys() {
        var result = read(List.of(work(4, "start", null, List.of(lot(42, 4), lot(41, 4))),
                work(3, "start", null, List.of(lot(30, 3))), work(5, "end", null, List.of(lot(50, 5)))));
        assertEquals(Status.CONFLICT, result.status());
        assertEquals(LotStatus.MULTI, result.snapshot().lotStatus());
        assertNull(result.snapshot().currentLot());
        assertEquals(List.of(3L, 4L), result.snapshot().activeWorks().stream().map(Work::workKey).toList());
        assertEquals(List.of(41L, 42L), result.snapshot().activeWorks().getLast().lots().stream().map(Lot::workLotKey).toList());
    }

    @Test
    void failuresPropagateAndModelsKeepPortOrder() {
        var failure = new IllegalStateException("시험 장애");
        when(port.readEquipment("EQP")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.readOperationalSnapshot("EQP")));
        when(port.readModels(null)).thenReturn(List.of());
        assertEquals(List.of(), service.readModels(null));
    }

    private SnapshotResult read(final List<Work> works) {
        when(port.readEquipment("EQP")).thenReturn(Optional.of(new EquipmentFacts(EQP, null, works)));
        return service.readOperationalSnapshot("EQP");
    }

    private static Work work(long key, String state, OffsetDateTime end, List<Lot> lots) {
        return new Work(key, 1, null, null, null, state, null, end, null, null, null, lots);
    }

    private static Lot lot(long key, long work) {
        return new Lot(key, work, null, "LOT" + key, null, null, null);
    }
}
