package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiToolItem;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpHsms;
import com.nori.tc.ui.domain.eqp.UiEqpLogPolicy;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpParamVersion;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.core.ai.TcAiReadPort.Lot;
import com.nori.tc.ui.core.ai.TcAiReadPort.Work;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.BusinessEntry;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.CommLogFact;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.DlqFact;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.GatewayEntry;
import com.nori.tc.ui.core.ai.TcAiReadService.OperationalSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 원천 null과 필드 자료형을 보존하며 JSON 값과 RFC3339 UTC 시각으로 옮깁니다.
 * text는 Gateway가 질문의 식별자·명사를 대조하고 줄 단위로 인용하는 본문이므로 "[제목]" 한 줄, 조회 조건,
 * 필드마다 "한국어 이름표(키): 값" 한 줄, 배열 원소는 원소마다 한 줄로 만듭니다.
 */
final class TcAiToolValues {
    static final String SNAPSHOT_TITLE = "설비 운영 스냅샷";
    static final String MODEL_TITLE = "모델 정의";
    static final String DLQ_TITLE = "DLQ 데드레터 큐 목록 항목";
    static final String COMM_LOG_TITLE = "통신 로그";
    private static final Map<String, String> DETAIL_TITLES = Map.of(
            "model-parameter", "모델 파라미터",
            "secs-message", "SECS 메시지 정의",
            "socket-message", "SOCKET 메시지 정의",
            "mes-message", "MES 메시지 정의");
    private static final List<String> CONDITION_KEYS = List.of("eqp", "from", "to");
    private static final String CONDITIONS = "조회 조건";
    private static final String NONE = "없음";
    private static final int REGISTRATION_TEXT_LIMIT = 4000;
    private static final Set<String> SECRET_REGISTRATION_PARAMETER_NAMES = Set.of("password", "api_token", "private_key");
    /** 키가 원천 자료에서 정해지는 맵이라 이름표 대신 원래 키를 씁니다. */
    private static final String RAW_KEY_MAP = "tags";
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("eqp", "설비"), Map.entry("from", "시작 시각"), Map.entry("to", "종료 시각"),
            Map.entry("source", "출처"), Map.entry("entry", "내용"), Map.entry("row", "내용"),
            Map.entry("detailType", "상세 종류"), Map.entry("occurredAt", "발생 시각"),
            Map.entry("direction", "송수신 방향"), Map.entry("messageName", "메시지 이름"),
            Map.entry("dlqId", "DLQ ID"), Map.entry("equipmentId", "설비 ID"), Map.entry("traceId", "추적 ID"),
            Map.entry("commInterfaceType", "통신 방식"), Map.entry("socketType", "소켓 유형"),
            Map.entry("stage", "실패 단계"), Map.entry("reasonCode", "실패 사유 코드"),
            Map.entry("reasonMessage", "실패 사유 메시지"), Map.entry("payloadRefKey", "원문 참조 키"),
            Map.entry("rawLen", "원문 길이"), Map.entry("b64Len", "Base64 길이"), Map.entry("tags", "태그"),
            Map.entry("ttlSeconds", "보존 시간 초"), Map.entry("topic", "Kafka 토픽"),
            Map.entry("partition", "파티션"), Map.entry("offset", "오프셋"), Map.entry("eqpId", "설비 ID"),
            Map.entry("messageType", "메시지 유형"), Map.entry("payloadRef", "원문 참조"),
            Map.entry("eqpKey", "설비 키"), Map.entry("lineKey", "라인 키"), Map.entry("commInterface", "통신 방식"),
            Map.entry("commMode", "통신 모드"), Map.entry("isDev", "개발 설비 여부"),
            Map.entry("routePartition", "라우팅 파티션"), Map.entry("eqpIp", "설비 IP"),
            Map.entry("eqpPort", "설비 포트"), Map.entry("modelVersionKey", "모델 버전 키"),
            Map.entry("appliedParamVersion", "적용 파라미터 버전"), Map.entry("gatewayJarKey", "Gateway JAR 키"),
            Map.entry("businessJarKey", "Business JAR 키"), Map.entry("enabled", "사용 여부"),
            Map.entry("createdAt", "생성 시각"), Map.entry("updatedAt", "수정 시각"),
            Map.entry("createdBy", "생성자"), Map.entry("updatedBy", "수정자"), Map.entry("modelKey", "모델 키"),
            Map.entry("modelName", "모델 이름"), Map.entry("parentModel", "상위 모델"),
            Map.entry("modelVersion", "모델 버전"), Map.entry("status", "상태"), Map.entry("description", "설명"),
            Map.entry("maker", "제조사"), Map.entry("modelParamKey", "모델 파라미터 키"),
            Map.entry("paramName", "파라미터 이름"), Map.entry("paramValue", "파라미터 값"),
            Map.entry("secsMsgKey", "SECS 메시지 키"), Map.entry("secsMsgName", "SECS 메시지 이름"),
            Map.entry("socketMsgKey", "SOCKET 메시지 키"), Map.entry("socketMsgName", "SOCKET 메시지 이름"),
            Map.entry("mesMsgKey", "MES 메시지 키"), Map.entry("mesMsgName", "MES 메시지 이름"),
            Map.entry("workLotKey", "작업 LOT 키"), Map.entry("workKey", "작업 키"),
            Map.entry("carrierId", "캐리어 ID"), Map.entry("lotId", "LOT ID"),
            Map.entry("parentLotId", "상위 LOT ID"), Map.entry("chamberId", "챔버 ID"),
            Map.entry("workId", "작업 ID"), Map.entry("operatorId", "작업자 ID"), Map.entry("stepSeq", "공정 순번"),
            Map.entry("workState", "작업 상태"), Map.entry("startTime", "시작 시각"),
            Map.entry("endTime", "종료 시각"), Map.entry("mesMessage", "MES 메시지"), Map.entry("lots", "LOT"),
            Map.entry("runtimeState", "실시간 상태"), Map.entry("activeWorks", "진행 작업"),
            Map.entry("lotStatus", "LOT 상태"), Map.entry("currentLot", "현재 LOT"),
            Map.entry("controlState", "제어 상태"), Map.entry("eqpState", "설비 상태"),
            Map.entry("connectionState", "연결 상태"), Map.entry("desiredState", "연결 의도"),
            Map.entry("deviceId", "장치 ID"), Map.entry("t3Timeout", "T3 타임아웃"),
            Map.entry("t5Timeout", "T5 타임아웃"), Map.entry("t6Timeout", "T6 타임아웃"),
            Map.entry("t7Timeout", "T7 타임아웃"), Map.entry("t8Timeout", "T8 타임아웃"),
            Map.entry("linkTestEnabled", "링크 테스트 사용 여부"), Map.entry("linkTestInterval", "링크 테스트 간격"),
            Map.entry("maxMsgBytes", "최대 메시지 바이트"), Map.entry("socketProtocolType", "소켓 프로토콜 유형"),
            Map.entry("charset", "문자 인코딩"), Map.entry("heartbeatEnabled", "하트비트 사용 여부"),
            Map.entry("heartbeatInterval", "하트비트 간격"), Map.entry("readTimeout", "읽기 타임아웃"),
            Map.entry("writeTimeout", "쓰기 타임아웃"), Map.entry("maxFrameSizeBytes", "최대 프레임 바이트"),
            Map.entry("keepAliveEnabled", "연결 유지 사용 여부"), Map.entry("plcProtocol", "PLC 프로토콜"),
            Map.entry("transport", "전송 방식"), Map.entry("unitId", "유닛 ID"),
            Map.entry("pollTimeoutMs", "폴링 타임아웃 밀리초"), Map.entry("reconnectMaxRetries", "최대 재연결 시도 횟수"),
            Map.entry("enabledPollGroups", "활성 폴링 그룹"), Map.entry("version", "버전"),
            Map.entry("logLevel", "로그 레벨"), Map.entry("logRetentionDays", "로그 보관 일수"), Map.entry("logPath", "로그 경로"),
            Map.entry("eqpParamKey", "설비 파라미터 키"), Map.entry("paramVersion", "버전"),
            Map.entry("eqpParamVersionKey", "설비 파라미터 버전 키"), Map.entry("versionDescription", "설명"),
            Map.entry("eqpPortStatusKey", "설비 포트 키"), Map.entry("portId", "포트 ID"),
            Map.entry("portType", "포트 유형"), Map.entry("portState", "포트 상태"),
            Map.entry("carrierType", "캐리어 유형"), Map.entry("carrierState", "캐리어 상태"),
            Map.entry("jarFileName", "JAR 파일명"), Map.entry("valueOmissionReason", "값 제외 사유"));

    private TcAiToolValues() {}

    static List<AiToolItem> registration(final String eqp, final EqpManagementSnapshot snapshot,
                                         final Map<String, Object> conditions) {
        List<AiToolItem> items = new ArrayList<>();
        registrationSection(items, eqp, "설비 등록 정보", conditions, eqp(snapshot.eqp()));
        if (snapshot.model() != null) registrationSection(items, eqp + ":model", "등록 모델", conditions, model(snapshot.model()));
        if (snapshot.hsms() != null) registrationSection(items, eqp + ":hsms", "HSMS 통신 설정", conditions, hsms(snapshot.hsms()));
        if (snapshot.socket() != null) registrationSection(items, eqp + ":socket", "SOCKET 통신 설정", conditions, socket(snapshot.socket()));
        if (snapshot.plc() != null) registrationSection(items, eqp + ":plc", "PLC 통신 설정", conditions, plc(snapshot.plc()));
        if (snapshot.logPolicy() != null) registrationSection(items, eqp + ":log-policy", "로그 정책", conditions, logPolicy(snapshot.logPolicy()));
        for (var row : snapshot.params()) registrationSection(items, eqp + ":parameter:" + row.eqpParamKey(),
                "설비 파라미터", conditions, parameter(row));
        for (var row : snapshot.paramVersionMetas()) registrationSection(items, eqp + ":parameter-version:" + row.eqpParamVersionKey(),
                "설비 파라미터 버전", conditions, parameterVersion(row));
        for (var row : snapshot.portStatuses()) registrationSection(items, eqp + ":port:" + row.eqpPortStatusKey(),
                "설비 포트", conditions, port(row));
        if (snapshot.gatewayJar() != null) registrationSection(items, eqp + ":gateway-jar", "Gateway JAR 등록 정보", conditions, gatewayJar(snapshot.gatewayJar()));
        if (snapshot.businessJar() != null) registrationSection(items, eqp + ":business-jar", "Business JAR 등록 정보", conditions, businessJar(snapshot.businessJar()));
        return List.copyOf(items);
    }

    /** 구획·행을 먼저 나누고, 한 필드도 상한을 넘으면 이름표를 반복해 원문을 모두 보존합니다. */
    private static void registrationSection(final List<AiToolItem> items, final String ref, final String title,
                                            final Map<String, Object> conditions, final Map<String, Object> values) {
        String header = text(title, conditions, Map.of());
        int lineCapacity = REGISTRATION_TEXT_LIMIT - header.codePointCount(0, header.length()) - 1;
        List<String> lines = new ArrayList<>();
        fields("", values, false, lines);
        StringBuilder page = new StringBuilder(header);
        int part = 1;
        for (String line : lines) {
            int valueStart = line.indexOf(": ") + 2;
            String prefix = line.substring(0, valueStart);
            String value = line.substring(valueStart);
            int valueCapacity = lineCapacity - prefix.codePointCount(0, prefix.length());
            if (valueCapacity <= 0) throw new IllegalStateException("등록 정보 이름표가 본문 상한을 초과합니다.");
            int start = 0;
            int remaining = value.codePointCount(0, value.length());
            do {
                int count = Math.min(valueCapacity, remaining);
                int end = value.offsetByCodePoints(start, count);
                String fragment = prefix + value.substring(start, end);
                if (page.codePointCount(0, page.length()) + 1 + fragment.codePointCount(0, fragment.length()) > REGISTRATION_TEXT_LIMIT) {
                    registrationPage(items, ref, part++, page.toString(), values);
                    page = new StringBuilder(header);
                }
                page.append('\n').append(fragment);
                start = end;
                remaining -= count;
            } while (remaining > 0);
        }
        registrationPage(items, ref, part, page.toString(), values);
    }

    private static void registrationPage(final List<AiToolItem> items, final String ref, final int part,
                                         final String text, final Map<String, Object> values) {
        // 원천 값은 첫 항목에만 두어 분할 때문에 같은 파라미터 행이 중복된 것으로 보이지 않게 합니다.
        items.add(new AiToolItem(part == 1 ? ref : ref + ":part:" + part, text, part == 1 ? values : Map.of(), null));
    }

    private static Map<String, Object> hsms(final UiEqpHsms value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpKey", value.eqpKey());
        values.put("deviceId", value.deviceId());
        values.put("t3Timeout", value.t3Timeout());
        values.put("t5Timeout", value.t5Timeout());
        values.put("t6Timeout", value.t6Timeout());
        values.put("t7Timeout", value.t7Timeout());
        values.put("t8Timeout", value.t8Timeout());
        values.put("linkTestEnabled", value.linkTestEnabled());
        values.put("linkTestInterval", value.linkTestInterval());
        values.put("maxMsgBytes", value.maxMsgBytes());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> socket(final UiEqpSocket value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpKey", value.eqpKey());
        values.put("socketProtocolType", value.socketProtocolType());
        values.put("charset", value.charset());
        values.put("heartbeatEnabled", value.heartbeatEnabled());
        values.put("heartbeatInterval", value.heartbeatInterval());
        values.put("readTimeout", value.readTimeout());
        values.put("writeTimeout", value.writeTimeout());
        values.put("maxFrameSizeBytes", value.maxFrameSizeBytes());
        values.put("keepAliveEnabled", value.keepAliveEnabled());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> plc(final UiEqpPlc value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpKey", value.eqpKey());
        values.put("plcProtocol", value.plcProtocol());
        values.put("transport", value.transport());
        values.put("unitId", value.unitId());
        values.put("pollTimeoutMs", value.pollTimeoutMs());
        values.put("reconnectMaxRetries", value.reconnectMaxRetries());
        values.put("enabledPollGroups", value.enabledPollGroups());
        values.put("version", value.version());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> logPolicy(final UiEqpLogPolicy value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpKey", value.eqpKey());
        values.put("logLevel", enumName(value.logLevel()));
        values.put("logRetentionDays", value.logRetentionDays());
        values.put("logPath", value.logPath());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> parameter(final UiEqpParam value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpParamKey", value.eqpParamKey());
        values.put("eqpKey", value.eqpKey());
        values.put("paramName", value.paramName());
        values.put("paramVersion", value.paramVersion());
        // 전체 이름만 대조하여 password_timeout 같은 일반 파라미터까지 가리지 않습니다.
        if (value.paramName() != null && SECRET_REGISTRATION_PARAMETER_NAMES.contains(value.paramName().toLowerCase(Locale.ROOT))) {
            values.put("valueOmissionReason", "비밀 파라미터 이름에 해당하여 값을 제외했습니다.");
        } else values.put("paramValue", value.paramValue());
        values.put("description", value.description());
        values.put("createdBy", value.createdBy());
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> parameterVersion(final UiEqpParamVersion value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpParamVersionKey", value.eqpParamVersionKey());
        values.put("eqpKey", value.eqpKey());
        values.put("paramVersion", value.paramVersion());
        values.put("versionDescription", value.versionDescription());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("createdBy", value.createdBy());
        values.put("updatedBy", value.updatedBy());
        return values;
    }

    private static Map<String, Object> port(final UiEqpPortStatus value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eqpPortStatusKey", value.eqpPortStatusKey());
        values.put("eqpKey", value.eqpKey());
        values.put("portId", value.portId());
        values.put("portType", enumName(value.portType()));
        values.put("portState", value.portState());
        values.put("carrierId", value.carrierId());
        values.put("carrierType", enumName(value.carrierType()));
        values.put("carrierState", enumName(value.carrierState()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        return values;
    }

    private static Map<String, Object> gatewayJar(final UiGatewayJar value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gatewayJarKey", value.gatewayJarKey());
        values.put("jarFileName", value.jarFileName());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("createdBy", value.createdBy());
        values.put("updatedBy", value.updatedBy());
        return values;
    }

    private static Map<String, Object> businessJar(final UiBusinessJar value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("businessJarKey", value.businessJarKey());
        values.put("jarFileName", value.jarFileName());
        values.put("createdAt", timestamp(value.createdAt()));
        values.put("updatedAt", timestamp(value.updatedAt()));
        values.put("createdBy", value.createdBy());
        values.put("updatedBy", value.updatedBy());
        return values;
    }

    static AiToolItem item(final String ref, final String title, final Map<String, Object> conditions,
                           final Map<String, Object> values) {
        // DB 항목은 발생 시각순 자료가 아니므로 절삭용 누락 시간 구간을 만들지 않습니다.
        return new AiToolItem(ref, text(title, conditions, values), values, null);
    }

    static AiToolItem timedItem(final String ref, final String title, final Map<String, Object> conditions,
                                final Map<String, Object> values, final Instant time) {
        return new AiToolItem(ref, text(title, conditions, values), values, time);
    }

    /** 응답 값에 없을 수 있는 질문의 설비·기간을 대조할 수 있도록 null이 아닌 입력을 고정 순서로 옮깁니다. */
    static Map<String, Object> conditions(final Map<String, Object> arguments) {
        Map<String, Object> conditions = new LinkedHashMap<>();
        for (String key : CONDITION_KEYS) {
            if (arguments.get(key) != null) conditions.put(key, arguments.get(key));
        }
        return conditions;
    }

    private static String text(final String title, final Map<String, Object> conditions,
                               final Map<String, Object> values) {
        List<String> lines = new ArrayList<>();
        // Gateway는 줄바꿈을 공백으로 합친 뒤 형태소를 나누므로 제목이 다음 줄 낱말과 섞여 잘못 나뉘지 않게 괄호로 닫습니다.
        lines.add("[" + title + "]");
        conditions.forEach((key, value) -> lines.add(CONDITIONS + " > " + named(key) + ": " + scalar(value)));
        fields("", values, false, lines);
        return String.join("\n", lines);
    }

    private static void fields(final String prefix, final Map<?, ?> values, final boolean rawKeys,
                               final List<String> lines) {
        values.forEach((rawKey, value) -> {
            String key = (String) rawKey;
            String label = rawKeys ? key : label(key);
            String name = rawKeys ? key : named(key);
            switch (value) {
                case Map<?, ?> child when !child.isEmpty() ->
                        fields(prefix + label + " > ", child, RAW_KEY_MAP.equals(key), lines);
                case List<?> list when !list.isEmpty() -> {
                    for (int index = 0; index < list.size(); index++) {
                        element(prefix + label + " " + (index + 1), list.get(index), lines);
                    }
                }
                case Map<?, ?> ignored -> lines.add(prefix + name + ": " + NONE);
                case List<?> ignored -> lines.add(prefix + name + ": " + NONE);
                case null, default -> lines.add(prefix + name + ": " + scalar(value));
            }
        });
    }

    /**
     * 배열 원소는 text 4000자 상한 안에 JSON 때보다 적지 않은 원소를 담도록 값 필드를 "이름표 값" 쉼표 목록 한 줄로 잇고,
     * 원소 안의 비어 있지 않은 객체·배열만 "이름표 N > " 아래 줄로 씁니다.
     */
    private static void element(final String head, final Object value, final List<String> lines) {
        if (!(value instanceof Map<?, ?> element)) {
            lines.add(head + ": " + scalar(value));
            return;
        }
        List<String> facts = new ArrayList<>();
        Map<Object, Object> nested = new LinkedHashMap<>();
        element.forEach((rawKey, field) -> {
            String key = (String) rawKey;
            if (filled(field)) nested.put(key, field);
            else facts.add(label(key) + " " + (field instanceof Map<?, ?> || field instanceof List<?> ? NONE : scalar(field)));
        });
        if (!facts.isEmpty()) lines.add(head + ": " + String.join(", ", facts));
        fields(head + " > ", nested, false, lines);
    }

    private static boolean filled(final Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty() || value instanceof List<?> list && !list.isEmpty();
    }

    private static String named(final String key) {
        return label(key) + "(" + key + ")";
    }

    private static String label(final String key) {
        String label = LABELS.get(key);
        if (label == null) throw new IllegalStateException("TC AI 도구 필드의 한국어 이름표가 없습니다: " + key);
        return label;
    }

    /** 원천 문자열의 줄바꿈이 한 줄 한 사실 구조를 깨지 않도록 공백으로 바꿉니다. 원래 값은 values에 남습니다. */
    private static String scalar(final Object value) {
        if (value == null) return NONE;
        if (value instanceof String text) return text.replaceAll("[\\r\\n\\u0085\\u2028\\u2029]+", " ");
        return String.valueOf(value);
    }

    static Map<String, Object> dlq(DlqFact fact) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", fact.source().value());
        values.put("entry", switch (fact.entry()) {
            case GatewayEntry entry -> gateway(entry);
            case BusinessEntry entry -> business(entry);
        });
        return values;
    }

    static Map<String, Object> commLog(CommLogFact fact) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", fact.source().value());
        values.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(fact.time()));
        values.put("direction", fact.direction());
        values.put("messageName", fact.messageName());
        return values;
    }

    private static Map<String, Object> gateway(GatewayEntry e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dlqId", e.dlqId());
        values.put("equipmentId", e.equipmentId());
        values.put("traceId", e.traceId());
        values.put("commInterfaceType", e.commInterfaceType());
        values.put("socketType", e.socketType());
        values.put("stage", e.stage());
        values.put("reasonCode", e.reasonCode());
        values.put("reasonMessage", e.reasonMessage());
        values.put("occurredAt", e.occurredAt());
        values.put("payloadRefKey", e.payloadRefKey());
        values.put("rawLen", e.rawLen());
        values.put("b64Len", e.b64Len());
        values.put("tags", e.tags());
        values.put("ttlSeconds", e.ttlSeconds());
        return values;
    }

    private static Map<String, Object> business(BusinessEntry e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dlqId", e.dlqId());
        values.put("source", e.source());
        values.put("stage", e.stage());
        values.put("reasonCode", e.reasonCode());
        values.put("reasonMessage", e.reasonMessage());
        values.put("occurredAt", e.occurredAt());
        values.put("topic", e.topic());
        values.put("partition", e.partition());
        values.put("offset", e.offset());
        values.put("eqpId", e.eqpId());
        values.put("messageType", e.messageType());
        values.put("messageName", e.messageName());
        values.put("traceId", e.traceId());
        values.put("payloadRef", e.payloadRef());
        values.put("tags", e.tags());
        values.put("ttlSeconds", e.ttlSeconds());
        return values;
    }

    static AiToolItem detail(final String modelRef, final String type, final long key,
                             final Map<String, Object> row, final Map<String, Object> conditions) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("detailType", type);
        values.put("row", row);
        String title = DETAIL_TITLES.get(type);
        if (title == null) throw new IllegalStateException("TC AI 모델 상세 종류의 제목이 없습니다: " + type);
        return item(modelRef + ":" + type + ":" + key, title, conditions, values);
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
