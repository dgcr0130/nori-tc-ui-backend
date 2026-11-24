package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.*;
import com.nori.ai.integration.tool.AiToolDispatcher;
import com.nori.ai.integration.tool.AiToolEnvelopeAssembler;
import com.nori.ai.integration.security.AiIdentityService;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterModuleConfiguration;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiReadPort.*;
import com.nori.tc.ui.core.ai.TcAiReadService;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.*;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.eqp.*;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;
import com.nori.tc.ui.domain.model.*;
import com.nori.tc.ui.domain.common.eqp.*;
import com.nori.tc.ui.domain.common.model.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 필드표·항목 단위·상태·공통 외피 절삭을 실제 공개 API로 연결해 확인합니다. */
class TcAiToolConfigurationTest {
    private static final Instant OBSERVED = Instant.parse("2026-11-23T01:00:00Z");
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-23T10:00+09:00");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REGISTRATION_TOOL = "tc.eqp.readRegistration";
    private final TcAiReadPort port = mock(TcAiReadPort.class);
    private final EqpManagementService management = mock(EqpManagementService.class);
    private final TcAiToolConfiguration configuration = new TcAiToolConfiguration(new TcAiReadService(port, mock(TcAiReadPort.RedisReads.class)));

    @Test
    void snapshotHasEveryFieldWithNativeJsonTypesNullsAndNoNestedLimit() {
        var eqp = new UiEqp(1L, "EQP", 2, UiProtocolType.SECS, null, true, 4, null, 5000,
                3, null, null, null, true, TIME, TIME, null, null);
        var lots = IntStream.rangeClosed(1, 101).mapToObj(i -> new Lot(i, 9, null, "LOT" + i, null, null, TIME)).toList();
        var work = new Work(9, 1, null, null, null, "start", TIME, null, null, TIME, TIME, lots);
        when(port.readEquipment("EQP")).thenReturn(Optional.of(new EquipmentFacts(eqp, null, List.of(work))));
        var result = snapshot(Map.of("eqp", "EQP"));
        assertEquals(AiToolResult.Status.CONFLICT, result.status());
        var item = result.items().getFirst();
        assertEquals("EQP", item.ref());
        assertNull(item.time());
        var root = tree(item);
        keys(root, "eqp runtimeState activeWorks lotStatus currentLot");
        keys(root.get("eqp"), "eqpKey eqpId lineKey commInterface commMode isDev routePartition eqpIp eqpPort modelVersionKey appliedParamVersion gatewayJarKey businessJarKey enabled createdAt updatedAt createdBy updatedBy");
        assertEquals("SECS", root.at("/eqp/commInterface").asString());
        assertTrue(root.at("/eqp/eqpKey").isIntegralNumber());
        assertTrue(root.at("/eqp/isDev").isBoolean());
        assertEquals("2026-11-23T01:00:00Z", root.at("/eqp/createdAt").asString());
        assertTrue(root.at("/eqp/commMode").isNull());
        keys(root.get("runtimeState"), "controlState eqpState connectionState desiredState");
        root.get("runtimeState").forEach(value -> assertTrue(value.isNull()));
        var w = root.at("/activeWorks/0");
        keys(w, "workKey eqpKey workId operatorId stepSeq workState startTime endTime mesMessage createdAt updatedAt lots");
        assertTrue(w.get("stepSeq").isNull());
        assertTrue(w.get("endTime").isNull());
        assertEquals(101, w.get("lots").size());
        keys(w.at("/lots/0"), "workLotKey workKey carrierId lotId parentLotId chamberId updatedAt");
        assertEquals(9, w.at("/lots/0/workKey").asLong());
        assertTrue(root.get("currentLot").isNull());
        var response = assemble(configuration.tcAiOperationalSnapshot().toolId(), result);
        assertTrue(item.text().codePointCount(0, item.text().length()) > 4000);
        var envelope = JSON.readTree(response.body());
        assertEquals("CONFLICT", envelope.get("status").asString());
        assertEquals(0, envelope.at("/data/items").size());
        assertEquals(1, envelope.get("missingFields").size());
        assertEquals("truncatedItems", envelope.at("/missingFields/0").asString());
        assertEquals(0, envelope.at("/retentionMeta/missingRanges").size());
    }

