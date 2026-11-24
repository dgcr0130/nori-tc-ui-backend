package com.nori.tc.ui.starter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nori.ai.integration.api.*;
import com.nori.ai.integration.config.AiIntegrationAutoConfiguration;
import com.nori.ai.integration.config.AiIntegrationProperties;
import com.nori.ai.integration.security.AiDelegationCodec;
import com.nori.tc.ui.adapter.db.UiDbAdapterModuleConfiguration;
import com.nori.tc.ui.adapters.redis.config.UiRedisAdapterModuleConfiguration;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterModuleConfiguration;
import com.nori.tc.ui.adapters.web.security.*;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiRedisFacts.*;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.port.db.*;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.core.usecase.ValidateTokenUseCase;
import com.nori.tc.ui.domain.auth.*;
import com.nori.tc.ui.domain.common.user.*;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 로컬 HTTP 대역과 실제 TC·공통 보안 그래프를 연결하며 외부 인프라는 사용하지 않습니다. */
class TcAiIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant FROM = Instant.parse("2026-11-23T00:00:00Z");
    private static final String JOB = "12d55b20-b2e1-42d0-8e6f-fd151206f433";
    private static final Set<String> ALL = Set.of("EQP_READ", "MODEL_READ", "DLQ_READ", "COMM_LOG_READ",
            "AI_DOC_READ", "AI_ADMIN_DOC_READ", "AI_LOG_READ", "AI_TRACE_READ", "AI_REVIEW");
    private static final List<Path> TEMP_DIRECTORIES = new CopyOnWriteArrayList<>();
    @TempDir Path directory;
    private final TcAiReadPort db = mock(TcAiReadPort.class);
    private final TcAiReadPort.RedisReads redis = mock(TcAiReadPort.RedisReads.class);
    private final EqpManagementService management = mock(EqpManagementService.class);
    private final SessionPort sessions = mock(SessionPort.class);
    private final UserPort users = mock(UserPort.class);
    private final PermissionPort permissions = mock(PermissionPort.class);
    private final ValidateTokenUseCase cachedAuth = mock(ValidateTokenUseCase.class);
    private final AtomicReference<Set<String>> currentPermissions = new AtomicReference<>(ALL);
    private final List<JsonNode> assertions = new CopyOnWriteArrayList<>();
    private final List<JsonNode> results = new CopyOnWriteArrayList<>();
    private final List<String> privateMarkers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> gatewayFailure = new AtomicReference<>();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private HttpServer gateway;
    private HttpServer backend;
    private ExecutorService gatewayThreads;
    private ExecutorService backendThreads;
    private HttpClient client;
    private KeyPair pair;
    private String sessionToken;
    private String serviceToken;
    private String gatewayToken;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void startLocalGraph() throws Exception {
        TEMP_DIRECTORIES.add(directory);
        sessionToken = marker("cookie"); serviceToken = marker("service"); gatewayToken = marker("gateway");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        when(sessions.findValidByToken(sessionToken)).thenReturn(Optional.of(new UiAuthSessionSnapshot(7,
                now, now.plusHours(1), null, false)));
        when(users.findByUserPk(7)).thenReturn(Optional.of(new UiUserAccount(7, "fixture-user", "시험 사용자",
                marker("hash"), UiUserStatus.ACTIVE, null)));
        when(permissions.findPermissionCodesByUserPk(7)).thenAnswer(invocation -> currentPermissions.get());
        when(cachedAuth.execute(sessionToken)).thenReturn(new UserPrincipal(7, "fixture-user", Set.of("EQP_READ")));
        when(db.readEquipment("EQP")).thenReturn(Optional.empty());
        when(db.readModels(null)).thenReturn(List.of());
        when(management.getManageDetail("EQP")).thenReturn(Optional.empty());
        for (Source source : Source.values()) {
            when(redis.readDlq(eq(source), any())).thenReturn(List.of(dlqFact(source, 0)));
            when(redis.readCommLogs(eq(source), anyString(), any(), any(), any())).thenReturn(List.of(
                    new CommLogFact(source, "tc:" + source.value() + ":comm-log:EQP", 0, FROM.toEpochMilli(), "RECV", "S3F17")));
        }
        gatewayThreads = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("tc-ai-test-gateway-", 0).factory());
        backendThreads = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("tc-ai-test-backend-", 0).factory());
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.setExecutor(gatewayThreads);
        gateway.createContext("/v1/query", this::gatewayQuery);
        gateway.start();
        Properties local = credentials();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // starter 자원을 명시적 입력으로 읽어 실제 앱의 자동 로딩과 구별합니다.
        context.getEnvironment().getPropertySources().addLast(new ResourcePropertySource("tc-ai-file", "classpath:application.properties"));
        context.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("local-test-only", local));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("testDbReads", db);
            factory.registerSingleton("testRedisReads", redis);
            factory.registerSingleton("testEqpManagement", management);
            factory.registerSingleton("testSessions", sessions);
            factory.registerSingleton("testUsers", users);
            factory.registerSingleton("testPermissions", permissions);
            factory.registerSingleton("testCachedAuth", cachedAuth);
        });
        Class<?>[] ownerImports = Stream.of(UiCoreModuleConfiguration.class, UiWebAdapterModuleConfiguration.class,
                        UiDbAdapterModuleConfiguration.class, UiRedisAdapterModuleConfiguration.class)
                .flatMap(type -> Arrays.stream(type.getAnnotation(Import.class).value()))
                .filter(type -> Set.of("TcAiReadService", "TcAiToolConfiguration", "TcAiIdentityResolver").contains(type.getSimpleName()))
                .toArray(Class<?>[]::new);
        assertEquals(3, ownerImports.length);
        assertThat(UiRedisAdapterModuleConfiguration.class.getAnnotation(Import.class).value())
                .extracting(Class::getSimpleName).contains("TcAiRedisReadAdapter");
        assertThat(UiDbAdapterModuleConfiguration.class.getAnnotation(Import.class).value())
                .extracting(Class::getSimpleName).contains("TcAiDbReadAdapter");
        context.register(ownerImports);
        context.register(WebHost.class, UiSecurityConfig.class, AiIntegrationAutoConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // HTTP bridge는 시험에서만 servlet 요청을 전달하며 제품 Controller를 추가하지 않습니다.
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.setExecutor(backendThreads);
        backend.createContext("/", this::backendRequest);
        backend.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
    }

    @AfterEach
    void closeResources() throws Exception {
        if (root != null) { root.detachAppender(logs); logs.stop(); }
        if (gateway != null) gateway.stop(0);
        if (backend != null) backend.stop(0);
        if (client != null) client.close();
        if (context != null) context.close();
        for (ExecutorService executor : Arrays.asList(gatewayThreads, backendThreads)) {
            if (executor != null) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP 대역 스레드 종료");
            }
        }
    }

    @AfterAll
    static void temporaryDirectoriesWereRemoved() {
        assertThat(TEMP_DIRECTORIES).noneMatch(Files::exists);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.isAlive()
                && thread.getName().startsWith("tc-ai-test-"));
    }

    @Test
    void fileSettingsAndFiveHandlersMatchAndSignedHttpRoundTripUsesCurrentIdentity() throws Exception {
        var properties = context.getBean(AiIntegrationProperties.class);
        assertTrue(properties.enabled());
        assertEquals(AiIntegrationProperties.BrowserCredential.COOKIE, properties.browserCredential());
        assertEquals("TC", properties.systemId()); assertEquals("tc-ui-backend", properties.issuer());
        assertEquals(ALL, Set.copyOf(properties.permissionCodes())); assertEquals("AI_REVIEW", properties.reviewPermission());
        assertEquals(1, context.getBeansOfType(AiRequestIdentityResolver.class).size());
        var registered = context.getBeansOfType(AiToolRegistration.class).values().stream().map(AiToolRegistration::toolId).sorted().toList();
        assertEquals(properties.tools().stream().map(AiIntegrationProperties.Tool::id).sorted().toList(), registered);
        assertEquals(5, registered.size());
        var response = question();
        assertEquals(202, response.statusCode()); assertNull(gatewayFailure.get());
        assertEquals(5, results.size());
        assertEquals(List.of("EMPTY", "EMPTY", "PARTIAL", "PARTIAL", "EMPTY"), results.stream().map(n -> n.get("status").asString()).toList());
        assertEquals("7", assertions.getFirst().get("sub").asString());
        assertEquals("TC", assertions.getFirst().get("sys").asString());
        assertEquals("tc-ui-backend", assertions.getFirst().get("iss").asString());
        assertEquals("ai-gateway", assertions.getFirst().get("aud").asString());
        assertEquals("READ", assertions.getFirst().get("scope").asString());
        assertEquals(60, assertions.getFirst().get("exp").asLong() - assertions.getFirst().get("iat").asLong());
        assertEquals(ALL, permissionSet(assertions.getFirst()));
        verify(db).readEquipment("EQP"); verify(db).readModels(null);
        verify(management).getManageDetail("EQP");
        verify(redis).readDlq(eq(Source.GATEWAY), any());
        verify(redis).readCommLogs(eq(Source.BUSINESS), eq("EQP"), eq(FROM), eq(FROM.plusSeconds(10)), any());
        verify(cachedAuth, never()).execute(anyString());
    }

    @Test
    void commonChainsPrecedeBusinessChainAndBusinessAuthenticationCsrfAndPermissionsStayEffective() throws Exception {
        var chains = context.getBean(FilterChainProxy.class).getFilterChains();
        assertEquals(context.getBean("aiInternalSecurityFilterChain"), chains.get(0));
        assertEquals(context.getBean("aiBrowserSecurityFilterChain"), chains.get(1));
        assertEquals(context.getBean("securityFilterChain"), chains.get(2));
        assertEquals(401, send("GET", "/api/probe", null, null).statusCode());
        assertEquals(200, send("GET", "/api/probe", sessionCookie(), null).statusCode());
        assertEquals(403, send("POST", "/api/probe", sessionCookie(), "{}").statusCode());
        var businessCsrf = mvc.perform(MockMvcRequestBuilders.post("/api/probe")
                .cookie(new Cookie("TC_UI_AUTH", sessionToken))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andReturn().getResponse();
        assertEquals(200, businessCsrf.getStatus());
        when(cachedAuth.execute(sessionToken)).thenReturn(new UserPrincipal(7, "fixture-user", Set.of()));
        assertEquals(403, send("GET", "/api/probe", sessionCookie(), null).statusCode());
        assertEquals(401, send("GET", "/api/ai/session", null, null).statusCode());
        assertEquals(403, send("POST", "/api/ai/v1/query", sessionCookie(), "{}").statusCode());
        assertTrue(assertions.isEmpty());
    }

    @Test
    void revocationChangesNextSignatureButPreviousDelegationRetainsItsSnapshotAndDenialDoesNotRead() throws Exception {
        assertEquals(202, question().statusCode());
        var old = assertions.getFirst();
        currentPermissions.set(Set.of("EQP_READ"));
        results.clear(); clearInvocations(redis);
        assertEquals(202, question().statusCode()); assertNull(gatewayFailure.get());
        var next = assertions.getLast();
        assertEquals(Set.of("EQP_READ"), permissionSet(next));
        assertNotEquals(old.get("pdg").asString(), next.get("pdg").asString());
        assertEquals(List.of("EMPTY", "DENIED", "DENIED", "DENIED", "EMPTY"), results.stream().map(n -> n.get("status").asString()).toList());
        for (JsonNode result : results.subList(1, 4)) {
            assertEquals("DELEGATION_DENIED", result.get("errorCode").asString());
            assertTrue(result.get("data").isNull());
        }
        verifyNoInteractions(redis);
        clearInvocations(users, permissions);
        assertEquals(200, internal(old, "tc.dlq.list", Map.of()).statusCode());
        verify(redis).readDlq(eq(Source.GATEWAY), any());
        verifyNoInteractions(users, permissions);
        // 로그아웃도 새 브라우저 요청에 반영되지만 이미 발급한 dlg를 취소하지 않습니다.
        when(sessions.findValidByToken(sessionToken)).thenReturn(Optional.empty());
        assertEquals(401, send("GET", "/api/ai/session", sessionCookie(), null).statusCode());
        assertEquals(200, internal(old, "tc.dlq.list", Map.of()).statusCode());
    }

    @Test
    void registrationRequiresOnlyEqpReadAndDenialDoesNotReadManagementDetail() throws Exception {
        var tools = context.getBean(AiIntegrationProperties.class).tools();
        assertEquals(List.of(List.of("EQP_READ")), tools.stream()
                .filter(tool -> tool.id().equals("tc.eqp.readRegistration"))
                .map(AiIntegrationProperties.Tool::requiredPermissions).toList());
        currentPermissions.set(Set.of("EQP_READ"));
        assertEquals(202, question().statusCode());
        assertNull(gatewayFailure.get());
        assertEquals("EMPTY", results.getLast().get("status").asString());
        verify(management).getManageDetail("EQP");
        currentPermissions.set(Set.of("MODEL_READ"));
        results.clear();
        clearInvocations(management);
        assertEquals(202, question().statusCode());
        assertNull(gatewayFailure.get());
        var denied = results.getLast();
        assertEquals("tc.eqp.readRegistration", denied.get("toolId").asString());
        assertEquals("DENIED", denied.get("status").asString());
        assertEquals("DELEGATION_DENIED", denied.get("errorCode").asString());
        assertTrue(denied.get("data").isNull());
        verifyNoInteractions(management);
    }

    @Test
    void oversizedDlqUsesCommonTruncationAndMissingTimeRange() throws Exception {
        when(redis.readDlq(eq(Source.GATEWAY), any())).thenReturn(IntStream.range(0, 105)
                .mapToObj(i -> dlqFact(Source.GATEWAY, i)).toList());
        assertEquals(202, question().statusCode()); assertNull(gatewayFailure.get());
        var result = results.get(2);
        assertEquals(100, result.at("/data/items").size());
        assertEquals("PARTIAL", result.get("status").asString());
        assertThat(JSON.writeValueAsString(result.get("missingFields"))).contains("completeness", "truncatedItems");
        assertEquals(1, result.at("/retentionMeta/missingRanges").size());
        assertEquals(FROM.toString(), result.at("/retentionMeta/missingRanges/0/from").asString());
        assertEquals(FROM.plusNanos(1).toString(), result.at("/retentionMeta/missingRanges/0/to").asString());
    }

    @Test
    void failureStagesAreLoggedWithoutCredentialsBodiesJwsDelegationsOrKeys() throws Exception {
        assertEquals(202, question().statusCode()); assertNull(gatewayFailure.get());
        var claims = assertions.getFirst();
        when(db.readModels(null)).thenThrow(new IllegalStateException(marker("handler-body")));
        assertEquals(503, internal(claims, "tc.model.read", Map.of()).statusCode());
        when(permissions.findPermissionCodesByUserPk(7)).thenThrow(new IllegalStateException(marker("identity-body")));
        assertEquals(503, send("GET", "/api/ai/session", sessionCookie(), null).statusCode());
        String text = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(text).contains("stage=identity", "code=AUTH_UNAVAILABLE", "stage=handler", "code=SOURCE_UNAVAILABLE", "stage=sign");
        for (String secret : privateMarkers) assertFalse(text.contains(secret), "비밀 표식 비출력");
    }

    private Properties credentials() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair = generator.generateKeyPair();
        var pub = (ECPublicKey) pair.getPublic(); var priv = (ECPrivateKey) pair.getPrivate();
        String x = encode(pub.getW().getAffineX()), y = encode(pub.getW().getAffineY()), d = encode(priv.getS());
        byte[] hmac = new byte[32]; new SecureRandom().nextBytes(hmac);
        String hmacText = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        privateMarkers.addAll(List.of(x, y, d, hmacText));
        Files.writeString(directory.resolve("service"), serviceToken);
        Files.writeString(directory.resolve("gateway"), gatewayToken);
        Files.writeString(directory.resolve("delegation"), hmacText);
        Files.writeString(directory.resolve("actor"), JSON.writeValueAsString(Map.of("kty", "EC", "crv", "P-256",
                "kid", "tc-ai-test", "alg", "ES256", "x", x, "y", y, "d", d)));
        Properties properties = new Properties();
        String prefix = "nori.ai.integration.";
        properties.setProperty(prefix + "gateway-base-url", base(gateway));
        properties.setProperty(prefix + "service-token-file", directory.resolve("service").toString());
        properties.setProperty(prefix + "gateway-token-file", directory.resolve("gateway").toString());
        properties.setProperty(prefix + "delegation-key-file", directory.resolve("delegation").toString());
        properties.setProperty(prefix + "actor-private-jwk-file", directory.resolve("actor").toString());
        return properties;
    }

    private void gatewayQuery(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                assertEquals("Bearer " + serviceToken, exchange.getRequestHeaders().getFirst("Authorization"));
                assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
                assertEquals("POST", exchange.getRequestMethod());
                assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).contains("시험 질문");
                String jws = exchange.getRequestHeaders().getFirst("X-Actor-Assertion");
                privateMarkers.add(jws);
                String[] parts = jws.split("\\.");
                var header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
                assertEquals("ES256", header.get("alg").asString()); assertEquals("tc-ai-test", header.get("kid").asString());
                var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                verifier.initVerify(pair.getPublic());
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
                var claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
                assertEquals(AiDelegationCodec.digest(permissionSet(claims)), claims.get("pdg").asString());
                privateMarkers.add(claims.get("dlg").asString()); assertions.add(claims);
                for (String tool : List.of("tc.eqp.getOperationalSnapshot", "tc.model.read", "tc.dlq.list", "tc.commLogs.search",
                        "tc.eqp.readRegistration")) {
                    Map<String, Object> args = switch (tool) {
                        case "tc.eqp.getOperationalSnapshot", "tc.eqp.readRegistration" -> Map.of("eqp", "EQP");
                        case "tc.commLogs.search" -> Map.of("eqp", "EQP", "from", FROM.toString(), "to", FROM.plusSeconds(10).toString());
                        default -> Map.of();
                    };
                    var response = internal(claims, tool, args);
                    assertTrue(Set.of(200, 403).contains(response.statusCode()));
                    var result = JSON.readTree(response.body());
                    assertEquals(Set.of("version", "systemId", "jobId", "toolId", "status", "observedAt", "data",
                            "missingFields", "warnings", "retentionMeta", "errorCode"), result.propertyNames());
                    assertEquals(tool, result.get("toolId").asString()); results.add(result);
                }
                byte[] body = JSON.writeValueAsBytes(Map.of("jobId", JOB));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body);
            } catch (Exception | AssertionError failure) {
                gatewayFailure.set(failure);
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private HttpResponse<String> question() throws Exception {
        var session = send("GET", "/api/ai/session", sessionCookie(), null);
        assertEquals(200, session.statusCode());
        String csrf = JSON.readTree(session.body()).at("/csrf/token").asString();
        var request = HttpRequest.newBuilder(URI.create(base(backend) + "/api/ai/v1/query"))
                .header("Cookie", sessionCookie() + "; NORI_AI_XSRF=" + csrf).header("X-Nori-AI-CSRF", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"question\":\"시험 질문\",\"context\":{\"timezone\":\"UTC\",\"referenceTime\":\"2026-11-23T00:00:00Z\"}}"));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> internal(JsonNode claims, String tool, Map<String, Object> arguments) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", "1"); body.put("systemId", "TC"); body.put("jobId", JOB); body.put("toolId", tool);
        body.put("subject", claims.get("sub").asString()); body.put("delegationId", claims.get("dlg").asString());
        body.put("permissionDigest", claims.get("pdg").asString()); body.put("arguments", arguments); body.put("targets", List.of());
        body.put("deadline", Instant.now().plusSeconds(10).toString());
        var request = HttpRequest.newBuilder(URI.create(base(backend) + "/internal/ai/v1/query"))
                .header("Authorization", "Bearer " + gatewayToken).header("X-Request-Id", JOB)
                .header("X-Actor-Subject", claims.get("sub").asString()).header("X-Delegation-Id", claims.get("dlg").asString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void backendRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI())
                        .content(exchange.getRequestBody().readAllBytes());
                exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
                String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                if (cookieHeader != null) {
                    for (String cookie : cookieHeader.split(";")) {
                        String[] parts = cookie.strip().split("=", 2);
                        request.cookie(new Cookie(parts[0], parts[1]));
                    }
                }
                var response = mvc.perform(request).andReturn().getResponse();
                for (String name : response.getHeaderNames()) exchange.getResponseHeaders().put(name, new ArrayList<>(response.getHeaders(name)));
                byte[] body = response.getContentAsByteArray();
                exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) exchange.getResponseBody().write(body);
            } catch (Exception failure) { throw new IOException("시험 HTTP bridge 실패", failure); }
        }
    }

    private HttpResponse<String> send(String method, String path, String cookie, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base(backend) + path)).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String sessionCookie() { return "TC_UI_AUTH=" + sessionToken; }
    private String marker(String kind) { String value = kind + "-" + UUID.randomUUID(); privateMarkers.add(value); return value; }
    private static String base(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static Set<String> permissionSet(JsonNode claims) {
        Set<String> result = new HashSet<>(); claims.get("perm").forEach(value -> result.add(value.asString())); return result;
    }
    private static String encode(BigInteger number) {
        byte[] raw = number.toByteArray(), bytes = new byte[32];
        int count = Math.min(32, raw.length); System.arraycopy(raw, raw.length - count, bytes, 32 - count, count);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static DlqFact dlqFact(Source source, int order) {
        DlqEntry entry = source == Source.GATEWAY
                ? new GatewayEntry("id", "EQP", null, null, null, null, null, null, FROM.toEpochMilli(), null, 0, 0, null, null)
                : new BusinessEntry("id", "UI_BACKEND", null, null, null, FROM.toEpochMilli(), null, null, null, "EQP", null, null, null, null, null, null);
        return new DlqFact(source, "tc:" + source.value() + ":dlq:" + order, order, entry);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(Probe.class)
    static class WebHost {
        @Bean tools.jackson.databind.ObjectMapper json() { return JSON; }
        @Bean UiTokenAuthenticationFilter tokenFilter(ValidateTokenUseCase auth, UiAuthProperties properties) {
            return new UiTokenAuthenticationFilter(auth, JSON, properties);
        }
        @Bean UiAuthenticationEntryPoint entryPoint() { return new UiAuthenticationEntryPoint(JSON); }
        @Bean UiApiPermissionCache permissionCache() {
            var now = OffsetDateTime.now();
            return new UiApiPermissionCache(() -> List.of(new UiPermission(1, "EQP_READ", "시험 업무", UiPermissionResourceType.API,
                    UiPermissionMatchType.EXACT, "/api/probe", null, null, true, now, now, null, null)), UiPermissionCatalogGuard.disabled());
        }
    }

    @RestController
    static class Probe {
        @RequestMapping(value = "/api/probe", method = {RequestMethod.GET, RequestMethod.POST})
        String probe() { return "확인"; }
    }
}
