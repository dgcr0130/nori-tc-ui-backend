package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.domain.common.model.PlcDataType;
import com.nori.tc.db.jpa.common.entity.model.TcModelPlcRegisterEntity;
import jakarta.persistence.Column;
import jakarta.persistence.PersistenceException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 발행된 엔티티 length=10과 확장된 DB 컬럼의 검증·저장 호환성을 실 Hibernate/H2로 확인한다. */
class PlcRegisterOrderJpaCompatibilityTest {

    @Test
    void publishedLengthTenMappingValidatesAndStoresLittleEndianInWiderColumns() throws Exception {
        assertEquals(10, TcModelPlcRegisterEntity.class.getDeclaredField("byteOrder").getAnnotation(Column.class).length());
        assertEquals(10, TcModelPlcRegisterEntity.class.getDeclaredField("wordOrder").getAnnotation(Column.class).length());
        final String url = "jdbc:h2:mem:plc_order_compatibility";
        // 마지막 연결이 닫히면 인메모리 DB가 제거된다. 외부 DB와 Flyway는 사용하지 않는다.
        try (var keeper = DriverManager.getConnection(url, "sa", "")) {
            try (var factory = configuration(url, "create").buildSessionFactory()) {
                assertTrue(factory.isOpen());
                try (var session = factory.openSession()) {
                    final var transaction = session.beginTransaction();
                    assertThrows(PersistenceException.class, () -> session.persist(littleEndianRegister()));
                    transaction.rollback();
                }
            }
            try (var statement = keeper.createStatement()) {
                statement.execute("ALTER TABLE tc_model_plc_register ALTER COLUMN byte_order VARCHAR(20)");
                statement.execute("ALTER TABLE tc_model_plc_register ALTER COLUMN word_order VARCHAR(20)");
            }
            try (var factory = configuration(url, "validate").buildSessionFactory(); var session = factory.openSession()) {
                final var transaction = session.beginTransaction();
                final var entity = littleEndianRegister();
                session.persist(entity);
                session.flush();
                session.clear();
                final var saved = session.find(TcModelPlcRegisterEntity.class, entity.getRegisterKey());
                assertEquals("LITTLE_ENDIAN", saved.getByteOrder());
                assertEquals("LITTLE_ENDIAN", saved.getWordOrder());
                transaction.commit();
            }
        }
    }

    private static TcModelPlcRegisterEntity littleEndianRegister() {
        final var entity = TcModelPlcRegisterEntity.newEntity(7L, "TEMP");
        entity.setWordOffset(0);
        entity.setDataType(PlcDataType.INT16);
        entity.setByteOrder("LITTLE_ENDIAN");
        entity.setWordOrder("LITTLE_ENDIAN");
        return entity;
    }

    private static Configuration configuration(final String url, final String schemaAction) {
        return new Configuration()
                .addAnnotatedClass(TcModelPlcRegisterEntity.class)
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", schemaAction);
    }
}