    @Test
    void snapshotTextHasTitleRequestedEqpAndOneLabelledFactPerLine() {
        var eqp = new UiEqp(25L, "CLN_001", 2, UiProtocolType.SECS, null, false, 1, "10.0.0.5", 5000,
                3, null, null, null, true, TIME, TIME, null, null);
        when(port.readEquipment("CLN_001")).thenReturn(Optional.of(new EquipmentFacts(eqp,
                new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "DOWN", null, "STARTED"), List.of())));
        var item = snapshot(Map.of("eqp", "CLN_001")).items().getFirst();
        assertEquals(String.join("\n",
                "[설비 운영 스냅샷]",
                "조회 조건 > 설비(eqp): CLN_001",
                "설비 > 설비 키(eqpKey): 25",
                "설비 > 설비 ID(eqpId): CLN_001",
                "설비 > 라인 키(lineKey): 2",
                "설비 > 통신 방식(commInterface): SECS",
                "설비 > 통신 모드(commMode): 없음",
                "설비 > 개발 설비 여부(isDev): false",
                "설비 > 라우팅 파티션(routePartition): 1",
                "설비 > 설비 IP(eqpIp): 10.0.0.5",
                "설비 > 설비 포트(eqpPort): 5000",
                "설비 > 모델 버전 키(modelVersionKey): 3",
                "설비 > 적용 파라미터 버전(appliedParamVersion): 없음",
                "설비 > Gateway JAR 키(gatewayJarKey): 없음",
                "설비 > Business JAR 키(businessJarKey): 없음",
                "설비 > 사용 여부(enabled): true",
                "설비 > 생성 시각(createdAt): 2026-11-23T01:00:00Z",
                "설비 > 수정 시각(updatedAt): 2026-11-23T01:00:00Z",
                "설비 > 생성자(createdBy): 없음",
                "설비 > 수정자(updatedBy): 없음",
                "실시간 상태 > 제어 상태(controlState): OFFLINE",
                "실시간 상태 > 설비 상태(eqpState): DOWN",
                "실시간 상태 > 연결 상태(connectionState): 없음",
                "실시간 상태 > 연결 의도(desiredState): STARTED",
                "진행 작업(activeWorks): 없음",
                "LOT 상태(lotStatus): UNKNOWN",
                "현재 LOT(currentLot): 없음"), item.text());
        assertEquals("DOWN", tree(item).at("/runtimeState/eqpState").asString());
    }

    @Test
    void nestedListElementsAreNumberedAndSourceLineBreaksStayOnOneLine() {
        var eqp = new UiEqp(1L, "EQP", 2, null, null, false, null, null, 0, 3, null, null, null, true, null, null, null, null);
        var lot = new Lot(5, 9, null, "LOT1", null, null, TIME);
        var work = new Work(9, 1, "W1", null, null, "start", TIME, null, "첫 줄\r\n둘째 줄", TIME, TIME, List.of(lot));
        when(port.readEquipment("EQP")).thenReturn(Optional.of(new EquipmentFacts(eqp, null, List.of(work))));
        var item = snapshot(Map.of("eqp", "EQP")).items().getFirst();
        assertThat(item.text().lines().toList()).contains(
                "진행 작업 1: 작업 키 9, 설비 키 1, 작업 ID W1, 작업자 ID 없음, 공정 순번 없음, 작업 상태 start, "
                        + "시작 시각 2026-11-23T01:00:00Z, 종료 시각 없음, MES 메시지 첫 줄 둘째 줄, "
                        + "생성 시각 2026-11-23T01:00:00Z, 수정 시각 2026-11-23T01:00:00Z",
                "진행 작업 1 > LOT 1: 작업 LOT 키 5, 작업 키 9, 캐리어 ID 없음, LOT ID LOT1, 상위 LOT ID 없음, 챔버 ID 없음, "
                        + "수정 시각 2026-11-23T01:00:00Z",
                "LOT 상태(lotStatus): ACTIVE",
                "현재 LOT > LOT ID(lotId): LOT1");
        assertEquals("첫 줄\r\n둘째 줄", tree(item).at("/activeWorks/0/mesMessage").asString());
    }

    @Test
    void labelledTextKeepsAtLeastTheJsonLotCapacityUnderCommonTextLimit() {
        var eqp = new UiEqp(25L, "CLN_001", 2, UiProtocolType.SECS, null, false, 1, "10.0.0.5", 5000,
                3, null, null, null, true, TIME, TIME, null, null);
        var lots = IntStream.rangeClosed(1, 22).mapToObj(i -> new Lot(i, 9, "CARRIER01", "LOT%04d".formatted(i), null, null, TIME)).toList();
        var work = new Work(9, 25, "W9", "OP01", 1, "start", TIME, null, null, TIME, TIME, lots);
        when(port.readEquipment("CLN_001")).thenReturn(Optional.of(new EquipmentFacts(eqp,
                new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "DOWN", null, "STARTED"), List.of(work))));
        var result = snapshot(Map.of("eqp", "CLN_001"));
        var item = result.items().getFirst();
        assertTrue(JSON.writeValueAsString(item.values()).length() <= 4000, "같은 자료의 JSON도 상한 안");
        assertTrue(item.text().codePointCount(0, item.text().length()) <= 4000);
        assertEquals(1, JSON.readTree(assemble("tc.eqp.getOperationalSnapshot", result).body()).at("/data/items").size());
    }

    @Test
    void snapshotStatusesAreCompatibleWithCommonEnvelope() {
        when(port.readEquipment("EQP")).thenReturn(Optional.empty());
        assertEquals("EMPTY", envelope(snapshot(Map.of("eqp", "EQP"))).get("status").asString());
        assertEquals(0, envelope(snapshot(Map.of("eqp", "EQP"))).at("/data/items").size());
        var eqp = new UiEqp(1L, "EQP", 2, null, null, false, null, null, 0, 3, null, null, null, true, null, null, null, null);
        when(port.readEquipment("EQP")).thenReturn(Optional.of(new EquipmentFacts(eqp, null, List.of())));
        var root = envelope(snapshot(Map.of("eqp", "EQP")));
        assertEquals("PARTIAL", root.get("status").asString());
        assertEquals("currentLot", root.at("/missingFields/0").asString());
        assertEquals("UNKNOWN", root.at("/data/items/0/values/lotStatus").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidRequiredEqpHasInputInvalidHttp(final String eqp) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("eqp", eqp);
        var result = snapshot(arguments);
        var response = assemble("tc.eqp.getOperationalSnapshot", result);
        assertEquals(AiToolResult.Status.FAILED, result.status());
        assertEquals(400, response.http());
        assertEquals("INPUT_INVALID", response.code());
        assertTrue(JSON.readTree(response.body()).get("data").isNull());
        verifyNoInteractions(port);
    }

    @Test
    void wrongArgumentTypesUnknownFieldsAndMissingRequiredAreRejected() {
        assertEquals(AiToolResult.Status.FAILED, snapshot(Map.of()).status());
        for (Map<String, Object> arguments : List.of(Map.<String, Object>of("eqp", 1), Map.<String, Object>of("unexpected", "EQP"))) {
            assertEquals(AiToolResult.Status.FAILED, snapshot(arguments).status());
            assertEquals(AiToolResult.Status.FAILED, models(arguments).status());
        }
        verifyNoInteractions(port);
    }

    @Test
    void optionalNullIsOmittedAndNoModelsIsEmpty() {
        var explicitNull = new HashMap<String, Object>();
        explicitNull.put("eqp", null);
        when(port.readModels(null)).thenReturn(List.of());
        assertEquals(models(Map.of()), models(explicitNull));
        assertEquals("EMPTY", envelope(models(explicitNull)).get("status").asString());
    }

    @Test
    void modelAndEachDetailRowKeepSchemaOrderReferenceAndTimestamp() {
        var parameters = List.of(new UiModelParam(8, 3, "P8", null, null, TIME), new UiModelParam(7, 3, null, "value", null, null));
        when(port.readModels("EQP")).thenReturn(List.of(new ModelFacts(model(3, null), parameters,
                List.of(new UiModelSecsMessage(12, 3, null, null, TIME), new UiModelSecsMessage(11, 3, "S1F1", null, null)),
                List.of(new UiModelSocketMessage(13, 3, null, null, TIME)),
                List.of(new UiModelMesMessage(14, 3, null, null, TIME)))));
        var result = models(Map.of("eqp", "EQP"));
        assertEquals(List.of("3", "3:model-parameter:8", "3:model-parameter:7", "3:secs-message:12", "3:secs-message:11",
                "3:socket-message:13", "3:mes-message:14"), result.items().stream().map(AiToolItem::ref).toList());
        var basic = tree(result.items().getFirst());
        keys(basic, "modelVersionKey modelKey modelName parentModel modelVersion commInterface status description maker createdAt updatedAt createdBy updatedBy");
        assertEquals("parent-ref", basic.get("parentModel").asString());
        assertEquals("SOCKET", basic.get("commInterface").asString());
        assertEquals("OPERATE", basic.get("status").asString());
        assertTrue(basic.get("description").isNull());
        assertTrue(result.items().stream().allMatch(item -> item.time() == null));
        assertEquals("2026-11-23T01:00:00Z", basic.get("updatedAt").asString());
        assertEquals(String.join("\n",
                "[모델 파라미터]",
                "조회 조건 > 설비(eqp): EQP",
                "상세 종류(detailType): model-parameter",
                "내용 > 모델 파라미터 키(modelParamKey): 8",
                "내용 > 모델 버전 키(modelVersionKey): 3",
                "내용 > 파라미터 이름(paramName): P8",
                "내용 > 파라미터 값(paramValue): 없음",
                "내용 > 설명(description): 없음",
                "내용 > 수정 시각(updatedAt): 2026-11-23T01:00:00Z"), result.items().get(1).text());
        assertEquals(List.of("[모델 정의]", "[SECS 메시지 정의]", "[SOCKET 메시지 정의]", "[MES 메시지 정의]"),
                List.of(0, 3, 5, 6).stream().map(i -> result.items().get(i).text().lines().findFirst().orElseThrow()).toList());
        assertEquals("조회 조건 > 설비(eqp): EQP", result.items().getFirst().text().lines().skip(1).findFirst().orElseThrow());
        keys(tree(result.items().get(1)), "detailType row");
        keys(tree(result.items().get(1)).get("row"), "modelParamKey modelVersionKey paramName paramValue description updatedAt");
        keys(tree(result.items().get(3)).get("row"), "secsMsgKey modelVersionKey secsMsgName description updatedAt");
        keys(tree(result.items().get(5)).get("row"), "socketMsgKey modelVersionKey socketMsgName description updatedAt");
        keys(tree(result.items().get(6)).get("row"), "mesMsgKey modelVersionKey mesMsgName description updatedAt");
        for (var item : result.items().subList(1, result.items().size())) {
            assertEquals(3, tree(item).at("/row/modelVersionKey").asLong());
            assertTrue(tree(item).at("/row/description").isNull());
        }
        assertEquals(7, envelope(result).at("/data/items").size());
    }

    @Test
    void nullEnumsAndTimeStayNullAndFractionalSecondsStayExact() {
        var value = new UiModel(3, 4, null, null, null, null, null, null, null, null,
                OffsetDateTime.parse("2026-11-23T10:00:00.123456789+09:00"), null, null);
        var root = JSON.valueToTree(TcAiToolValues.model(value));
        assertTrue(root.get("status").isNull());
        assertTrue(root.get("commInterface").isNull());
        assertTrue(root.get("createdAt").isNull());
        assertEquals("2026-11-23T01:00:00.123456789Z", root.get("updatedAt").asString());
        when(port.readModels(null)).thenReturn(List.of(fact(value)));
        assertNull(models(Map.of()).items().getFirst().time());
    }

    @Test
    void emptyDetailsRetainModelAndUnscopedResultsKeepPageOrder() {
        when(port.readModels("EQP")).thenReturn(List.of(fact(model(3, null))));
        assertEquals(List.of("3"), models(Map.of("eqp", "EQP")).items().stream().map(AiToolItem::ref).toList());
        when(port.readModels(null)).thenReturn(List.of(fact(model(9, null)), fact(model(2, null))));
        assertEquals(List.of("9", "2"), models(Map.of()).items().stream().map(AiToolItem::ref).toList());
    }

    @Test
    void detailRowsOverOneHundredUseCommonCountTruncationWithoutBundling() {
        var rows = IntStream.rangeClosed(1, 120).mapToObj(i -> new UiModelParam(i, 3, "P" + i, "가".repeat(60), null, TIME)).toList();
        when(port.readModels("EQP")).thenReturn(List.of(new ModelFacts(model(3, null), rows, List.of(), List.of(), List.of())));
        var result = models(Map.of("eqp", "EQP"));
        assertEquals(121, result.items().size());
        assertTrue(result.items().stream().allMatch(item -> item.text().codePointCount(0, item.text().length()) < 4000));
        var root = envelope(result);
        assertEquals("PARTIAL", root.get("status").asString());
        assertEquals(100, root.at("/data/items").size());
        assertEquals("3:model-parameter:99", root.at("/data/items/99/ref").asString());
        assertEquals("truncatedItems", root.at("/missingFields/0").asString());
        assertEquals(1, root.get("missingFields").size());
        assertEquals(0, root.at("/retentionMeta/missingRanges").size());
    }

    @Test
    void oversizedIndividualTextIsSkippedAndMissingTimeAddsNoRange() {
        var longRow = new UiModelParam(1, 3, "P", "😀".repeat(4001), null, TIME);
        var shortRow = new UiModelParam(2, 3, "P2", "v", null, TIME);
        when(port.readModels("EQP")).thenReturn(List.of(new ModelFacts(model(3, null), List.of(longRow, shortRow), List.of(), List.of(), List.of())));
        var root = envelope(models(Map.of("eqp", "EQP")));
        assertEquals(2, root.at("/data/items").size());
        assertEquals("3:model-parameter:2", root.at("/data/items/1/ref").asString());
        assertEquals(0, root.at("/retentionMeta/missingRanges").size());
    }

    @Test
    void multibyteValuesUseCommonOneMebibyteFinalEnvelopeLimit() {
        when(port.readModels(null)).thenReturn(IntStream.rangeClosed(1, 100)
                .mapToObj(i -> fact(model(i, "가".repeat(3600)))).toList());
        var result = models(Map.of());
        assertTrue(result.items().stream().allMatch(item -> item.text().codePointCount(0, item.text().length()) < 4000));
        var response = assemble("tc.model.read", result);
        var root = JSON.readTree(response.body());
        assertTrue(response.body().length <= 1024 * 1024);
        assertTrue(root.at("/data/items").size() < 100);
        assertEquals("PARTIAL", root.get("status").asString());
        assertEquals(1, root.get("missingFields").size());
        assertEquals("truncatedItems", root.at("/missingFields/0").asString());
        assertEquals(0, root.at("/retentionMeta/missingRanges").size());
    }

    @Test
    void dbExceptionsAndLateResultsUseCommonDispatcherBoundaries() {
        when(port.readModels(null)).thenThrow(new IllegalStateException("시험 저장소 장애"));
        var clock = Clock.fixed(OBSERVED, ZoneOffset.UTC);
        try (var dispatcher = new AiToolDispatcher(clock)) {
            var registration = configuration.tcAiModelRead();
            var failure = assertThrows(AiIdentityService.Failure.class, () -> dispatcher.execute(registration.handler(),
                    OBSERVED.plusSeconds(10), ignored -> call(registration.toolId(), Map.of())));
            assertEquals(503, failure.status());
            assertEquals("SOURCE_UNAVAILABLE", failure.code());
            var response = new AiToolEnvelopeAssembler().failure(identity(registration.toolId()), OBSERVED, failure.status(), failure.code());
            assertTrue(JSON.readTree(response.body()).get("data").isNull());
            var expired = assertThrows(AiIdentityService.Failure.class, () -> dispatcher.execute(registration.handler(),
                    OBSERVED, ignored -> call(registration.toolId(), Map.of())));
            assertEquals(504, expired.status());
        }
    }

    @Test
    void resultCompletedAfterDeadlineCannotBecomeSuccess() {
        when(port.readModels(null)).thenReturn(List.of(fact(model(3, null))));
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(OBSERVED, OBSERVED, OBSERVED.plusSeconds(11));
        try (var dispatcher = new AiToolDispatcher(clock)) {
            var registration = configuration.tcAiModelRead();
            var failure = assertThrows(AiIdentityService.Failure.class, () -> dispatcher.execute(registration.handler(),
                    OBSERVED.plusSeconds(10), ignored -> call(registration.toolId(), Map.of())));
            assertEquals(504, failure.status());
            assertEquals("TIMEOUT", failure.code());
            verify(port).readModels(null);
        }
    }

    @Test
    void explicitWebImportsAndConditionalGraphHaveOneResolverAndFiveRegistrations() {
        assertThat(UiWebAdapterModuleConfiguration.class.getAnnotation(Import.class).value())
                .contains(TcAiIdentityResolver.class, TcAiToolConfiguration.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(TcAiIdentityResolver.class,
                TcAiToolConfiguration.class, TcAiReadService.class);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TcAiReadService.class)
                .doesNotHaveBean(AiRequestIdentityResolver.class).doesNotHaveBean(AiToolRegistration.class));
        runner.withPropertyValues("nori.ai.integration.enabled=true")
                .withBean(EqpManagementService.class, () -> management)
                .withBean(TcAiReadPort.class, () -> port)
                .withBean(TcAiReadPort.RedisReads.class, () -> mock(TcAiReadPort.RedisReads.class))
                .withBean(SessionPort.class, () -> mock(SessionPort.class))
                .withBean(UserPort.class, () -> mock(UserPort.class))
                .withBean(PermissionPort.class, () -> mock(PermissionPort.class))
                .withBean(UiAuthProperties.class, () -> new UiAuthProperties(8, 300, 30, 100, null, null, null, null, null, null, null, List.of()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AiRequestIdentityResolver.class);
                    assertThat(context.getBeansOfType(AiToolRegistration.class).values().stream().map(AiToolRegistration::toolId))
                            .containsExactlyInAnyOrder("tc.eqp.getOperationalSnapshot", "tc.eqp.readRegistration",
                                    "tc.model.read", "tc.dlq.list", "tc.commLogs.search");
                });
    }

    @Test
    void registrationReusesManagementDetailAndIncludesEveryRegistrationSection() {
        var detail = registrationDetail(List.of(registrationParameter(81, "GAS_FLOW", "125 sccm")));
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(detail));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        assertFalse(result.items().isEmpty());
        assertTrue(result.missingFields().isEmpty());
        Map.ofEntries(
                Map.entry("eqpKey", 25L), Map.entry("eqpId", "PHOTO_001"), Map.entry("lineKey", 2L),
                Map.entry("commInterface", "SECS"), Map.entry("commMode", "ACTIVE"), Map.entry("isDev", false),
                Map.entry("routePartition", 1), Map.entry("eqpIp", "10.0.0.25"), Map.entry("eqpPort", 5000),
                Map.entry("modelVersionKey", 3L), Map.entry("appliedParamVersion", "26.11.24.0001"),
                Map.entry("gatewayJarKey", 51L), Map.entry("businessJarKey", 52L), Map.entry("enabled", true),
                Map.entry("modelName", "PHOTO_MODEL"), Map.entry("modelVersion", "v3"),
                Map.entry("deviceId", 101), Map.entry("t3Timeout", 45), Map.entry("t5Timeout", 10),
                Map.entry("t6Timeout", 5), Map.entry("t7Timeout", 10), Map.entry("t8Timeout", 5),
                Map.entry("linkTestEnabled", true), Map.entry("linkTestInterval", 30), Map.entry("maxMsgBytes", 1048576L),
                Map.entry("socketProtocolType", "LINE_BASED"), Map.entry("charset", "UTF-8"),
                Map.entry("heartbeatEnabled", true), Map.entry("heartbeatInterval", 20),
                Map.entry("readTimeout", 31), Map.entry("writeTimeout", 32),
                Map.entry("maxFrameSizeBytes", 65536), Map.entry("keepAliveEnabled", true),
                Map.entry("plcProtocol", "MODBUS_TCP"), Map.entry("transport", "TCP"), Map.entry("unitId", 7),
                Map.entry("pollTimeoutMs", 1500), Map.entry("reconnectMaxRetries", 4),
                Map.entry("enabledPollGroups", List.of("READ_STATUS")), Map.entry("version", 6L),
                Map.entry("logLevel", "DEBUG"), Map.entry("logRetentionDays", 14), Map.entry("logPath", "/logs/photo"),
                Map.entry("eqpParamKey", 81L), Map.entry("paramName", "GAS_FLOW"), Map.entry("paramValue", "125 sccm"),
                Map.entry("paramVersion", "26.11.24.0001"), Map.entry("eqpParamVersionKey", 91L),
                Map.entry("versionDescription", "등록 버전 설명"), Map.entry("portId", "LP01"),
                Map.entry("portType", "LOAD_PORT"), Map.entry("jarFileName", "photo-gateway.jar"))
                .forEach((field, value) -> assertRegistrationValue(result, field, value));
        assertRegistrationValue(result, "jarFileName", "photo-business.jar");
        String text = registrationText(result);
        assertThat(text).contains("PHOTO_001", "PHOTO_MODEL", "101", "LINE_BASED", "MODBUS_TCP", "/logs/photo",
                "GAS_FLOW", "125 sccm", "등록 버전 설명", "LP01", "photo-gateway.jar", "photo-business.jar");
        assertRegistrationEnvelopeKeepsEveryItem(result);
        verify(management).getManageDetail("PHOTO_001");
        verifyNoMoreInteractions(management);
        verifyNoInteractions(port);
    }

    @Test
    void registrationSucceedsWithoutLotsRuntimeStateOrOptionalSettings() {
        var detail = registrationDetail(List.of());
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(new EqpManagementSnapshot(
                detail.eqp(), null, null, null, null, null, null, null, List.of(), List.of(), List.of(), null, null)));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        assertFalse(result.items().isEmpty());
        assertTrue(result.missingFields().isEmpty());
        assertRegistrationValue(result, "eqpId", "PHOTO_001");
        assertRegistrationEnvelopeKeepsEveryItem(result);
        verify(management).getManageDetail("PHOTO_001");
        verifyNoInteractions(port);
    }

    @Test
    void registrationDoesNotExposeRuntimeConnectionOrLotFacts() {
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(registrationDetail(List.of())));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        for (String field : List.of("runtimeState", "connectionState", "controlState", "eqpState", "desiredState",
                "activeWorks", "currentLot", "lotStatus")) {
            assertTrue(registrationValues(result, field).isEmpty(), field);
            assertFalse(registrationText(result).contains("(" + field + ")"), field);
        }
        assertThat(registrationText(result)).doesNotContain("RUNTIME_ONLY_MARKER", "CONNECTION_ONLY_MARKER");
        verifyNoInteractions(port);
    }

    @Test
    void registrationMissingEquipmentIsEmpty() {
        when(management.getManageDetail("MISSING_EQP")).thenReturn(Optional.empty());
        var result = registration(Map.of("eqp", "MISSING_EQP"));
        assertEquals(AiToolResult.Status.EMPTY, result.status());
        assertTrue(result.items().isEmpty());
        var response = assemble(REGISTRATION_TOOL, result);
        assertEquals(200, response.http());
        assertEquals("EMPTY", JSON.readTree(response.body()).get("status").asString());
        assertEquals(0, JSON.readTree(response.body()).at("/data/items").size());
        verify(management).getManageDetail("MISSING_EQP");
        verifyNoInteractions(port);
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrationArguments")
    void registrationRejectsMissingBlankWrongTypeLongAndAdditionalArguments(Map<String, Object> arguments) {
        var result = registration(arguments);
        assertEquals(AiToolResult.Status.FAILED, result.status());
        assertTrue(result.items().isEmpty());
        var response = assemble(REGISTRATION_TOOL, result);
        assertEquals(400, response.http());
        assertEquals("INPUT_INVALID", response.code());
        assertTrue(JSON.readTree(response.body()).get("data").isNull());
        verifyNoInteractions(management, port);
    }

    @Test
    void registrationAcceptsThe64CharacterEqpBoundary() {
        for (String eqp : List.of("E".repeat(64), "설".repeat(64))) {
            var original = registrationDetail(List.of()).eqp();
            var equipment = new UiEqp(original.eqpKey(), eqp, original.lineKey(), original.commInterface(),
                    original.commMode(), original.isDev(), original.routePartition(), original.eqpIp(), original.eqpPort(),
                    original.modelVersionKey(), null, null, null, true, TIME, TIME, null, null);
            when(management.getManageDetail(eqp)).thenReturn(Optional.of(new EqpManagementSnapshot(
                    equipment, null, null, null, null, null, null, null, List.of(), List.of(), List.of(), null, null)));
            var result = registration(Map.of("eqp", eqp));
            assertEquals(AiToolResult.Status.SUCCESS, result.status());
            assertRegistrationValue(result, "eqpId", eqp);
            verify(management).getManageDetail(eqp);
        }
        verifyNoInteractions(port);
    }

    @Test
    void registrationTextUsesKoreanLabelsAndTheRequestedEqpOnEveryItem() {
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(
                registrationDetail(List.of(registrationParameter(81, "GAS_FLOW", "125 sccm")))));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        assertFalse(result.items().isEmpty());
        for (AiToolItem item : result.items()) {
            assertTrue(item.text().lines().findFirst().orElseThrow().matches("\\[.*[가-힣].*]"));
            assertThat(item.text().lines().toList()).contains("조회 조건 > 설비(eqp): PHOTO_001");
        }
        String text = registrationText(result);
        assertThat(text).contains("설비 ID(eqpId): PHOTO_001", "모델 이름(modelName): PHOTO_MODEL",
                "파라미터 이름(paramName): GAS_FLOW", "파라미터 값(paramValue): 125 sccm",
                "설명(description): 파라미터 설명");
        for (String field : List.of("deviceId", "socketProtocolType", "plcProtocol", "logRetentionDays",
                "portId", "paramVersion", "versionDescription", "jarFileName")) {
            assertTrue(text.lines().anyMatch(line -> line.matches(".*[가-힣][^()]*\\(" + field + "\\): .+")), field);
        }
    }

    @Test
    void registrationManyParametersAreSplitWithoutLosingRowsAtTheCommonTextLimit() {
        var parameters = IntStream.rangeClosed(1, 24)
                .mapToObj(i -> registrationParameter(i, "FLOW_%02d".formatted(i), "값%02d-".formatted(i) + "가".repeat(220)))
                .toList();
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(registrationDetail(parameters)));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        assertTrue(registrationText(result).codePointCount(0, registrationText(result).length()) > 4000);
        assertTrue(result.items().size() > 1);
        assertThat(registrationValues(result, "eqpParamKey").stream().map(JsonNode::asLong).toList())
                .containsExactlyInAnyOrderElementsOf(parameters.stream().map(UiEqpParam::eqpParamKey).toList());
        for (var row : parameters) {
            assertRegistrationValue(result, "paramName", row.paramName());
            assertRegistrationValue(result, "paramValue", row.paramValue());
            assertThat(registrationText(result)).contains(row.paramName(), row.paramValue());
        }
        assertRegistrationEnvelopeKeepsEveryItem(result);
    }

    @Test
    void registrationOneLongParameterValueIsSplitWithoutDiscardingItsText() {
        String value = "힣".repeat(2200) + "똠".repeat(2200) + "쀍".repeat(2200);
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(
                registrationDetail(List.of(registrationParameter(81, "LONG_PARAMETER", value)))));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        String text = registrationText(result);
        for (int character : List.of((int) '힣', (int) '똠', (int) '쀍')) {
            assertTrue(text.codePoints().filter(codePoint -> codePoint == character).count() >= 2200,
                    "장문 파라미터 값의 본문 보존");
        }
        assertRegistrationEnvelopeKeepsEveryItem(result);
    }

    @Test
    void registrationOmitsSecretNamedParameterValuesAndJarBinaryPayloads() {
        var secrets = List.of("PASSWORD_ONLY_MARKER", "TOKEN_ONLY_MARKER", "PRIVATE_KEY_ONLY_MARKER",
                "JAR_PRIVATE_CONTENT_MARKER");
        when(management.getManageDetail("PHOTO_001")).thenReturn(Optional.of(registrationDetail(List.of(
                registrationParameter(81, "password", secrets.get(0)),
                registrationParameter(82, "api_token", secrets.get(1)),
                registrationParameter(83, "private_key", secrets.get(2)),
                registrationParameter(84, "GAS_FLOW", "125 sccm")))));
        var result = registration(Map.of("eqp", "PHOTO_001"));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        String output = JSON.writeValueAsString(result.items());
        for (String secret : secrets) {
            assertThat(output).doesNotContain(secret, Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        }
        assertTrue(registrationValues(result, "jarFile").isEmpty());
        assertRegistrationValue(result, "eqpKey", 25L);
        assertRegistrationValue(result, "gatewayJarKey", 51L);
        assertRegistrationValue(result, "businessJarKey", 52L);
        assertRegistrationValue(result, "paramValue", "125 sccm");
        assertThat(registrationText(result)).contains("125 sccm", "photo-gateway.jar", "photo-business.jar");
        String envelope = new String(assemble(REGISTRATION_TOOL, result).body(), StandardCharsets.UTF_8);
        for (String secret : secrets) assertFalse(envelope.contains(secret));
    }

    private AiToolResult registration(Map<String, Object> arguments) {
        var result = new AtomicReference<AiToolResult>();
        new ApplicationContextRunner().withUserConfiguration(TcAiToolConfiguration.class)
                .withPropertyValues("nori.ai.integration.enabled=true")
                .withBean(TcAiReadService.class, () -> new TcAiReadService(port, mock(TcAiReadPort.RedisReads.class)))
                .withBean(EqpManagementService.class, () -> management)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registrations = context.getBeansOfType(AiToolRegistration.class).values();
                    assertThat(registrations.stream().map(AiToolRegistration::toolId).toList()).contains(REGISTRATION_TOOL);
                    var registration = registrations.stream().filter(tool -> tool.toolId().equals(REGISTRATION_TOOL))
                            .findFirst().orElseThrow();
                    var now = Instant.now();
                    result.set(registration.handler().handle(new AiToolCall("TC", "fixture-job", REGISTRATION_TOOL,
                            "7", Set.of("EQP_READ"), arguments, List.of(), now.plusSeconds(10), now)));
                });
        return result.get();
    }

    private static java.util.stream.Stream<Map<String, Object>> invalidRegistrationArguments() {
        Map<String, Object> explicitNull = new HashMap<>();
        explicitNull.put("eqp", null);
        return java.util.stream.Stream.of(Map.<String, Object>of(), explicitNull,
                Map.of("eqp", ""), Map.of("eqp", " \t\n"), Map.of("eqp", "E".repeat(65)),
                Map.of("eqp", "설".repeat(65)), Map.of("eqp", 25), Map.of("eqp", true),
                Map.of("eqp", List.of("PHOTO_001")), Map.of("eqp", Map.of("id", "PHOTO_001")),
                Map.of("unexpected", "PHOTO_001"), Map.of("eqp", "PHOTO_001", "unexpected", "value"),
                Map.of("eqp", "PHOTO_001", "from", OBSERVED.toString()));
    }

    private static EqpManagementSnapshot registrationDetail(List<UiEqpParam> parameters) {
        var eqp = new UiEqp(25L, "PHOTO_001", 2, UiProtocolType.SECS, "ACTIVE", false, 1, "10.0.0.25", 5000,
                3, "26.11.24.0001", 51L, 52L, true, TIME, TIME, "fixture-creator", "fixture-updater");
        var model = new UiModel(3, 4, "PHOTO_MODEL", "parent-ref", "v3", UiProtocolType.SECS,
                UiModelStatus.OPERATE, "모델 설명", "fixture-maker", TIME, TIME, "fixture-creator", "fixture-updater");
        byte[] jar = "JAR_PRIVATE_CONTENT_MARKER".getBytes(StandardCharsets.UTF_8);
        return new EqpManagementSnapshot(eqp, model,
                new UiEqpHsms(25, 101, 45, 10, 5, 10, 5, true, 30, 1048576L, TIME, TIME),
                new UiEqpSocket(25, "LINE_BASED", "UTF-8", true, 20, 31, 32, 65536, true, TIME, TIME),
                new UiEqpPlc(25, "MODBUS_TCP", "TCP", 7, 1500, 4, List.of("READ_STATUS"), 6, TIME, TIME),
                new UiEqpLogPolicy(25, UiLogLevel.DEBUG, 14, "/logs/photo", TIME),
                new UiEqpRuntimeState(25, UiControlState.REMOTE, UiEqpState.RUN, "RUNTIME_ONLY_MARKER", TIME, null, null, TIME),
                "CONNECTION_ONLY_MARKER",
                List.of(new UiEqpPortStatus(71, 25, "LP01", UiPortType.LOAD_PORT, "READY", null, null, null, TIME)),
                parameters, List.of(new UiEqpParamVersion(91, 25, "26.11.24.0001", "등록 버전 설명", TIME, TIME, "fixture-creator", "fixture-updater")),
                new UiGatewayJar(51, "photo-gateway.jar", jar, TIME, TIME, "fixture-creator", "fixture-updater"),
                new UiBusinessJar(52, "photo-business.jar", jar, TIME, TIME, "fixture-creator", "fixture-updater"));
    }

    private static UiEqpParam registrationParameter(long key, String name, String value) {
        return new UiEqpParam(key, 25, name, "26.11.24.0001", value, "파라미터 설명", "fixture-creator", TIME);
    }

    private static String registrationText(AiToolResult result) {
        return String.join("\n", result.items().stream().map(AiToolItem::text).toList());
    }

    private static List<JsonNode> registrationValues(AiToolResult result, String field) {
        var values = new ArrayList<JsonNode>();
        result.items().forEach(item -> collectRegistrationValues(tree(item), field, values));
        return values;
    }

    private static void collectRegistrationValues(JsonNode node, String field, List<JsonNode> values) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals(field)) values.add(entry.getValue());
                collectRegistrationValues(entry.getValue(), field, values);
            });
        } else if (node.isArray()) node.forEach(child -> collectRegistrationValues(child, field, values));
    }

    private static void assertRegistrationValue(AiToolResult result, String field, Object value) {
        JsonNode expected = JSON.valueToTree(value);
        assertTrue(registrationValues(result, field).stream().anyMatch(actual -> expected.isIntegralNumber()
                ? actual.isIntegralNumber() && actual.asLong() == expected.asLong() : expected.equals(actual)), field);
    }

    private static void assertRegistrationEnvelopeKeepsEveryItem(AiToolResult result) {
        assertFalse(result.items().isEmpty());
        assertTrue(result.items().stream().allMatch(item -> item.text().codePointCount(0, item.text().length()) <= 4000));
        var response = assemble(REGISTRATION_TOOL, result);
        assertEquals(200, response.http());
        var root = JSON.readTree(response.body());
        assertEquals("SUCCESS", root.get("status").asString());
        assertEquals(result.items().size(), root.at("/data/items").size());
        assertEquals(0, root.get("missingFields").size());
        for (int index = 0; index < result.items().size(); index++) {
            assertEquals(result.items().get(index).ref(), root.at("/data/items/" + index + "/ref").asString());
            assertEquals(result.items().get(index).text(), root.at("/data/items/" + index + "/text").asString());
        }
    }

    private AiToolResult snapshot(Map<String, Object> arguments) {
        return configuration.tcAiOperationalSnapshot().handler().handle(call("tc.eqp.getOperationalSnapshot", arguments));
    }
    private AiToolResult models(Map<String, Object> arguments) {
        return configuration.tcAiModelRead().handler().handle(call("tc.model.read", arguments));
    }
    private static AiToolCall call(String tool, Map<String, Object> arguments) {
        return new AiToolCall("TC", "fixture-job", tool, "7", Set.of("EQP_READ", "MODEL_READ"), arguments,
                List.of(new AiToolCall.Target("eqp", "unrelated-reference")), OBSERVED.plusSeconds(10), OBSERVED);
    }
    private static AiToolEnvelopeAssembler.Identity identity(String tool) {
        return new AiToolEnvelopeAssembler.Identity("1", "TC", "fixture-job", tool);
    }
    private static AiToolEnvelopeAssembler.Response assemble(String tool, AiToolResult result) {
        return new AiToolEnvelopeAssembler().assemble(identity(tool), OBSERVED, result);
    }
    private static JsonNode envelope(AiToolResult result) {
        return JSON.readTree(assemble("tc.model.read", result).body());
    }
    private static JsonNode tree(AiToolItem item) {
        return JSON.valueToTree(item.values());
    }
    private static void keys(JsonNode node, String expected) {
        var actual = new HashSet<String>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        assertEquals(Set.of(expected.split(" ")), actual);
    }
    private static UiModel model(long key, String description) {
        return new UiModel(key, 4, "fixture-model", "parent-ref", "v1", UiProtocolType.SOCKET, UiModelStatus.OPERATE,
                description, null, TIME, TIME, null, null);
    }
    private static ModelFacts fact(UiModel model) {
        return new ModelFacts(model, List.of(), List.of(), List.of(), List.of());
    }
}
