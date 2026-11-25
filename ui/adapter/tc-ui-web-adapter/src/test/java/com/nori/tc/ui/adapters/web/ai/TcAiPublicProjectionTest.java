package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiToolItem;
import com.nori.tc.ui.core.ai.TcAiReadPort.*;
import com.nori.tc.ui.core.ai.TcAiReadService.*;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.eqp.*;
import com.nori.tc.ui.domain.jar.*;
import com.nori.tc.ui.domain.model.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 공개 본문과 공개 values가 같은 업무 사실을 사용하고 내부 조인 키를 내보내지 않는지 확인합니다. */
class TcAiPublicProjectionTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-23T10:00+09:00");
    private static final Set<String> INTERNAL = Set.of("eqpKey", "lineKey", "modelKey", "modelVersionKey",
            "gatewayJarKey", "businessJarKey", "eqpParamKey", "eqpParamVersionKey", "eqpPortStatusKey",
            "modelParamKey", "secsMsgKey", "socketMsgKey", "mesMsgKey", "workKey", "workLotKey");

    @Test void registrationExcludesDatabaseKeysFromBothPublicRepresentations() {
        var items = registration();
        items.forEach(TcAiPublicProjectionTest::publicOnly);
        assertThat(text(items)).contains("ETC_001", "ETC_MODEL", "v77", "params-v9", "6100", "LINE_A", "A line");
    }

    @Test void actualDeprecatedRegisteredVersionAndParameterVersionRemainDistinct() {
        var detail = detail();
        var items = registration();
        assertThat(detail.eqp().modelVersionKey()).isEqualTo(77);
        assertThat(detail.model().modelVersionKey()).isEqualTo(77);
        assertThat(text(items)).contains("모델 버전(modelVersion): v77", "적용 파라미터 버전(appliedParamVersion): params-v9", "DEPRECATED");
        assertThat(text(items)).doesNotContain("modelVersionKey", "modelKey");
    }

    @Test void nestedWorkLotAndCurrentLotPreserveBusinessIdsAndNumbers() {
        var lot = new Lot(9902, 9901, "CARRIER-1", "LOT-1", "PARENT-1", "CHAMBER-1", TIME);
        var work = new Work(9901, 181, "WORK-1", "OPERATOR-1", 42, "start", TIME, null,
                "MESSAGE-1", TIME, TIME, List.of(lot));
        var value = TcAiToolValues.snapshot(new OperationalSnapshot(detail().eqp(),
                new EqpRuntimeStateView("REMOTE", "RUN", "OFFLINE", "STARTED"), List.of(work), LotStatus.ACTIVE, lot));
        var item = TcAiToolValues.item("ETC_001", TcAiToolValues.SNAPSHOT_TITLE, Map.of("eqp", "ETC_001"), value);
        publicOnly(item);
        assertThat(item.text()).contains("WORK-1", "LOT-1", "CARRIER-1", "CHAMBER-1", "MESSAGE-1", "42", "6100");
        assertThat(detail().eqp().eqpKey()).isEqualTo(181);
        assertThat(work.workKey()).isEqualTo(9901);
        assertThat(lot.workLotKey()).isEqualTo(9902);
    }

    @Test void modelParametersAndMessagesRetainNamesWithoutRowKeys() {
        var rows = List.of(TcAiToolValues.model(detail().model()),
                TcAiToolValues.parameter(new UiModelParam(8001, 77, "FLOW", "125", "업무 값", TIME)),
                TcAiToolValues.secs(new UiModelSecsMessage(8002, 77, "S1F1", "SECS", TIME)),
                TcAiToolValues.socket(new UiModelSocketMessage(8003, 77, "DEVICE_STATUS", "SOCKET", TIME)),
                TcAiToolValues.mes(new UiModelMesMessage(8004, 77, "MES_EVENT", "MES", TIME)));
        rows.forEach(row -> publicOnly(TcAiToolValues.item("internal-ref", "모델", Map.of(), row)));
        assertThat(rows.toString()).contains("FLOW", "125", "S1F1", "DEVICE_STATUS", "MES_EVENT", "v77");
    }

    @Test void parameterPortJarProjectionKeepsBusinessDataAndMasksSecrets() {
        var items = registration();
        items.forEach(TcAiPublicProjectionTest::publicOnly);
        assertThat(text(items)).contains("LP01", "CARRIER-1", "GAS_FLOW", "125 sccm", "gateway.jar", "business.jar", "비밀 파라미터");
        assertThat(text(items)).doesNotContain("PRIVATE_VALUE", "PRIVATE_JAR_BYTES");
        assertThat(items.stream().map(AiToolItem::values).toList().toString()).doesNotContain("PRIVATE_VALUE", "PRIVATE_JAR_BYTES");
        assertThat(detail().params().getFirst().eqpParamKey()).isEqualTo(8101);
        assertThat(detail().gatewayJar().gatewayJarKey()).isEqualTo(9101);
    }

    private static void publicOnly(AiToolItem item) {
        noKeys(item.values());
        INTERNAL.forEach(key -> assertThat(item.text()).doesNotContain("(" + key + ")"));
    }
    private static void noKeys(Object value) {
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> {
            assertThat(INTERNAL).doesNotContain((String) key);
            noKeys(child);
        });
        else if (value instanceof List<?> list) list.forEach(TcAiPublicProjectionTest::noKeys);
    }
    private static List<AiToolItem> registration() {
        return TcAiToolValues.registration("ETC_001", detail(), Map.of("eqp", "ETC_001"),
                new RegistrationLine(LineStatus.RESOLVED, new LineFacts("LINE_A", "A line", false)));
    }
    private static String text(List<AiToolItem> items) { return String.join("\n", items.stream().map(AiToolItem::text).toList()); }
    private static EqpManagementSnapshot detail() {
        var eqp = new UiEqp(181L, "ETC_001", 1, UiProtocolType.SECS, "ACTIVE", false, 1, "10.0.0.1", 6100,
                77, "params-v9", 9101L, 9102L, true, TIME, TIME, null, null);
        var model = new UiModel(77, 7001, "ETC_MODEL", null, "v77", UiProtocolType.SECS,
                UiModelStatus.DEPRECATED, null, null, TIME, TIME, null, null);
        var params = List.of(new UiEqpParam(8101, 181, "GAS_FLOW", "params-v9", "125 sccm", null, null, TIME),
                new UiEqpParam(8102, 181, "password", "params-v9", "PRIVATE_VALUE", null, null, TIME));
        return new EqpManagementSnapshot(eqp, model,
                new UiEqpHsms(181, 101, 45, 10, 5, 10, 5, true, 30, 1048576L, TIME, TIME),
                null, null, null, null, null,
                List.of(new UiEqpPortStatus(8201, 181, "LP01", null, "READY", "CARRIER-1", null, null, TIME)),
                params, List.of(new UiEqpParamVersion(8301, 181, "params-v9", "버전 설명", TIME, TIME, null, null)),
                new UiGatewayJar(9101, "gateway.jar", "PRIVATE_JAR_BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8), TIME, TIME, null, null),
                new UiBusinessJar(9102, "business.jar", new byte[0], TIME, TIME, null, null));
    }
}
