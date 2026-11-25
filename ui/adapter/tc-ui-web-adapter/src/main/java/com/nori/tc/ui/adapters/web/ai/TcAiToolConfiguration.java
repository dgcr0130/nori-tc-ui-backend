package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiToolCall;
import com.nori.ai.integration.api.AiToolItem;
import com.nori.ai.integration.api.AiToolRegistration;
import com.nori.ai.integration.api.AiToolResult;
import com.nori.tc.ui.core.ai.TcAiReadService;
import com.nori.tc.ui.core.service.EqpManagementService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** TC의 DB·Redis 읽기 도구를 공통 처리기 등록 형식으로 연결합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nori.ai.integration.enabled", havingValue = "true")
public class TcAiToolConfiguration {
    private final TcAiReadService reads;

    public TcAiToolConfiguration(final TcAiReadService reads) {
        this.reads = reads;
    }

    @Bean
    public AiToolRegistration tcAiOperationalSnapshot() {
        return AiToolRegistration.of("tc.eqp.getOperationalSnapshot", this::snapshot);
    }

    @Bean
    public AiToolRegistration tcAiConnectionState() {
        return AiToolRegistration.of("tc.eqp.getConnectionState", this::connection);
    }

    @Bean
    public AiToolRegistration tcAiModelRead() {
        return AiToolRegistration.of("tc.model.read", this::models);
    }

    @Bean
    public AiToolRegistration tcAiDlqList() {
        return AiToolRegistration.of("tc.dlq.list", this::dlq);
    }

    @Bean
    public AiToolRegistration tcAiCommLogsSearch() {
        return AiToolRegistration.of("tc.commLogs.search", this::commLogs);
    }

    @Bean
    public AiToolRegistration tcAiEqpRegistration(final EqpManagementService management) {
        return AiToolRegistration.of("tc.eqp.readRegistration", call -> registration(call, management));
    }

    private AiToolResult registration(final AiToolCall call, final EqpManagementService management) {
        if (!validEqp(call) || call.arguments().get("eqp") == null) return invalidInput();
        String eqp = (String) call.arguments().get("eqp");
        var detail = management.getManageDetail(eqp);
        var items = detail.map(snapshot -> TcAiToolValues.registration(eqp, snapshot,
                TcAiToolValues.conditions(call.arguments()), reads.readRegistrationLine(snapshot.eqp()))).orElseGet(List::of);
        return result(detail.isPresent() ? AiToolResult.Status.SUCCESS : AiToolResult.Status.EMPTY, items, List.of());
    }

    private AiToolResult dlq(final AiToolCall call) {
        final RedisInput input;
        try { input = redisInput(call); }
        catch (IllegalArgumentException invalid) { return invalidInput(); }
        var facts = reads.readDlq(input.eqp(), input.from(), input.to(), call.deadline());
        var conditions = TcAiToolValues.conditions(call.arguments());
        var items = facts.items().stream().map(fact -> TcAiToolValues.timedItem(fact.ref(),
                TcAiToolValues.DLQ_TITLE, conditions, TcAiToolValues.dlq(fact), fact.time())).toList();
        return beforeDeadline(call, result(AiToolResult.Status.valueOf(facts.status().name()),
                items, facts.missingFields()));
    }

    private AiToolResult commLogs(final AiToolCall call) {
        final RedisInput input;
        try { input = redisInput(call); }
        catch (IllegalArgumentException invalid) { return invalidInput(); }
        if (input.eqp() == null || input.from() == null) return invalidInput();
        var facts = reads.readCommLogs(input.eqp(), input.from(), input.to(), call.deadline());
        var conditions = TcAiToolValues.conditions(call.arguments());
        var items = facts.items().stream().map(fact -> TcAiToolValues.timedItem(fact.ref(),
                TcAiToolValues.COMM_LOG_TITLE, conditions, TcAiToolValues.commLog(fact), fact.time())).toList();
        var status = AiToolResult.Status.valueOf(facts.status().name());
        var result = status == AiToolResult.Status.PARTIAL
                ? new AiToolResult(status, items, facts.missingFields(),
                        List.of(AiToolResult.Warning.RETENTION_UNVERIFIED),
                        new AiToolResult.RetentionMeta(List.of(),
                                List.of(new AiToolResult.TimeRange(input.from(), input.to()))))
                : result(status, items, facts.missingFields());
        return beforeDeadline(call, result);
    }

