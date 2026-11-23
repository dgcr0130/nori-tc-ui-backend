package com.nori.tc.ui.core.ai;

import com.nori.tc.ui.core.ai.TcAiReadPort.EquipmentFacts;
import com.nori.tc.ui.core.ai.TcAiReadPort.Lot;
import com.nori.tc.ui.core.ai.TcAiReadPort.ModelFacts;
import com.nori.tc.ui.core.ai.TcAiReadPort.Work;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.domain.eqp.UiEqp;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 전체 작업에서 현재 LOT를 판정하며 저장소 장애를 정상 부재로 바꾸지 않습니다. */
@ConditionalOnProperty(name = "nori.ai.integration.enabled", havingValue = "true")
public class TcAiReadService {
    private static final Set<String> ENDED_STATES = Set.of("end", "completed", "closed");
    private final TcAiReadPort readPort;

    public TcAiReadService(final TcAiReadPort readPort) {
        this.readPort = Objects.requireNonNull(readPort);
    }

    public SnapshotResult readOperationalSnapshot(final String eqpId) {
        var facts = readPort.readEquipment(eqpId);
        if (facts.isEmpty()) return new SnapshotResult(Status.EMPTY, null, List.of());
        return snapshot(facts.get());
    }

    public List<ModelFacts> readModels(final String eqpId) {
        return readPort.readModels(eqpId);
    }

    private SnapshotResult snapshot(final EquipmentFacts facts) {
        List<Work> activeWorks = facts.works().stream()
                .filter(work -> "start".equals(work.workState()) && work.endTime() == null)
                .sorted(Comparator.comparingLong(Work::workKey))
                .map(work -> new Work(work.workKey(), work.eqpKey(), work.workId(), work.operatorId(),
                        work.stepSeq(), work.workState(), work.startTime(), work.endTime(), work.mesMessage(),
                        work.createdAt(), work.updatedAt(), work.lots().stream()
                                .sorted(Comparator.comparingLong(Lot::workLotKey)).toList()))
                .toList();
        List<Lot> candidates = activeWorks.stream().flatMap(work -> work.lots().stream()).toList();
        LotStatus lotStatus;
        if (candidates.size() > 1) lotStatus = LotStatus.MULTI;
        else if (candidates.size() == 1) lotStatus = LotStatus.ACTIVE;
        else if (!facts.works().isEmpty() && facts.works().stream()
                .allMatch(work -> ENDED_STATES.contains(work.workState() == null ? "" : work.workState()))) {
            lotStatus = LotStatus.ENDED;
        } else lotStatus = LotStatus.UNKNOWN;
        Status status = switch (lotStatus) {
            case MULTI -> Status.CONFLICT;
            case UNKNOWN -> Status.PARTIAL;
            case ACTIVE, ENDED -> Status.SUCCESS;
        };
        EqpRuntimeStateView runtime = facts.runtimeState() == null
                ? new EqpRuntimeStateView(null, null, null, null) : facts.runtimeState();
        var value = new OperationalSnapshot(facts.eqp(), runtime, activeWorks, lotStatus,
                lotStatus == LotStatus.ACTIVE ? candidates.getFirst() : null);
        return new SnapshotResult(status, value, lotStatus == LotStatus.UNKNOWN ? List.of("currentLot") : List.of());
    }

    public enum Status { SUCCESS, EMPTY, PARTIAL, CONFLICT }
    public enum LotStatus { ACTIVE, MULTI, ENDED, UNKNOWN }
    public record OperationalSnapshot(UiEqp eqp, EqpRuntimeStateView runtimeState, List<Work> activeWorks,
                                      LotStatus lotStatus, Lot currentLot) {
        public OperationalSnapshot { activeWorks = List.copyOf(activeWorks); }
    }
    public record SnapshotResult(Status status, OperationalSnapshot snapshot, List<String> missingFields) {
        public SnapshotResult { missingFields = List.copyOf(missingFields); }
    }
}
