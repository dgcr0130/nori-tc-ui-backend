package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiRequestIdentity;
import com.nori.ai.integration.config.AiIntegrationProperties;
import com.nori.ai.integration.security.AiIdentityService;
import com.nori.tc.ui.core.port.db.*;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 현재 인증 읽기와 미로그인·장애의 공통 HTTP 경계를 확인합니다. */
class TcAiIdentityResolverTest {
    private final SessionPort sessions = mock(SessionPort.class);
    private final UserPort users = mock(UserPort.class);
    private final PermissionPort permissions = mock(PermissionPort.class);
    private final UiAuthProperties properties = new UiAuthProperties(8, 300, 30, 100, "AI_TEST_AUTH", "/",
            null, false, "Strict", null, null, List.of());
    private final TcAiIdentityResolver resolver = new TcAiIdentityResolver(properties, sessions, users, permissions);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T00:00Z");

    @Test
    void readsCurrentPermissionsEveryTimeWithoutCachedPrincipal() {
        active(7);
        when(permissions.findPermissionCodesByUserPk(7)).thenReturn(Set.of("MODEL_READ")).thenReturn(Set.of("EQP_READ"));
        UserPrincipal staleCache = new UserPrincipal(7, "old", Set.of("DLQ_READ"));
        var request = request("fixture-session");
        request.setUserPrincipal(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(staleCache, null));
        assertEquals(Set.of("MODEL_READ"), resolver.resolve(request).permissionCodes());
        assertEquals(Set.of("EQP_READ"), resolver.resolve(request).permissionCodes());
        verify(sessions, times(2)).findValidByToken("fixture-session");
        verify(users, times(2)).findByUserPk(7);
    }

    @Test
    void emptyPermissionsAndMaximumPositiveSubjectAreValid() {
        active(Long.MAX_VALUE);
        when(permissions.findPermissionCodesByUserPk(Long.MAX_VALUE)).thenReturn(Set.of());
        assertEquals(new AiRequestIdentity(true, "9223372036854775807", Set.of()), resolver.resolve(request("fixture-session")));
    }

    @Test
    void missingEmptyBlankAndDuplicateCookiesDoNotReadStorage() {
        var none = new MockHttpServletRequest();
        assertAnonymous(none);
        assertAnonymous(request(""));
        assertAnonymous(request(" "));
        var duplicate = request("fixture-session");
        duplicate.setCookies(new Cookie("AI_TEST_AUTH", "fixture-session"), new Cookie("AI_TEST_AUTH", "other-fixture"));
        assertAnonymous(duplicate);
        var unrelated = new MockHttpServletRequest();
        unrelated.setCookies(new Cookie("OTHER", "fixture-session"));
        assertAnonymous(unrelated);
        verifyNoInteractions(sessions, users, permissions);
    }

    @Test
    void missingExpiredOrRevokedSessionUsesValidSessionPortContract() {
        for (String token : List.of("missing-fixture", "expired-fixture", "revoked-fixture")) {
            when(sessions.findValidByToken(token)).thenReturn(Optional.empty());
            assertAnonymous(request(token));
        }
        verifyNoInteractions(users, permissions);
    }

    @Test
    void missingUserIsAnonymous() {
        active(7);
        when(users.findByUserPk(7)).thenReturn(Optional.empty());
        assertAnonymous(request("fixture-session"));
        verifyNoInteractions(permissions);
    }

    @ParameterizedTest
    @EnumSource(value = UiUserStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void inactiveUserIsAnonymous(final UiUserStatus status) {
        active(7);
        when(users.findByUserPk(7)).thenReturn(Optional.of(user(7, status)));
        assertAnonymous(request("fixture-session"));
        verifyNoInteractions(permissions);
    }

    @Test
    void eachStorageFailureBecomesAuthUnavailableWithoutCredentialMessage() {
        var failure = new IllegalStateException("시험 저장소 장애");
        active(7);
        when(permissions.findPermissionCodesByUserPk(7)).thenThrow(failure);
        assertAuthUnavailable();
        when(users.findByUserPk(7)).thenThrow(failure);
        assertAuthUnavailable();
        when(sessions.findValidByToken("fixture-session")).thenThrow(failure);
        assertAuthUnavailable();
    }

    @Test
    void invalidSessionPrimaryKeyCannotBeConstructedAndMappingFailureIsUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> new UiAuthSessionSnapshot(0, NOW, NOW.plusHours(1), null, false));
        when(sessions.findValidByToken("fixture-session")).thenThrow(new IllegalArgumentException("시험 데이터 무결성 장애"));
        assertAuthUnavailable();
    }

    private void assertAuthUnavailable() {
        var config = mock(AiIntegrationProperties.class);
        when(config.permissionCodes()).thenReturn(List.of("EQP_READ"));
        var identity = new AiIdentityService(resolver, config);
        var failure = assertThrows(AiIdentityService.Failure.class, () -> identity.current(request("fixture-session")));
        assertEquals(503, failure.status());
        assertEquals("AUTH_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("fixture-session"));
    }

    private void active(long key) {
        when(sessions.findValidByToken("fixture-session")).thenReturn(Optional.of(new UiAuthSessionSnapshot(key, NOW, NOW.plusHours(1), null, false)));
        when(users.findByUserPk(key)).thenReturn(Optional.of(user(key, UiUserStatus.ACTIVE)));
    }

    private static UiUserAccount user(long key, UiUserStatus status) {
        return new UiUserAccount(key, "fixture-user", "fixture-user", "fixture-hash", status, null);
    }

    private static MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("AI_TEST_AUTH", token));
        return request;
    }

    private void assertAnonymous(MockHttpServletRequest request) {
        assertEquals(new AiRequestIdentity(false, null, Set.of()), resolver.resolve(request));
        var config = mock(AiIntegrationProperties.class);
        when(config.permissionCodes()).thenReturn(List.of("EQP_READ"));
        var failure = assertThrows(AiIdentityService.Failure.class, () -> new AiIdentityService(resolver, config).current(request));
        assertEquals(401, failure.status());
        assertEquals("LOGIN_REQUIRED", failure.code());
    }
}
