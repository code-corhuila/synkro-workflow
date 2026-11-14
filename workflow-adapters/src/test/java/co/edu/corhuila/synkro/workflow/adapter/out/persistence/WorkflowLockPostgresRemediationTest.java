package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import co.edu.corhuila.synkro.workflow.application.port.out.ExecutionCoordinator;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL: the advisory-lock pair actually taken by {@link JdbcSagaStore} sessions. */
class WorkflowLockPostgresRemediationTest {
    static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.15-alpine3.23")
        .withDatabaseName("synkro").withUsername("postgres").withPassword("disposable-admin");
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static final int AUTH_REFRESH_TOKEN_NAMESPACE=0x52544F4B;
    static final UUID FIRST=UUID.fromString("00000001-0000-0000-0000-000000000000");
    static final UUID SECOND=UUID.fromString("00000000-0000-0000-0000-000000000001"); // same XOR; legacy pair differs from FIRST
    static JdbcSagaStore store;

    static Connection admin() throws SQLException {return DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());}
    static void sql(String text) throws SQLException {try(var c=admin();var s=c.createStatement()){s.execute(text);}}

    @BeforeAll static void setup() throws Exception {
        pg.start();
        sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE workflow_app LOGIN PASSWORD 'disposable-app'");
        var flyway=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())
            .schemas("workflow_schema").defaultSchema("workflow_schema")
            .locations("filesystem:"+ROOT.resolve("db/01_ddl"),"filesystem:"+ROOT.resolve("db/03_dcl"))
            .cleanDisabled(true).validateMigrationNaming(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        var ds=new PGSimpleDataSource();ds.setURL(pg.getJdbcUrl());ds.setUser("workflow_app");ds.setPassword("disposable-app");
        store=new JdbcSagaStore(ds,JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }
    @AfterAll static void stop(){pg.stop();}

    Saga saga(UUID id) {
        return Saga.create(id,"lock-"+UUID.randomUUID(),UUID.randomUUID(),new SaleInput(UUID.randomUUID(),List.of(new SaleInput.Line(UUID.randomUUID(),1))),"lock-trace",Instant.now());
    }
    /** Advisory locks (first key, second key, objsubid) currently held by workflow owner sessions. */
    static List<long[]> ownerLocks() throws SQLException {
        String query="SELECT l.classid::bigint,l.objid::bigint,l.objsubid FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid "
            +"WHERE l.locktype='advisory' AND l.granted AND a.usename='workflow_app' AND a.application_name='synkro-workflow-owner' ORDER BY 1,2";
        var rows=new ArrayList<long[]>();
        try(var c=admin();var s=c.createStatement();var r=s.executeQuery(query)){while(r.next())rows.add(new long[]{r.getLong(1),r.getLong(2),r.getLong(3)});}
        return rows;
    }
    static long unsigned(int key){return Integer.toUnsignedLong(key);}
    static void awaitNoOwnerLocks() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ownerLocks().isEmpty()) {
            if(System.nanoTime()>deadline)throw new AssertionError("owner locks were not released in time");
            Thread.sleep(25);
        }
    }
    @BeforeEach @AfterEach void noLeftovers() throws Exception {awaitNoOwnerLocks();}

    @Test void sessionHoldsTheNamespacedPairAndKeepsItAfterCommittedProgress() throws Exception {
        Saga created=saga(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));store.create(created);
        long[] expected={unsigned(WorkflowLockKey.NAMESPACE),unsigned(0x4AE455D2),2};
        try(var owner=store.tryOwn(created.id()).orElseThrow()) {
            assertThat(ownerLocks()).hasSize(1);assertThat(ownerLocks().getFirst()).containsExactly(expected);
            owner.save(owner.load().attempt("validate-customer",Instant.now())); // committed write
            assertThat(ownerLocks()).hasSize(1);assertThat(ownerLocks().getFirst()).containsExactly(expected);
        }
        awaitNoOwnerLocks();
    }
    @Test void negativeFoldedValueIsLockedAsItsUnsignedObjectId() throws Exception {
        UUID id=UUID.fromString("deadbeef-cafe-babe-0123-456789abcdef");store.create(saga(id));
        try(var owner=store.tryOwn(id).orElseThrow()) {
            assertThat(ownerLocks()).hasSize(1);
            assertThat(ownerLocks().getFirst()).containsExactly(unsigned(WorkflowLockKey.NAMESPACE),unsigned(0x9CDB8CD9),2);
        }
    }
    @Test void workflowNamespaceNeverBlocksNorIsBlockedByTheAuthPair() throws Exception {
        UUID id=UUID.fromString("00000003-0000-0000-0000-000000000000");int value=WorkflowLockKey.value(id);store.create(saga(id));
        String pair=AUTH_REFRESH_TOKEN_NAMESPACE+","+value;
        try(var auth=admin();var s=auth.createStatement()) {
            s.execute("SELECT pg_advisory_lock("+pair+")"); // Auth holds its pair with the same second key
            var owned=store.tryOwn(id);
            assertThat(owned).isPresent();owned.get().close();
            s.execute("SELECT pg_advisory_unlock("+pair+")");
            try(var owner=store.tryOwn(id).orElseThrow()) { // and the reverse: a workflow owner does not block Auth
                try(var r=s.executeQuery("SELECT pg_try_advisory_lock("+pair+")")){r.next();assertThat(r.getBoolean(1)).isTrue();}
                s.execute("SELECT pg_advisory_unlock("+pair+")");
            }
        }
    }
    @Test void differentUuidsWithTheSameFoldedValueSerializeWithoutAliasingTheirRows() throws Exception {
        assertThat(WorkflowLockKey.value(FIRST)).isEqualTo(WorkflowLockKey.value(SECOND));
        Saga first=saga(FIRST),second=saga(SECOND);store.create(first);store.create(second);
        try(var owner=store.tryOwn(FIRST).orElseThrow()) {
            assertBlocked(SECOND); // shared key: serialized, never both owners
            assertThat(owner.load().id()).isEqualTo(FIRST);
        }
        try(var successor=store.tryOwn(SECOND).orElseThrow()) {
            assertThat(successor.load().id()).isEqualTo(SECOND);
            assertThat(successor.load().idempotencyKey()).isEqualTo(second.idempotencyKey()).isNotEqualTo(first.idempotencyKey());
            assertBlocked(FIRST);
        }
    }
    void assertBlocked(UUID id) {
        var unexpected=store.tryOwn(id);
        unexpected.ifPresent(ExecutionCoordinator.Lease::close);
        assertThat(unexpected).isEmpty();
    }
    @Test void aLegacyPairThatWouldCollideWithAuthIsNowIsolated() throws Exception {
        UUID id=UUID.fromString("52544f4b-0000-0000-0000-000000000001");store.create(saga(id));
        try(var auth=admin();var lock=auth.prepareStatement("SELECT pg_advisory_lock(?,?)")) {
            lock.setInt(1,AUTH_REFRESH_TOKEN_NAMESPACE);lock.setInt(2,1);lock.execute();
            var workflow=store.tryOwn(id);
            workflow.ifPresent(ExecutionCoordinator.Lease::close);
            assertThat(workflow).as("Auth's RTOK/1 lease must not block Workflow").isPresent();
        }
    }
    @Test void concurrentAttemptsOnOneSagaYieldExactlyOneOwner() throws Exception {
        UUID id=UUID.randomUUID();store.create(saga(id));int contenders=8;
        var leases=new ArrayList<ExecutionCoordinator.Lease>();
        try(var pool=Executors.newFixedThreadPool(contenders)) {
            var gate=new CyclicBarrier(contenders);var futures=new ArrayList<Future<Optional<ExecutionCoordinator.Lease>>>();
            for(int i=0;i<contenders;i++)futures.add(pool.submit(()->{gate.await();return store.tryOwn(id);}));
            for(var f:futures){var owned=f.get(15,TimeUnit.SECONDS);owned.ifPresent(leases::add);}
            assertThat(leases).hasSize(1);
        } finally {leases.forEach(ExecutionCoordinator.Lease::close);}
    }
    @Test void killedOwnerCannotSaveAfterItsSuccessorOwns() throws Exception {
        Saga saga=saga(UUID.randomUUID());store.create(saga);
        var stale=store.tryOwn(saga.id()).orElseThrow();stale.load();
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='workflow_app' AND application_name='synkro-workflow-owner'");
        awaitNoOwnerLocks(); // bounded polling instead of a timing guess
        try(var successor=store.tryOwn(saga.id()).orElseThrow()) {
            successor.save(successor.load().success("validate-customer",null,null,Instant.now()));
            assertThatThrownBy(()->stale.save(saga)).isInstanceOf(WorkflowError.class);
        } finally {stale.close();}
        assertThat(store.find(saga.id()).orElseThrow().completedSteps()).containsExactly("validate-customer");
    }
}