    private static AiToolResult beforeDeadline(AiToolCall call, AiToolResult result) {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(call.deadline())) {
            return result(AiToolResult.Status.TIMEOUT, List.of(), List.of());
        }
        return result;
    }

    private static RedisInput redisInput(AiToolCall call) {
        if (!Set.of("eqp", "from", "to").containsAll(call.arguments().keySet())) {
            throw new IllegalArgumentException("알 수 없는 입력");
        }
        Object eqp = call.arguments().get("eqp");
        if (!validEqpValue(eqp)) throw new IllegalArgumentException("설비 입력 오류");
        Instant from = utc(call.arguments().get("from"));
        Instant to = utc(call.arguments().get("to"));
        if ((from == null) != (to == null) || from != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("기간 입력 오류");
        }
        return new RedisInput((String) eqp, from, to);
    }

    private static Instant utc(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)
                || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")) {
            throw new IllegalArgumentException("UTC 시각 입력 오류");
        }
        try { return Instant.parse(text); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("UTC 시각 입력 오류", invalid); }
    }

    private record RedisInput(String eqp, Instant from, Instant to) {}

    private AiToolResult snapshot(final AiToolCall call) {
        if (!validEqp(call) || call.arguments().get("eqp") == null) return invalidInput();
        String eqp = (String) call.arguments().get("eqp");
        var result = reads.readOperationalSnapshot(eqp);
        var items = result.snapshot() == null ? List.<AiToolItem>of()
                : List.of(TcAiToolValues.item(eqp, TcAiToolValues.SNAPSHOT_TITLE,
                        TcAiToolValues.conditions(call.arguments()), TcAiToolValues.snapshot(result.snapshot())));
        return result(AiToolResult.Status.valueOf(result.status().name()), items, result.missingFields());
    }

    private AiToolResult connection(final AiToolCall call) {
        if (!validEqp(call) || call.arguments().get("eqp") == null) return invalidInput();
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(call.deadline())) {
            return result(AiToolResult.Status.TIMEOUT, List.of(), List.of());
        }
        String eqp = (String) call.arguments().get("eqp");
        var connection = reads.readConnectionState(eqp);
        var items = connection.runtimeState() == null ? List.<AiToolItem>of()
                : List.of(TcAiToolValues.item(eqp, TcAiToolValues.CONNECTION_TITLE,
                        TcAiToolValues.conditions(call.arguments()), TcAiToolValues.connection(eqp, connection)));
        return beforeDeadline(call, result(AiToolResult.Status.valueOf(connection.status().name()), items,
                connection.missingFields()));
    }

    private AiToolResult models(final AiToolCall call) {
        if (!validEqp(call)) return invalidInput();
        var facts = reads.readModels((String) call.arguments().get("eqp"));
        var conditions = TcAiToolValues.conditions(call.arguments());
        List<AiToolItem> items = new ArrayList<>();
        for (var fact : facts) {
            var model = fact.model();
            String ref = Long.toString(model.modelVersionKey());
            items.add(TcAiToolValues.item(ref, TcAiToolValues.MODEL_TITLE, conditions, TcAiToolValues.model(model)));
            fact.parameters().forEach(row -> items.add(TcAiToolValues.detail(ref, "model-parameter",
                    row.modelParamKey(), TcAiToolValues.parameter(row), conditions)));
            fact.secsMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "secs-message",
                    row.secsMsgKey(), TcAiToolValues.secs(row), conditions)));
            fact.socketMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "socket-message",
                    row.socketMsgKey(), TcAiToolValues.socket(row), conditions)));
            fact.mesMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "mes-message",
                    row.mesMsgKey(), TcAiToolValues.mes(row), conditions)));
        }
        return result(items.isEmpty() ? AiToolResult.Status.EMPTY : AiToolResult.Status.SUCCESS, items, List.of());
    }

    private static boolean validEqp(final AiToolCall call) {
        if (call.arguments().keySet().stream().anyMatch(key -> !"eqp".equals(key))) return false;
        Object value = call.arguments().get("eqp");
        return validEqpValue(value);
    }

    private static boolean validEqpValue(Object value) {
        return value == null || value instanceof String text && !text.isBlank()
                && text.codePointCount(0, text.length()) <= 64;
    }

    private static AiToolResult invalidInput() {
        return result(AiToolResult.Status.FAILED, List.of(), List.of());
    }

    private static AiToolResult result(final AiToolResult.Status status, final List<AiToolItem> items,
                                       final List<String> missing) {
        return new AiToolResult(status, items, missing, List.of(), new AiToolResult.RetentionMeta(List.of(), List.of()));
    }
}
