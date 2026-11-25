package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.*;
import com.nori.tc.db.core.work.store.*;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.jpa.common.entity.eqp.TcEqpStateHistEntity;
import com.nori.tc.db.jpa.common.entity.line.TcLineEntity;
import com.nori.tc.db.jpa.common.mapper.eqp.TcEqpStateHistEntityMapperImpl;
import com.nori.tc.db.jpa.common.mapper.line.TcLineEntityMapperImpl;
import com.nori.tc.db.jpa.common.repository.eqp.TcEqpStateHistJpaRepository;
import com.nori.tc.db.jpa.common.repository.line.TcLineJpaRepository;
import com.nori.tc.db.jpa.common.store.eqp.TcEqpStateHistJpaStore;
import com.nori.tc.db.jpa.common.store.line.TcLineJpaStore;
import com.nori.tc.ui.core.port.db.*;
import java.sql.DriverManager;
import java.util.*;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 이번 시험이 소유한 PG에서 고정 발행 JPA store와 실제 SQL 정렬·스코프를 검증합니다. */
@Tag("connection-scope")
@Testcontainers(disabledWithoutDocker = true)
class TcAiExactRelationsPostgresTest {
    private static final String FIXED_PG_TEST_IMAGE = "pgvector/pgvector:0.8.7-pg18-trixie@sha256:5895949dfcdf308eb0cd31c950c93c14895c77f33fbedfecf1fa511ab1d4bcd2";

    @Test void exactConnAndInactiveLineUseActualPublishedStoresOutsideMixedAndListLimits() throws Exception {
        String image = System.getenv("AIGW_PG_TEST_IMAGE");
        if (image == null) image = FIXED_PG_TEST_IMAGE;
        assertEquals(FIXED_PG_TEST_IMAGE, image);
        // 일반 CI는 클래스의 Docker 부재 조건을 따릅니다. 필수 PG 실측은 Docker에서 실행·skip 0건을 별도로 확인합니다.
        // 지역 변수의 수명에 컨테이너를 묶어 실패 시에도 이 시험의 자원을 닫습니다.
        try (var pg = new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE tc_eqp_state_hist(state_hist_key bigint PRIMARY KEY,eqp_key bigint NOT NULL,state_type varchar(10) NOT NULL,from_state varchar(50),to_state varchar(50),changed_at timestamptz NOT NULL,reason_code varchar(50),reason_detail text)");
                sql.execute("INSERT INTO tc_eqp_state_hist VALUES(1,181,'CONN',NULL,'ONLINE','2026-11-24T00:00Z',NULL,NULL),(2,181,'CONN','ONLINE','OFFLINE','2026-11-24T00:00Z',NULL,NULL),(3,999,'CONN',NULL,'OTHER_EQP','2026-11-25T00:00Z',NULL,NULL)");
                sql.execute("INSERT INTO tc_eqp_state_hist SELECT n+100,181,'OPER',NULL,'RUN','2026-11-25T00:00Z',NULL,NULL FROM generate_series(1,101)n");
                sql.execute("CREATE TABLE tc_line(line_key bigint PRIMARY KEY,line_code varchar(100),line_name varchar(100),description text,is_active boolean,created_at timestamptz,updated_at timestamptz)");
                sql.execute("INSERT INTO tc_line SELECT n,'L'||lpad(n::text,4,'0'),'fixture line '||n,NULL,true,now(),now() FROM generate_series(1,500)n");
                sql.execute("INSERT INTO tc_line VALUES(1501,'Z_LINE_A','A line',NULL,false,now(),now())");
                var configuration = new Configuration().addAnnotatedClass(TcEqpStateHistEntity.class).addAnnotatedClass(TcLineEntity.class)
                        .setProperty("hibernate.connection.url", pg.getJdbcUrl()).setProperty("hibernate.connection.username", pg.getUsername())
                        .setProperty("hibernate.connection.password", pg.getPassword()).setProperty("hibernate.hbm2ddl.auto", "none");
                try (var factory = configuration.buildSessionFactory(); var em = factory.createEntityManager()) {
                    var repositories = new JpaRepositoryFactory(em);
                    var history = new TcEqpStateHistJpaStore(repositories.getRepository(TcEqpStateHistJpaRepository.class), new TcEqpStateHistEntityMapperImpl());
                    var eqps = mock(TcEqpStore.class);
                    var row = mock(TcEqp.class);
                    when(row.eqpKey()).thenReturn(181L);
                    when(eqps.findByEqpId("ETC_001")).thenReturn(Optional.of(row));
                    var equipment = new JpaEqpQueryPort(eqps, mock(TcEqpParamStore.class), mock(TcEqpParamVersionStore.class), mock(TcEqpStateStore.class), history);
                    assertEquals("OFFLINE", equipment.findRuntimeStateByEqpId("ETC_001").orElseThrow().connectionState());
                    // 같은 시각에는 state_hist_key DESC가 이기며, 타 설비·OPER 행은 결과를 바꾸지 않습니다.
                    sql.execute("INSERT INTO tc_eqp_state_hist VALUES(300,181,'CONN','OFFLINE',' ','2026-11-26T00:00Z',NULL,NULL)");
                    assertEquals("", equipment.findRuntimeStateByEqpId("ETC_001").orElseThrow().connectionState());
                    sql.execute("DELETE FROM tc_eqp_state_hist WHERE eqp_key=181 AND state_type='CONN'");
                    assertNull(equipment.findRuntimeStateByEqpId("ETC_001").orElseThrow().connectionState());
                    var lines = new TcLineJpaStore(em, repositories.getRepository(TcLineJpaRepository.class), new TcLineEntityMapperImpl());
                    var adapter = new TcAiDbReadAdapter(equipment, mock(TcWorkStore.class), mock(TcWorkLotStore.class),
                            mock(ModelCrudPort.class), mock(ModelDetailQueryPort.class), lines);
                    assertEquals("A line", adapter.readLine(1501).orElseThrow().lineName());
                    assertFalse(adapter.readLine(1501).orElseThrow().active());
                    assertTrue(adapter.readLine(1502).isEmpty());
                }
            }
        }
    }
}
