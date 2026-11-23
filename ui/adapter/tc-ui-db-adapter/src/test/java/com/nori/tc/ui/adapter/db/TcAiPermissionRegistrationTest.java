package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.jpa.common.entity.user.TcUiPermissionEntity;
import com.nori.tc.db.jpa.common.entity.user.TcUserGroupPermissionEntity;
import com.nori.tc.db.jpa.common.mapper.user.TcUiPermissionEntityMapperImpl;
import com.nori.tc.db.jpa.common.mapper.user.TcUserGroupPermissionEntityMapperImpl;
import com.nori.tc.db.jpa.common.repository.user.TcUiPermissionJpaRepository;
import com.nori.tc.db.jpa.common.repository.user.TcUserGroupPermissionJpaRepository;
import com.nori.tc.db.jpa.common.store.user.TcUiPermissionJpaStore;
import com.nori.tc.db.jpa.common.store.user.TcUserGroupPermissionJpaStore;
import com.nori.tc.ui.core.model.UiPermissionUpsertCommand;
import com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 고정 버전 store의 유일키 재사용을 저장소 대역으로 검증하며 운영 행을 만들지 않습니다. */
class TcAiPermissionRegistrationTest {
    @Test
    void absentPermissionIsCreatedAndRepeatedCodeReusesItsPermId() {
        var repository = mock(TcUiPermissionJpaRepository.class);
        var saved = new AtomicReference<TcUiPermissionEntity>();
        when(repository.findByPermCode("AI_REVIEW")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.save(any(TcUiPermissionEntity.class))).thenAnswer(invocation -> {
            TcUiPermissionEntity entity = invocation.getArgument(0);
            if (saved.get() != null) assertSame(saved.get(), entity);
            if (entity.getPermId() == null) entity.setPermId(101L);
            saved.set(entity); return entity;
        });
        var store = new TcUiPermissionJpaStore(mock(EntityManager.class), repository, new TcUiPermissionEntityMapperImpl());
        var adapter = new JpaPermissionCrudPort(store);
        var command = new UiPermissionUpsertCommand(null, "AI_REVIEW", "AI 답변 검토", UiPermissionResourceType.API,
                UiPermissionMatchType.EXACT, "/api/ai/permission-codes/AI_REVIEW", "GET", "AI 답변 검토", true, "SYSTEM", "SYSTEM");
        assertEquals(101L, adapter.upsert(command).permId());
        assertEquals(101L, adapter.upsert(command).permId());
        assertEquals("GET", saved.get().getHttpMethod());
        verify(repository, times(2)).findByPermCode("AI_REVIEW");
        verify(repository, times(2)).flush();
    }

    @Test
    void absentGroupPermissionIsCreatedAndRepeatedPairReusesItsKey() {
        var repository = mock(TcUserGroupPermissionJpaRepository.class);
        var saved = new AtomicReference<TcUserGroupPermissionEntity>();
        when(repository.findByGroupIdAndPermId(11L, 101L)).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.save(any(TcUserGroupPermissionEntity.class))).thenAnswer(invocation -> {
            TcUserGroupPermissionEntity entity = invocation.getArgument(0);
            if (saved.get() != null) assertSame(saved.get(), entity);
            if (entity.getUgpKey() == null) entity.setUgpKey(501L);
            saved.set(entity); return entity;
        });
        var store = new TcUserGroupPermissionJpaStore(repository, new TcUserGroupPermissionEntityMapperImpl());
        var adapter = new JpaGroupPermissionMappingPort(store);
        var command = new UiUserGroupPermissionUpsertCommand(11, 101, OffsetDateTime.now(), "SYSTEM");
        assertEquals(501L, adapter.upsert(command).ugpKey());
        assertEquals(501L, adapter.upsert(command).ugpKey());
        verify(repository, times(2)).findByGroupIdAndPermId(11L, 101L);
        verify(repository, times(2)).flush();
    }
}
