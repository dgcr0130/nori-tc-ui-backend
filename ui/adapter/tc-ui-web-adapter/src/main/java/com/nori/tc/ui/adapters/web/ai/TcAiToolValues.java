package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiToolItem;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.core.ai.TcAiReadPort.Lot;
import com.nori.tc.ui.core.ai.TcAiReadPort.Work;
import com.nori.tc.ui.core.ai.TcAiReadService.OperationalSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 원천 null과 필드 자료형을 보존하며 JSON 값과 RFC3339 UTC 시각으로 옮깁니다. */
final class TcAiToolValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TcAiToolValues() {}

    static AiToolItem item(final String ref, final Map<String, Object> values) {
        // DB 항목은 발생 시각순 자료가 아니므로 절삭용 누락 시간 구간을 만들지 않습니다.
        return new AiToolItem(ref, JSON.writeValueAsString(values), values, null);
    }

    static AiToolItem detail(final String modelRef, final String type, final long key,
                             final Map<String, Object> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("detailType", type);
        values.put("row", row);
        return item(modelRef + ":" + type + ":" + key, values);
    }

    private static String timestamp(final OffsetDateTime time) {
        return time == null ? null : DateTimeFormatter.ISO_INSTANT.format(time.toInstant());
    }

    private static String enumName(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    static Map<String, Object> eqp(final UiEqp value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpKey", value.eqpKey());
        values.put("eqpId", value.eqpId());
        values.put("lineKey", value.lineKey());
        values.put("commInterface", enumName(value.commInterface()));
        values.put("commMode", value.commMode());
        values.put("isDev", value.isDev());
        values.put("routePartition", value.routePartition());
        values.put("eqpIp", value.eqpIp());
        values.put("eqpPort", value.eqpPort());
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("appliedParamVersion", value.appliedParamVersion());
        values.put("gatewayJarKey", value.gatewayJarKey());
        values.put("businessJarKey", value.businessJarKey());
        values.put("enabled", value.enabled());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("createdBy", value.createdBy());
        values.put("updatedBy", value.updatedBy());
        return values;
    }

    static Map<String, Object> model(final UiModel value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("modelKey", value.modelKey());
        values.put("modelName", value.modelName());
        values.put("parentModel", value.parentModel());
        values.put("modelVersion", value.modelVersion());
        values.put("commInterface", enumName(value.commInterface()));
        values.put("status", enumName(value.status()));
        values.put("description", value.description());
        values.put("maker", value.maker());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("createdBy", value.createdBy());
        values.put("updatedBy", value.updatedBy());
        return values;
    }

    static Map<String, Object> parameter(final UiModelParam value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modelParamKey", value.modelParamKey());
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("paramName", value.paramName());
        values.put("paramValue", value.paramValue());
        values.put("description", value.description());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    static Map<String, Object> secs(final UiModelSecsMessage value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("secsMsgKey", value.secsMsgKey());
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("secsMsgName", value.secsMsgName());
        values.put("description", value.description());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    static Map<String, Object> socket(final UiModelSocketMessage value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("socketMsgKey", value.socketMsgKey());
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("socketMsgName", value.socketMsgName());
        values.put("description", value.description());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    static Map<String, Object> mes(final UiModelMesMessage value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mesMsgKey", value.mesMsgKey());
        values.put("modelVersionKey", value.modelVersionKey());
        values.put("mesMsgName", value.mesMsgName());
        values.put("description", value.description());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    static Map<String, Object> lot(final Lot value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workLotKey", value.workLotKey());
        values.put("workKey", value.workKey());
        values.put("carrierId", value.carrierId());
        values.put("lotId", value.lotId());
        values.put("parentLotId", value.parentLotId());
        values.put("chamberId", value.chamberId());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    static Map<String, Object> work(final Work value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workKey", value.workKey());
        values.put("eqpKey", value.eqpKey());
        values.put("workId", value.workId());
        values.put("operatorId", value.operatorId());
        values.put("stepSeq", value.stepSeq());
        values.put("workState", value.workState());
        values.put("startTime", timestamp(value.startTime()));
        values.put("endTime", timestamp(value.endTime()));
        values.put("mesMessage", value.mesMessage());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("lots", value.lots().stream().map(TcAiToolValues::lot).toList());
        return values;
    }

    static Map<String, Object> snapshot(final OperationalSnapshot value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqp", eqp(value.eqp()));
        values.put("runtimeState", runtime(value.runtimeState()));
        values.put("activeWorks", value.activeWorks().stream().map(TcAiToolValues::work).toList());
        values.put("lotStatus", enumName(value.lotStatus()));
        values.put("currentLot", value.currentLot() == null ? null : lot(value.currentLot()));
        return values;
    }

    static Map<String, Object> runtime(final EqpRuntimeStateView value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("controlState", value.controlState());
        values.put("eqpState", value.eqpState());
        values.put("connectionState", value.connectionState());
        values.put("desiredState", value.desiredState());
        return values;
    }
}
