package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class JdbcSagaStoreTest {
    static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.15-alpine3.23")
        .withDatabaseName("synkro").withUsername("postgres").withPassword("disposable-admin");
    static JdbcSagaStore store;
    static Flyway flyway;
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static Connection admin() throws SQLException { return DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); }
    static void sql(String text) throws SQLException { try(var c=admin();var s=c.createStatement()){s.execute(text);} }
    static Flyway migration(Path root) {
        return Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())
            .schemas("workflow_schema").defaultSchema("workflow_schema")
            .locations("filesystem:"+root.resolve("db/01_ddl"),"filesystem:"+root.resolve("db/03_dcl"))
            .cleanDisabled(true).validateMigrationNaming(true).load();
    }
    @BeforeAll static void setup() throws Exception {
        pg.start();sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE workflow_app LOGIN PASSWORD 'disposable-app'; CREATE ROLE customers_app LOGIN PASSWORD 'disposable-other'; CREATE SCHEMA customers_schema; CREATE TABLE customers_schema.secret(id int)");
        flyway=migration(ROOT);assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        var ds=new PGSimpleDataSource();ds.setURL(pg.getJdbcUrl());ds.setUser("workflow_app");ds.setPassword("disposable-app");
        store=new JdbcSagaStore(ds,JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }
    @AfterAll static void stop(){pg.stop();}
    Saga saga(String key) { return Saga.create(UUID.randomUUID(),key,UUID.randomUUID(),new SaleInput(UUID.randomUUID(),List.of(new SaleInput.Line(UUID.randomUUID(),2))),"original-correlation",Instant.now()); }
    @Test void realCreationStorageAndReplay() {
        Saga saga=saga("database-"+UUID.randomUUID());var first=store.create(saga);
        assertThat(first.created()).isTrue(); assertThat(store.find(saga.id())).contains(saga);
        assertThat(store.create(saga(saga.idempotencyKey())).saga()).isEqualTo(saga);
        assertThat(store.healthy()).isTrue();
        assertThat(store.running(100)).contains(saga.id());
    }
    @Test void concurrentCreationAndSessionOwnership() throws Exception {
        String key="concurrent-"+UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(6)) {
            var gate=new CyclicBarrier(6);var futures=new ArrayList<Future<UUID>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(()->{gate.await();return store.create(saga(key)).saga().id();}));
            var ids=new HashSet<UUID>();for(var f:futures)ids.add(f.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);UUID id=ids.iterator().next();
            try(var owner=store.tryOwn(id).orElseThrow()) {
                assertThat(store.tryOwn(id)).isEmpty();
                owner.save(owner.load().attempt("reserve-stock",Instant.now()));
                assertThat(store.tryOwn(id)).isEmpty(); // still owned after committed write
            }
            try(var successor=store.tryOwn(id).orElseThrow()) {assertThat(successor.load().execution().pendingStep()).isEqualTo("reserve-stock");}
        }
    }
    @Test void killedConnectionCannotOverwriteSuccessor() throws Exception {
        Saga saga=saga("fence-"+UUID.randomUUID());store.create(saga);
        var stale=store.tryOwn(saga.id()).orElseThrow();stale.load();
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='workflow_app' AND application_name='synkro-workflow-owner'");
        try(var successor=store.tryOwn(saga.id()).orElseThrow()) {
            Saga progress=successor.load().success("validate-customer",null,null,Instant.now());successor.save(progress);
            assertThatThrownBy(()->stale.save(saga)).isInstanceOf(WorkflowError.class);
        } finally { stale.close(); }
        assertThat(store.find(saga.id()).orElseThrow().completedSteps()).containsExactly("validate-customer");
    }
    @Test void isolatedHistoryNoopAndRestrictedEffectivePermissions() throws Exception {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=admin();var s=c.createStatement()) {
            var result=s.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NULL");result.next();assertThat(result.getBoolean(1)).isTrue();
        }
        for(String denied:List.of("DELETE FROM workflow_schema.saga_instance","TRUNCATE workflow_schema.saga_instance",
            "CREATE TABLE workflow_schema.illegal(id int)","SELECT * FROM customers_schema.secret",
            "SELECT * FROM workflow_schema.flyway_schema_history","ALTER TABLE workflow_schema.saga_instance ADD COLUMN illegal int")) {
            try(var c=DriverManager.getConnection(pg.getJdbcUrl(),"workflow_app","disposable-app");var s=c.createStatement()) {
                assertThatThrownBy(()->s.execute(denied)).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("42501");
            }
        }
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),"customers_app","disposable-other");var s=c.createStatement()) {
            assertThatThrownBy(()->s.execute("SELECT * FROM workflow_schema.saga_instance")).isInstanceOf(SQLException.class);
        }
    }
    @Test void invalidStatusAndWriteFailureCannotBeSaved() throws Exception {
        Saga saga=saga("invalid-"+UUID.randomUUID());store.create(saga);
        try(var c=admin();var s=c.prepareStatement("UPDATE workflow_schema.saga_instance SET status='BROKEN' WHERE saga_id=?")) {
            s.setObject(1,saga.id());assertThatThrownBy(s::executeUpdate).isInstanceOf(SQLException.class);
        }
        try(var owner=store.tryOwn(saga.id()).orElseThrow()) {
            sql("REVOKE UPDATE ON workflow_schema.saga_instance FROM workflow_writer");
            try { assertThatThrownBy(()->owner.save(saga.attempt("reserve-stock",Instant.now()))).isInstanceOf(WorkflowError.class); }
            finally {sql("GRANT UPDATE ON workflow_schema.saga_instance TO workflow_writer");}
        }
        assertThat(store.find(saga.id()).orElseThrow().execution().pendingStep()).isNull();
    }
}
