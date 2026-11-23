package com.nori.tc.ui.adapters.web.ai;

import com.nori.ai.integration.api.AiRequestIdentity;
import com.nori.ai.integration.api.AiRequestIdentityResolver;
import com.nori.tc.ui.core.port.db.PermissionPort;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.db.UserPort;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.UiUserStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 캐시 대신 현재 세션·사용자·권한을 읽어 AI 요청의 신원을 확인합니다. */
@ConditionalOnProperty(name = "nori.ai.integration.enabled", havingValue = "true")
public class TcAiIdentityResolver implements AiRequestIdentityResolver {
    private final UiAuthProperties properties;
    private final SessionPort sessions;
    private final UserPort users;
    private final PermissionPort permissions;

    public TcAiIdentityResolver(final UiAuthProperties properties, final SessionPort sessions,
                                final UserPort users, final PermissionPort permissions) {
        this.properties = Objects.requireNonNull(properties);
        this.sessions = Objects.requireNonNull(sessions);
        this.users = Objects.requireNonNull(users);
        this.permissions = Objects.requireNonNull(permissions);
    }

    @Override
    public AiRequestIdentity resolve(final HttpServletRequest request) {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return anonymous();
        for (Cookie cookie : cookies) {
            if (properties.cookieName().equals(cookie.getName())) {
                // 같은 이름의 여러 자격 중 하나를 임의로 선택하지 않습니다.
                if (token != null) return anonymous();
                token = cookie.getValue();
                if (token == null || token.isBlank()) return anonymous();
            }
        }
        if (token == null) return anonymous();
        var session = sessions.findValidByToken(token);
        if (session.isEmpty()) return anonymous();
        long userPk = session.get().userPk();
        var user = users.findByUserPk(userPk);
        if (user.isEmpty() || user.get().status() != UiUserStatus.ACTIVE) return anonymous();
        return new AiRequestIdentity(true, Long.toString(userPk), permissions.findPermissionCodesByUserPk(userPk));
    }

    private static AiRequestIdentity anonymous() {
        return new AiRequestIdentity(false, null, Set.of());
    }
}
