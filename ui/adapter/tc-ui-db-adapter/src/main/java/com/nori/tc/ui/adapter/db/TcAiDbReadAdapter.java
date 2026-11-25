package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.line.store.TcLineStore;
import com.nori.tc.db.core.work.store.TcWorkStore;
import com.nori.tc.db.core.work.store.TcWorkLotStore;
import com.nori.tc.db.domain.work.TcWork;
import com.nori.tc.db.domain.work.TcWorkLot;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 읽기 한 건의 페이지 경계를 같은 DB 스냅샷에서 순회하고 TC 소유 자료형으로 옮깁니다. */
@ConditionalOnProperty(name = "nori.ai.integration.enabled", havingValue = "true")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TcAiDbReadAdapter implements TcAiReadPort {
    private static final int PAGE_SIZE = 100;
    private final EqpQueryPort equipment;
    private final TcWorkStore works;
    private final TcWorkLotStore lots;
    private final ModelCrudPort models;
    private final ModelDetailQueryPort details;
    private final TcLineStore lines;

    public TcAiDbReadAdapter(final EqpQueryPort equipment, final TcWorkStore works, final TcWorkLotStore lots,
                             final ModelCrudPort models, final ModelDetailQueryPort details,
                             final TcLineStore lines) {
        this.equipment = equipment;
        this.works = works;
        this.lots = lots;
        this.models = models;
        this.details = details;
        this.lines = lines;
    }

    @Override
    public Optional<EqpQueryPort.EqpRuntimeStateView> readConnection(final String eqpId) {
        return equipment.findRuntimeStateByEqpId(eqpId);
    }

    @Override
    public Optional<LineFacts> readLine(final long lineKey) {
        return lines.findByKey(lineKey).map(line -> new LineFacts(line.lineCode(), line.lineName(), line.isActive()));
    }

    @Override
    public Optional<EquipmentFacts> readEquipment(final String eqpId) {
        return equipment.findByEqpId(eqpId).map(eqp -> {
            var runtime = equipment.findRuntimeStateByEqpId(eqpId).orElse(null);
            var allWorks = pages(page -> works.findAllByEqpKey(eqp.eqpKey(), page), TcWork::workKey);
            var mapped = allWorks.stream().sorted(Comparator.comparingLong(TcWork::workKey))
                    .map(this::work).toList();
            return new EquipmentFacts(eqp, runtime, mapped);
        });
    }

    @Override
    public List<ModelFacts> readModels(final String eqpId) {
        if (eqpId != null) {
            return equipment.findByEqpId(eqpId)
                    .flatMap(eqp -> models.findByModelVersionKey(eqp.modelVersionKey()))
                    .map(model -> List.of(new ModelFacts(model,
                            details.findParamsByModelVersionKey(model.modelVersionKey()),
                            details.findSecsMessagesByModelVersionKey(model.modelVersionKey()),
                            details.findSocketMessagesByModelVersionKey(model.modelVersionKey()),
                            details.findMesMessagesByModelVersionKey(model.modelVersionKey()))))
                    .orElseGet(List::of);
        }
        // count는 기존 port의 별도 스캔 결과이므로 끝 판정에 쓰지 않습니다.
        return pages(page -> models.findAll(UiPageRequest.of(page.offset(), page.limit())).items(),
                com.nori.tc.ui.domain.model.UiModel::modelVersionKey).stream()
                .map(model -> new ModelFacts(model, List.of(), List.of(), List.of(), List.of())).toList();
    }

    private Work work(final TcWork work) {
        var workLots = pages(page -> lots.findAllByWorkKey(work.workKey(), page), TcWorkLot::workLotKey)
                .stream().sorted(Comparator.comparingLong(TcWorkLot::workLotKey))
                .map(lot -> new Lot(lot.workLotKey(), lot.workKey(), lot.carrierId(), lot.lotId(),
                        lot.parentLotId(), lot.chamberId(), lot.updatedAt())).toList();
        return new Work(work.workKey(), work.eqpKey(), work.workId(), work.operatorId(), work.stepSeq(),
                work.workState(), work.startTime(), work.endTime(), work.mesMessage(), work.createdAt(),
                work.updatedAt(), workLots);
    }

    private static <T> List<T> pages(final Function<PageRequest, List<T>> query, final ToLongFunction<T> key) {
        List<T> all = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int offset = 0; ; offset = Math.addExact(offset, PAGE_SIZE)) {
            List<T> page = query.apply(PageRequest.of(offset, PAGE_SIZE));
            for (T row : page) {
                // 중복 제거로 정상 완전 조회를 가장하지 않습니다. 예외는 공통 처리기의 503 경계로 전달합니다.
                if (!seen.add(key.applyAsLong(row))) throw new IllegalStateException("AI 페이지 조회의 키가 중복되었습니다.");
                all.add(row);
            }
            if (page.size() < PAGE_SIZE) return List.copyOf(all);
        }
    }
}
