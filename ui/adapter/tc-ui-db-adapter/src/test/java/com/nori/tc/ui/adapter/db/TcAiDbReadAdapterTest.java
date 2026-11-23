package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.work.store.*;
import com.nori.tc.db.domain.work.*;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.model.*;
import com.nori.tc.ui.core.port.db.*;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.model.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 port/store 계약의 페이지 순회·자료 연결·장애 전파를 확인합니다. */
class TcAiDbReadAdapterTest {
    private final EqpQueryPort eqp = mock(EqpQueryPort.class);
    private final TcWorkStore works = mock(TcWorkStore.class);
    private final TcWorkLotStore lots = mock(TcWorkLotStore.class);
    private final ModelCrudPort models = mock(ModelCrudPort.class);
    private final ModelDetailQueryPort details = mock(ModelDetailQueryPort.class);
    private final TcAiDbReadAdapter adapter = new TcAiDbReadAdapter(eqp, works, lots, models, details);
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-23T10:00+09:00");
    private static final UiEqp EQP = new UiEqp(1L, "EQP", 2, null, null, false, null,
            null, 0, 3, null, null, null, true, null, null, null, null);

    @Test
    void allWorkAndLotPagesAreReadAndSortedAscending() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(works.findAllByEqpKey(1, PageRequest.of(0, 100))).thenReturn(IntStream.rangeClosed(2, 101)
                .mapToObj(i -> work(103 - i)).toList());
        when(works.findAllByEqpKey(1, PageRequest.of(100, 100))).thenReturn(List.of(work(1)));
        when(lots.findAllByWorkKey(eq(1L), any())).thenAnswer(call -> {
            PageRequest page = call.getArgument(1);
            if (page.offset() == 0) return IntStream.rangeClosed(1, 100).mapToObj(i -> lot(i, 1)).toList();
            return List.of(lot(101, 1));
        });
        var facts = adapter.readEquipment("EQP").orElseThrow();
        assertEquals(IntStream.rangeClosed(1, 101).mapToObj(i -> (long) i).toList(),
                facts.works().stream().map(TcAiReadPort.Work::workKey).toList());
        var first = facts.works().getFirst();
        assertEquals(101, first.lots().size());
        assertEquals(101, first.lots().getLast().workLotKey());
        assertEquals(TIME, first.updatedAt());
        assertEquals("fixture-work", first.workId());
        assertEquals(1, first.lots().getLast().workKey());
        verify(works).findAllByEqpKey(1, PageRequest.of(100, 100));
        verify(lots).findAllByWorkKey(1, PageRequest.of(100, 100));
        assertNull(facts.runtimeState());
    }

    @Test
    void exactlyFullPageNeedsAnEmptyNextPage() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(works.findAllByEqpKey(1, PageRequest.of(0, 100))).thenReturn(IntStream.rangeClosed(1, 100).mapToObj(TcAiDbReadAdapterTest::work).toList());
        assertEquals(100, adapter.readEquipment("EQP").orElseThrow().works().size());
        verify(works).findAllByEqpKey(1, PageRequest.of(100, 100));
    }

    @Test
    void duplicatePageKeyFailsInsteadOfSilentlyDeduplicating() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(works.findAllByEqpKey(1, PageRequest.of(0, 100))).thenReturn(IntStream.rangeClosed(1, 100).mapToObj(TcAiDbReadAdapterTest::work).toList());
        when(works.findAllByEqpKey(1, PageRequest.of(100, 100))).thenReturn(List.of(work(100)));
        assertThrows(IllegalStateException.class, () -> adapter.readEquipment("EQP"));
    }

    @Test
    void duplicateLotKeyAlsoFails() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(works.findAllByEqpKey(eq(1L), any())).thenReturn(List.of(work(1)));
        when(lots.findAllByWorkKey(eq(1L), any())).thenReturn(List.of(lot(1, 1), lot(1, 1)));
        assertThrows(IllegalStateException.class, () -> adapter.readEquipment("EQP"));
    }

    @Test
    void missingEquipmentDoesNotReadWorkOrDetailStores() {
        assertEquals(Optional.empty(), adapter.readEquipment("MISSING"));
        assertTrue(adapter.readModels("MISSING").isEmpty());
        verifyNoInteractions(works, lots, models, details);
    }

    @Test
    void missingLinkedModelIsEmpty() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        assertTrue(adapter.readModels("EQP").isEmpty());
        verifyNoInteractions(details);
    }

    @Test
    void linkedModelAndAllFourDetailCallsKeepKeysAndReturnOrder() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(models.findByModelVersionKey(3)).thenReturn(Optional.of(model(3)));
        var parameters = List.of(new UiModelParam(8, 3, "P8", null, null, TIME), new UiModelParam(7, 3, "P7", null, null, null));
        when(details.findParamsByModelVersionKey(3)).thenReturn(parameters);
        when(details.findSecsMessagesByModelVersionKey(3)).thenReturn(List.of(new UiModelSecsMessage(9, 3, null, null, TIME)));
        when(details.findSocketMessagesByModelVersionKey(3)).thenReturn(List.of(new UiModelSocketMessage(10, 3, null, null, TIME)));
        when(details.findMesMessagesByModelVersionKey(3)).thenReturn(List.of(new UiModelMesMessage(11, 3, null, null, TIME)));
        var fact = adapter.readModels("EQP").getFirst();
        assertEquals(parameters, fact.parameters());
        assertEquals("parent-ref", fact.model().parentModel());
        assertEquals(3, fact.secsMessages().getFirst().modelVersionKey());
        assertEquals(3, fact.socketMessages().getFirst().modelVersionKey());
        assertEquals(3, fact.mesMessages().getFirst().modelVersionKey());
        var order = inOrder(details);
        order.verify(details).findParamsByModelVersionKey(3);
        order.verify(details).findSecsMessagesByModelVersionKey(3);
        order.verify(details).findSocketMessagesByModelVersionKey(3);
        order.verify(details).findMesMessagesByModelVersionKey(3);
    }

    @Test
    void noDetailsStillKeepsTheModel() {
        when(eqp.findByEqpId("EQP")).thenReturn(Optional.of(EQP));
        when(models.findByModelVersionKey(3)).thenReturn(Optional.of(model(3)));
        var facts = adapter.readModels("EQP");
        assertEquals(1, facts.size());
        assertTrue(facts.getFirst().parameters().isEmpty());
    }

    @Test
    void unscopedModelsReadEveryPageAndNeverReadRecursiveDetails() {
        when(models.findAll(UiPageRequest.of(0, 100))).thenReturn(PagedResponse.of(IntStream.rangeClosed(1, 100)
                .mapToObj(TcAiDbReadAdapterTest::model).toList(), 0, 100, 101));
        when(models.findAll(UiPageRequest.of(100, 100))).thenReturn(PagedResponse.of(List.of(model(101)), 100, 100, 101));
        var facts = adapter.readModels(null);
        assertEquals(101, facts.size());
        assertEquals(101, facts.getLast().model().modelVersionKey());
        assertTrue(facts.stream().allMatch(fact -> fact.parameters().isEmpty() && fact.secsMessages().isEmpty()
                && fact.socketMessages().isEmpty() && fact.mesMessages().isEmpty()));
        verifyNoInteractions(eqp, details);
    }

    @Test
    void zeroModelsAndRepositoryFailuresRemainDistinct() {
        when(models.findAll(any())).thenReturn(PagedResponse.empty(0, 100));
        assertTrue(adapter.readModels(null).isEmpty());
        var failure = new IllegalStateException("시험 저장소 장애");
        when(models.findAll(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> adapter.readModels(null)));
    }

    @Test
    void authenticationPortsPropagateActualDbAccessException() {
        var failure = new com.nori.tc.db.core.exception.DbAccessException("시험 저장소 장애", new IllegalStateException());
        var sessionStore = mock(com.nori.tc.db.core.user.store.TcUiAuthSessionStore.class);
        when(sessionStore.findValidByToken(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(com.nori.tc.db.core.exception.DbAccessException.class,
                () -> new JpaSessionPort(sessionStore).findValidByToken("fixture-session")));
        var userStore = mock(com.nori.tc.db.core.user.store.TcUserInfoStore.class);
        when(userStore.findByUserPk(7)).thenThrow(failure);
        assertSame(failure, assertThrows(com.nori.tc.db.core.exception.DbAccessException.class,
                () -> new JpaUserPort(userStore).findByUserPk(7)));
        var members = mock(com.nori.tc.db.core.user.store.TcUserGroupMemberStore.class);
        var mappings = mock(com.nori.tc.db.core.user.store.TcUserGroupPermissionStore.class);
        var permissionStore = mock(com.nori.tc.db.core.user.store.TcUiPermissionStore.class);
        when(members.findAllByUserPk(7)).thenThrow(failure);
        assertSame(failure, assertThrows(com.nori.tc.db.core.exception.DbAccessException.class,
                () -> new JpaPermissionPort(members, mappings, permissionStore).findPermissionCodesByUserPk(7)));
        verifyNoInteractions(mappings, permissionStore);
    }

    @Test
    void readBoundaryUsesRepeatableReadAndExplicitConditionalImport() {
        var transaction = TcAiDbReadAdapter.class.getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
        assertThat(UiDbAdapterModuleConfiguration.class.getAnnotation(Import.class).value()).contains(TcAiDbReadAdapter.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(TcAiDbReadAdapter.class);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TcAiReadPort.class));
        runner.withPropertyValues("nori.ai.integration.enabled=true")
                .withBean(EqpQueryPort.class, () -> eqp).withBean(TcWorkStore.class, () -> works)
                .withBean(TcWorkLotStore.class, () -> lots).withBean(ModelCrudPort.class, () -> models)
                .withBean(ModelDetailQueryPort.class, () -> details)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TcAiReadPort.class));
    }

    private static TcWork work(long key) {
        return new TcWork(key, 1, "fixture-work", null, null, "start", null, null, null, null, TIME);
    }
    private static TcWorkLot lot(long key, long parent) {
        return new TcWorkLot(key, parent, null, "fixture-lot", null, null, TIME);
    }
    private static UiModel model(long key) {
        return new UiModel(key, 2, "fixture-model", "parent-ref", null, null, null, null, null, null, TIME, null, null);
    }
}
