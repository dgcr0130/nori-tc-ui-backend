package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiToolCall;
import com.nori.ai.integration.api.AiToolItem;
import com.nori.ai.integration.api.AiToolRegistration;
import com.nori.ai.integration.api.AiToolResult;
import com.nori.tc.ui.core.ai.TcAiReadService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** TC의 DB 읽기 두 도구만 공통 처리기 등록 형식으로 연결합니다. */
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
    public AiToolRegistration tcAiModelRead() {
        return AiToolRegistration.of("tc.model.read", this::models);
    }

    private AiToolResult snapshot(final AiToolCall call) {
        if (!validEqp(call) || call.arguments().get("eqp") == null) return invalidInput();
        String eqp = (String) call.arguments().get("eqp");
        var result = reads.readOperationalSnapshot(eqp);
        var items = result.snapshot() == null ? List.<AiToolItem>of()
                : List.of(TcAiToolValues.item(eqp, TcAiToolValues.snapshot(result.snapshot())));
        return result(AiToolResult.Status.valueOf(result.status().name()), items, result.missingFields());
    }

    private AiToolResult models(final AiToolCall call) {
        if (!validEqp(call)) return invalidInput();
        var facts = reads.readModels((String) call.arguments().get("eqp"));
        List<AiToolItem> items = new ArrayList<>();
        for (var fact : facts) {
            var model = fact.model();
            String ref = Long.toString(model.modelVersionKey());
            items.add(TcAiToolValues.item(ref, TcAiToolValues.model(model)));
            fact.parameters().forEach(row -> items.add(TcAiToolValues.detail(ref, "model-parameter",
                    row.modelParamKey(), TcAiToolValues.parameter(row))));
            fact.secsMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "secs-message",
                    row.secsMsgKey(), TcAiToolValues.secs(row))));
            fact.socketMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "socket-message",
                    row.socketMsgKey(), TcAiToolValues.socket(row))));
            fact.mesMessages().forEach(row -> items.add(TcAiToolValues.detail(ref, "mes-message",
                    row.mesMsgKey(), TcAiToolValues.mes(row))));
        }
        return result(items.isEmpty() ? AiToolResult.Status.EMPTY : AiToolResult.Status.SUCCESS, items, List.of());
    }

    private static boolean validEqp(final AiToolCall call) {
        if (call.arguments().keySet().stream().anyMatch(key -> !"eqp".equals(key))) return false;
        Object value = call.arguments().get("eqp");
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
