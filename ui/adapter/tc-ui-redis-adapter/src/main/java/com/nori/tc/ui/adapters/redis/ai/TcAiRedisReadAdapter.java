package com.nori.tc.ui.adapters.redis.ai;

import com.nori.tc.db.domain.redis.commlog.RedisCommLogEntry;
import com.nori.tc.db.domain.redis.dlq.RedisBusinessDlqEntry;
import com.nori.tc.db.domain.redis.dlq.RedisDlqEntry;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.BusinessEntry;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.CommLogFact;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.DlqEntry;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.DlqFact;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.GatewayEntry;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.ReadBudget;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.ReadTimeoutException;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.Source;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.SourceReadException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import tools.jackson.databind.ObjectMapper;

/** AI 읽기는 source 전체의 실패를 보존하고 SCAN·연결 수명을 요청 안에 한정합니다. */
@ConditionalOnProperty(name = "nori.ai.integration.enabled", havingValue = "true")
public class TcAiRedisReadAdapter implements TcAiReadPort.RedisReads {
    private static final Logger log = LoggerFactory.getLogger(TcAiRedisReadAdapter.class);
    private final RedisTemplate<String, Object> dlq;
    private final RedisTemplate<String, Object> comm;
    private final ObjectMapper mapper;

    public TcAiRedisReadAdapter(@Qualifier("dlqRedisTemplate") RedisTemplate<String, Object> dlq,
            @Qualifier("commLogRedisTemplate") RedisTemplate<String, Object> comm,
            @Qualifier("uiRedisObjectMapper") ObjectMapper mapper) {
        this.dlq = Objects.requireNonNull(dlq);
        this.comm = Objects.requireNonNull(comm);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<DlqFact> readDlq(Source source, ReadBudget budget) {
        String pattern = source == Source.GATEWAY ? "tc:gateway:dlq:*" : "tc:business:core:dlq:*";
        budget.check();
        try {
            List<DlqFact> result = dlq.execute((RedisCallback<List<DlqFact>>) connection -> {
                List<DlqFact> facts = new ArrayList<>();
                budget.check();
                try (var cursor = connection.keyCommands().scan(
                        ScanOptions.scanOptions().match(pattern).count(100).build())) {
                    while (true) {
                        budget.check();
                        if (!cursor.hasNext()) break;
                        budget.check();
                        byte[] keyBytes = cursor.next();
                        String key = new String(keyBytes, StandardCharsets.UTF_8);
                        try {
                            // 키별 GET은 한 연결 안에서 읽어 순서와 취소 검사 지점을 보존합니다.
                            budget.check();
                            byte[] bytes = connection.stringCommands().get(keyBytes);
                            budget.check();
                            if (bytes == null) continue;
                            Object raw = dlq.getValueSerializer().deserialize(bytes);
                            DlqEntry entry = source == Source.GATEWAY ? gateway(raw) : business(raw);
                            facts.add(new DlqFact(source, key, facts.size(), entry));
                        } catch (ReadTimeoutException timeout) { throw timeout; }
                        catch (RuntimeException failure) { throw sourceFailure(key, failure); }
                    }
                }
                budget.check();
                return facts;
            });
            budget.check();
            return Objects.requireNonNull(result);
        } catch (ReadTimeoutException | SourceReadException failure) { throw failure; }
        catch (RuntimeException failure) { throw sourceFailure(pattern, failure); }
    }

    @Override
    public List<CommLogFact> readCommLogs(Source source, String eqp, Instant from, Instant to, ReadBudget budget) {
        String key = "tc:" + source.value() + ":comm-log:" + eqp;
        budget.check();
        try {
            // score는 정수 epoch 밀리초입니다. 소수 밀리초 경계도 [from,to)로 닫습니다.
            double min = ceilMillis(from);
            double max = ceilMillis(to) - 1;
            var values = comm.opsForZSet().rangeByScore(key, min, max);
            budget.check();
            if (values == null) throw new IllegalStateException("Redis 읽기 결과 없음");
            List<CommLogFact> facts = new ArrayList<>();
            for (Object raw : values) {
                budget.check();
                RedisCommLogEntry entry = convert(raw, RedisCommLogEntry.class);
                facts.add(new CommLogFact(source, key, facts.size(), entry.getTimestampMs(),
                        entry.getDirection(), entry.getMessageName()));
            }
            budget.check();
            return facts;
        } catch (ReadTimeoutException timeout) { throw timeout; }
        catch (RuntimeException failure) { throw sourceFailure(key, failure); }
    }

    private static long ceilMillis(Instant instant) {
        return instant.toEpochMilli() + (instant.getNano() % 1_000_000 == 0 ? 0 : 1);
    }

    private GatewayEntry gateway(Object raw) {
        RedisDlqEntry e = convert(raw, RedisDlqEntry.class);
        return new GatewayEntry(e.getDlqId(), e.getEquipmentId(), e.getTraceId(), e.getCommInterfaceType(),
                e.getSocketType(), e.getStage(), e.getReasonCode(), e.getReasonMessage(), e.getOccurredAt(),
                e.getPayloadRefKey(), e.getRawLen(), e.getB64Len(), e.getTags(), e.getTtlSeconds());
    }

    private BusinessEntry business(Object raw) {
        RedisBusinessDlqEntry e = convert(raw, RedisBusinessDlqEntry.class);
        return new BusinessEntry(e.getDlqId(), e.getSource(), e.getStage(), e.getReasonCode(),
                e.getReasonMessage(), e.getOccurredAt(), e.getTopic(), e.getPartition(), e.getOffset(),
                e.getEqpId(), e.getMessageType(), e.getMessageName(), e.getTraceId(), e.getPayloadRef(),
                e.getTags(), e.getTtlSeconds());
    }

    private <T> T convert(Object raw, Class<T> type) {
        if (type.isInstance(raw)) return type.cast(raw);
        if (raw instanceof Map<?, ?>) return Objects.requireNonNull(mapper.convertValue(raw, type));
        throw new IllegalArgumentException("Redis 자료형 불일치");
    }

    private static SourceReadException sourceFailure(String key, RuntimeException failure) {
        // 예외 메시지와 stack에는 원천 본문이 섞일 수 있으므로 종류만 기록합니다.
        log.warn("TC AI Redis 읽기 실패. key={}, exceptionType={}", key, failure.getClass().getName());
        return new SourceReadException(failure);
    }
}
