package com.nori.tc.ui.core.ai;

import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** TC AI 조회의 저장소 경계이며 DB 구현 자료형을 외부로 전달하지 않습니다. */
public interface TcAiReadPort {
    /** Redis 구현과 DB 구현을 별도 Bean으로 연결하는 읽기 경계입니다. */
    interface RedisReads {
        List<TcAiRedisFacts.DlqFact> readDlq(TcAiRedisFacts.Source source, TcAiRedisFacts.ReadBudget budget);

        List<TcAiRedisFacts.CommLogFact> readCommLogs(TcAiRedisFacts.Source source, String eqp,
                Instant from, Instant to, TcAiRedisFacts.ReadBudget budget);
    }

    Optional<EquipmentFacts> readEquipment(String eqpId);

    /** 설비 존재와 관측 런타임만 조회하며 작업·LOT를 읽지 않습니다. */
    Optional<EqpRuntimeStateView> readConnection(String eqpId);

    /** 호출자가 이미 조회한 지정 설비의 라인 관계를 정확한 키로 읽습니다. */
    Optional<LineFacts> readLine(long lineKey);

    record LineFacts(String lineCode, String lineName, boolean active) {}

    List<ModelFacts> readModels(String eqpId);

    record EquipmentFacts(UiEqp eqp, EqpRuntimeStateView runtimeState, List<Work> works) {
        public EquipmentFacts { works = List.copyOf(works); }
    }

    record Work(long workKey, long eqpKey, String workId, String operatorId, Integer stepSeq,
                String workState, OffsetDateTime startTime, OffsetDateTime endTime, String mesMessage,
                OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Lot> lots) {
        public Work { lots = List.copyOf(lots); }
    }

    record Lot(long workLotKey, long workKey, String carrierId, String lotId,
               String parentLotId, String chamberId, OffsetDateTime updatedAt) {}

    record ModelFacts(UiModel model, List<UiModelParam> parameters, List<UiModelSecsMessage> secsMessages,
                      List<UiModelSocketMessage> socketMessages, List<UiModelMesMessage> mesMessages) {
        public ModelFacts {
            parameters = List.copyOf(parameters);
            secsMessages = List.copyOf(secsMessages);
            socketMessages = List.copyOf(socketMessages);
            mesMessages = List.copyOf(mesMessages);
        }
    }
}
