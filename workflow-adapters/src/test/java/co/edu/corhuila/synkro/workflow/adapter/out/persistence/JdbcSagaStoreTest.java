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
    static long advisoryHolders(int first,int second) throws SQLException {
        try(var c=admin();var s=c.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND granted AND objsubid=2 AND classid::bigint=? AND objid::bigint=?")) {
            s.setLong(1,Integer.toUnsignedLong(first));s.setLong(2,Integer.toUnsignedLong(second));
            try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    @Test void leaseHoldsTheWorkflowNamespaceKeyAndReleasesIt() throws Exception {
        Saga saga=saga("namespace-"+UUID.randomUUID());store.create(saga);int second=AdvisoryKeys.second(saga.id());
        try(var owner=store.tryOwn(saga.id()).orElseThrow()) {
            assertThat(advisoryHolders(AdvisoryKeys.FIRST,second)).isEqualTo(1);
            assertThat(advisoryHolders(0x52544F4B,second)).isZero(); // Auth refresh key space untouched
            owner.save(owner.load().attempt("reserve-stock",Instant.now()));
            assertThat(advisoryHolders(AdvisoryKeys.FIRST,second)).isEqualTo(1);
        }
        assertThat(advisoryHolders(AdvisoryKeys.FIRST,second)).isZero();
    }
    @Test void attemptMetadataLivesOnlyUnderThePrivateExecutionRecordAndSurvivesANewStoreInstance() throws Exception {
        Saga saga=saga("attempts-"+UUID.randomUUID());store.create(saga);
        try(var owner=store.tryOwn(saga.id()).orElseThrow()) {
            Saga attempted=owner.load().attempt("reserve-stock",Instant.now()).attempt("reserve-stock",Instant.now());owner.save(attempted);
        }
        try(var c=admin();var s=c.prepareStatement("SELECT step_results->'execution'->>'attempts',step_results->'execution'->>'pendingStep',step_results::text,error_detail,failed_step,array_to_string(completed_steps,',') FROM workflow_schema.saga_instance WHERE saga_id=?")) {
            s.setObject(1,saga.id());try(var r=s.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("2");assertThat(r.getString(2)).isEqualTo("reserve-stock");
                assertThat(r.getString(4)).isNull();assertThat(r.getString(5)).isNull();assertThat(r.getString(6)).isEmpty();
            }
        }
        var ds=new PGSimpleDataSource();ds.setURL(pg.getJdbcUrl());ds.setUser("workflow_app");ds.setPassword("disposable-app");
        var restarted=new JdbcSagaStore(ds,JsonMapper.builder().addModule(new JavaTimeModule()).build());
        assertThat(restarted.find(saga.id()).orElseThrow().execution().attempts()).isEqualTo(2); // a restart does not reset the budget
        assertThat(restarted.create(saga(saga.idempotencyKey())).saga().execution().attempts()).isEqualTo(2); // a replayed POST does not replenish it
    }
    @Test void sagasWithCollidingSecondKeysSerializeButKeepTheirOwnRows() throws Exception {
        Saga first=saga("collide-a-"+UUID.randomUUID());
        UUID other=new UUID(first.id().getMostSignificantBits()^0x1234L,first.id().getLeastSignificantBits()^(0x1234L<<32));
        assertThat(AdvisoryKeys.second(other)).isEqualTo(AdvisoryKeys.second(first.id()));
        Saga second=new Saga(other,"collide-b-"+UUID.randomUUID(),first.initiatedBy(),first.input(),first.status(),first.completedSteps(),null,null,null,null,first.execution(),first.createdAt(),first.updatedAt());
        store.create(first);store.create(second);
        try(var owner=store.tryOwn(first.id()).orElseThrow()) {
            assertThat(store.tryOwn(other)).isEmpty(); // same key serializes
            assertThat(owner.load().id()).isEqualTo(first.id()); // but identity never aliases
            owner.save(owner.load().attempt("reserve-stock",Instant.now()));
        }
        assertThat(store.find(other).orElseThrow().execution().pendingStep()).isNull();
        try(var owner=store.tryOwn(other).orElseThrow()) {assertThat(owner.load().id()).isEqualTo(other);}
    }
    @Test void concurrentSessionsCompetingForOneSagaYieldASingleLeaseHolder() throws Exception {
        Saga saga=saga("compete-"+UUID.randomUUID());store.create(saga);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var gate=new CyclicBarrier(6);var acquisitions=new CountDownLatch(6);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(()->{
                gate.await();var owned=store.tryOwn(saga.id());acquisitions.countDown();
                if(owned.isEmpty())return false;
                try(var lease=owned.get()){
                    assertThat(acquisitions.await(10,TimeUnit.SECONDS)).isTrue();
                    return true;
                }
            }));
            int winners=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))winners++;
            assertThat(winners).isEqualTo(1);
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
    @Test void checksumProtectionAndDisposableRollbackRebuild() throws Exception {
        Path copy=Files.createTempDirectory("workflow-checksum-");
        try {
            try(var paths=Files.walk(ROOT.resolve("db"))) {
                for(Path path:paths.toList()) {
                    Path target=copy.resolve("db").resolve(ROOT.resolve("db").relativize(path));
                    if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
                }
            }
            Files.writeString(copy.resolve("db/01_ddl/03_tables/V002__create_saga_instance.sql"),"\n-- changed checksum\n",StandardOpenOption.APPEND);
            assertThatThrownBy(()->migration(copy).validate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        } finally {
            try(var paths=Files.walk(copy)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        var scripts=new ArrayList<Path>();try(var paths=Files.walk(ROOT.resolve("db/05_rollbacks"))){paths.filter(p->p.toString().endsWith(".sql")).forEach(scripts::add);}
        scripts.sort(Comparator.comparing((Path p)->p.getFileName().toString()).reversed());
        for(Path script:scripts){if(script.getFileName().toString().startsWith("U001"))sql("DROP TABLE workflow_schema.flyway_schema_history");sql(Files.readString(script));}
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);assertThat(store.healthy()).isTrue();
    }
}
