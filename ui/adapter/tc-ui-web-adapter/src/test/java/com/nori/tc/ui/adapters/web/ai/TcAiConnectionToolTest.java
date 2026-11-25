package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.*;
import com.nori.ai.integration.tool.AiToolDispatcher;
import com.nori.ai.integration.security.AiIdentityService;
import com.nori.tc.ui.core.ai.*;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TcAiConnectionToolTest {
    final TcAiReadPort port = mock(TcAiReadPort.class);
    final AiToolRegistration tool = new TcAiToolConfiguration(new TcAiReadService(port,
            mock(TcAiReadPort.RedisReads.class))).tcAiConnectionState();
    AiToolCall call(Map<String, Object> args, Instant deadline) {
        return new AiToolCall("TC", "fixture", tool.toolId(), "7", Set.of("EQP_READ"), args,
                List.of(new AiToolCall.Target("EQP", "ETC_001")), deadline, Instant.now());
    }
    @Test void validConnectionPublicValuesAndTextDoNotDependOnLots() {
        when(port.readConnection("ETC_001")).thenReturn(Optional.of(new EqpRuntimeStateView("REMOTE", "RUN", "OFFLINE", "STARTED")));
        var result = tool.handler().handle(call(Map.of("eqp", "ETC_001"), Instant.now().plusSeconds(10)));
        assertEquals(AiToolResult.Status.SUCCESS, result.status());
        assertEquals(List.of(), result.missingFields());
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.text()).contains("ETC_001", "OFFLINE", "관측됨").doesNotContain("currentLot", "workKey", "eqpKey");
            assertThat(item.values()).containsEntry("eqpId", "ETC_001").doesNotContainKeys("eqpKey", "currentLot");
        });
        verify(port).readConnection("ETC_001");
        verifyNoMoreInteractions(port);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "UNKNOWN"})
    void absentConnectionIsPartialAndExplicitlyUnconfirmed(String value) {
        when(port.readConnection("ETC_001")).thenReturn(Optional.of(new EqpRuntimeStateView(null, null, value, null)));
        var result = tool.handler().handle(call(Map.of("eqp", "ETC_001"), Instant.now().plusSeconds(10)));
        assertEquals(AiToolResult.Status.PARTIAL, result.status());
        assertEquals(List.of("connectionState"), result.missingFields());
        assertThat(result.items().getFirst().text()).contains("확인 불가").doesNotContain("OFFLINE");
    }
    @Test void missingEquipmentInputAndExpiredDeadlineRemainDistinctAndDoNotBroadenReads() {
        assertEquals(AiToolResult.Status.EMPTY, tool.handler().handle(call(Map.of("eqp", "MISSING"), Instant.now().plusSeconds(10))).status());
        clearInvocations(port);
        assertEquals(AiToolResult.Status.FAILED, tool.handler().handle(call(Map.of(), Instant.now().plusSeconds(10))).status());
        assertEquals(AiToolResult.Status.FAILED, tool.handler().handle(call(Map.of("eqp", "ETC_001", "lineKey", 1), Instant.now().plusSeconds(10))).status());
        assertEquals(AiToolResult.Status.TIMEOUT, tool.handler().handle(call(Map.of("eqp", "ETC_001"), Instant.EPOCH)).status());
        verifyNoInteractions(port);
    }
    @Test void sourceFailureAndTimeoutUseExistingDispatcherContract() {
        var now = Instant.now();
        when(port.readConnection("ETC_001")).thenThrow(new IllegalStateException("시험 장애"));
        try (var dispatcher = new AiToolDispatcher(Clock.fixed(now, ZoneOffset.UTC))) {
            var failure = assertThrows(AiIdentityService.Failure.class, () -> dispatcher.execute(tool.handler(),
                    now.plusSeconds(10), ignored -> call(Map.of("eqp", "ETC_001"), now.plusSeconds(10))));
            assertEquals("SOURCE_UNAVAILABLE", failure.code());
            var expired = assertThrows(AiIdentityService.Failure.class, () -> dispatcher.execute(tool.handler(), now,
                    ignored -> call(Map.of("eqp", "ETC_001"), now)));
            assertEquals("TIMEOUT", expired.code());
        }
    }
}
