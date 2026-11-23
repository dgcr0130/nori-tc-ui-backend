package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.*;
import com.nori.ai.integration.tool.AiToolDispatcher;
import com.nori.ai.integration.tool.AiToolEnvelopeAssembler;
import com.nori.ai.integration.security.AiIdentityService;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterModuleConfiguration;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiReadPort.*;
import com.nori.tc.ui.core.ai.TcAiReadService;
import com.nori.tc.ui.core.port.db.*;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.model.*;
import com.nori.tc.ui.domain.common.model.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
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
    private final TcAiReadPort port = mock(TcAiReadPort.class);
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
        assertEquals("{\"detailType\":\"model-parameter\",\"row\":{\"modelParamKey\":8,\"modelVersionKey\":3,"
                + "\"paramName\":\"P8\",\"paramValue\":null,\"description\":null,\"updatedAt\":\"2026-11-23T01:00:00Z\"}}",
                result.items().get(1).text());
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
    void explicitWebImportsAndConditionalGraphHaveOneResolverAndFourRegistrations() {
        assertThat(UiWebAdapterModuleConfiguration.class.getAnnotation(Import.class).value())
                .contains(TcAiIdentityResolver.class, TcAiToolConfiguration.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(TcAiIdentityResolver.class,
                TcAiToolConfiguration.class, TcAiReadService.class);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TcAiReadService.class)
                .doesNotHaveBean(AiRequestIdentityResolver.class).doesNotHaveBean(AiToolRegistration.class));
        runner.withPropertyValues("nori.ai.integration.enabled=true")
                .withBean(TcAiReadPort.class, () -> port)
                .withBean(TcAiReadPort.RedisReads.class, () -> mock(TcAiReadPort.RedisReads.class))
                .withBean(SessionPort.class, () -> mock(SessionPort.class))
                .withBean(UserPort.class, () -> mock(UserPort.class))
                .withBean(PermissionPort.class, () -> mock(PermissionPort.class))
                .withBean(UiAuthProperties.class, () -> new UiAuthProperties(8, 300, 30, 100, null, null, null, null, null, null, null, List.of()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AiRequestIdentityResolver.class);
                    assertThat(context.getBeansOfType(AiToolRegistration.class).values().stream().map(AiToolRegistration::toolId))
                            .containsExactlyInAnyOrder("tc.eqp.getOperationalSnapshot", "tc.model.read", "tc.dlq.list", "tc.commLogs.search");
                });
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
        assertEquals(JSON.writeValueAsString(item.values()), item.text());
        return JSON.readTree(item.text());
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
