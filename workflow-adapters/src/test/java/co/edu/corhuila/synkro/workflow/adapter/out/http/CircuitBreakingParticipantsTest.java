package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.synkro.workflow.application.port.out.SaleParticipants;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class CircuitBreakingParticipantsTest {
    final CircuitBreakerTest.TestClock clock=new CircuitBreakerTest.TestClock();
    final UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),reservation=UUID.randomUUID(),sale=UUID.randomUUID(),caller=UUID.randomUUID();
    final SaleInput input=new SaleInput(customer,List.of(new SaleInput.Line(product,2)));
    final Reservation reserved=new Reservation(reservation,List.of(new Reservation.Line(product,2,125)));
    final Saga saga=Saga.create(UUID.randomUUID(),"breaker-key",caller,input,"trace",Instant.EPOCH).success("validate-customer",null,null,Instant.EPOCH).success("reserve-stock",reserved,null,Instant.EPOCH);
    final Fake fake=new Fake();
    final CircuitBreakingParticipants guarded=new CircuitBreakingParticipants(fake,clock);

    final class Fake implements SaleParticipants {
        final Map<String,AtomicInteger> calls=new HashMap<>();
        volatile RuntimeException failure;
        int count(String op){return calls.computeIfAbsent(op,k->new AtomicInteger()).get();}
        void enter(String op){calls.computeIfAbsent(op,k->new AtomicInteger()).incrementAndGet();if(failure!=null)throw failure;}
        public void validateCustomer(UUID id,String key,String trace){enter("customers");}
        public Reservation reserve(SaleInput in,String key,String trace){enter("reserve");return reserved;}
        public UUID register(Saga s,String key,String trace){enter("sales");return sale;}
        public void release(Reservation r,String key,String trace){enter("release");}
    }
    static ParticipantFailure of(ParticipantFailure.Kind kind){return new ParticipantFailure(kind);}
    void customers(){guarded.validateCustomer(customer,"k","trace");}
    void products(){guarded.reserve(input,"k","trace");}
    void sales(){guarded.register(saga,"k","trace");}
    void release(){guarded.release(reserved,"k","trace");}
    void tolerate(Runnable call){try{call.run();}catch(ParticipantFailure ignored){}}
    ParticipantFailure.Kind kindOf(Runnable call){try{call.run();return null;}catch(ParticipantFailure f){return f.kind();}}

    @Test void eachParticipantOpensFailsFastProbesAndRecovers() {
        Map<String,Runnable> participants=Map.of("customers",this::customers,"products",this::products,"sales",this::sales);
        for(var entry:participants.entrySet()) {
            var f=new Fake();var g=new CircuitBreakingParticipants(f,clock);
            Map<String,Runnable> local=Map.of("customers",()->g.validateCustomer(customer,"k","t"),"products",()->g.reserve(input,"k","t"),"sales",()->g.register(saga,"k","t"));
            Runnable call=local.get(entry.getKey());f.failure=of(ParticipantFailure.Kind.TRANSIENT);
            for(int i=0;i<3;i++)assertThat(kindOf(call)).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
            assertThat(f.count(entry.getKey().equals("products")?"reserve":entry.getKey())).isEqualTo(3);
            assertThat(kindOf(call)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN); // fail-fast: no fourth call
            assertThat(f.count(entry.getKey().equals("products")?"reserve":entry.getKey())).isEqualTo(3);
            clock.advance(Duration.ofSeconds(15));
            assertThat(kindOf(call)).isEqualTo(ParticipantFailure.Kind.TRANSIENT); // failed probe reopens
            assertThat(kindOf(call)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
            clock.advance(Duration.ofSeconds(15));f.failure=null;
            assertThat(kindOf(call)).isNull(); // probe success closes
            assertThat(kindOf(call)).isNull();
        }
    }
    @Test void anOpenCircuitDoesNotAffectTheOtherParticipants() {
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);for(int i=0;i<3;i++)tolerate(this::products);
        assertThat(kindOf(this::products)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
        fake.failure=null;
        customers();sales(); // untouched circuits still dispatch
        assertThat(fake.count("customers")).isEqualTo(1);assertThat(fake.count("sales")).isEqualTo(1);
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);for(int i=0;i<3;i++)tolerate(this::sales);
        fake.failure=null;customers();
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
    }
    @Test void reserveAndReleaseShareTheSingleProductsCircuit() {
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);
        tolerate(this::products);tolerate(this::release);tolerate(this::products);
        assertThat(kindOf(this::release)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
        assertThat(kindOf(this::products)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
        assertThat(fake.count("release")).isEqualTo(1);
    }
    @Test void onlyTransientFailuresCountTowardOpening() {
        for(var kind:List.of(ParticipantFailure.Kind.REJECTED,ParticipantFailure.Kind.AUTHORIZATION,ParticipantFailure.Kind.INVALID_RESPONSE,ParticipantFailure.Kind.LOCAL_FAILURE)) {
            fake.failure=of(kind);
            for(int i=0;i<10;i++)assertThat(kindOf(this::sales)).isEqualTo(kind); // never fail-fast
        }
        fake.failure=new IllegalStateException("secret-sentinel");
        for(int i=0;i<10;i++)assertThatThrownBy(this::sales).isInstanceOf(IllegalStateException.class);
        assertThat(fake.count("sales")).isEqualTo(50);
    }
    @Test void aNonSuccessfulProbeNeverClosesTheCircuitNorBlocksHalfOpenForever() {
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);for(int i=0;i<3;i++)tolerate(this::sales);
        clock.advance(Duration.ofSeconds(15));
        fake.failure=of(ParticipantFailure.Kind.REJECTED);
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.REJECTED); // probe consumed, neutral
        fake.failure=new IllegalArgumentException();
        assertThatThrownBy(this::sales).isInstanceOf(IllegalArgumentException.class); // a probe again, still allowed
        fake.failure=of(ParticipantFailure.Kind.INVALID_RESPONSE);
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.INVALID_RESPONSE);
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.TRANSIENT); // still probing, now reopens
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
        clock.advance(Duration.ofSeconds(15));fake.failure=null;sales();
        sales();assertThat(fake.count("sales")).isEqualTo(3+1+1+1+1+2);
    }
    @Test void aSuccessfulCallOnlyResetsItsOwnConsecutiveCount() {
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);tolerate(this::sales);tolerate(this::sales);
        fake.failure=null;sales();
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);tolerate(this::sales);tolerate(this::sales);
        assertThat(kindOf(this::sales)).isEqualTo(ParticipantFailure.Kind.TRANSIENT); // third consecutive since reset
    }
    @Test void failFastRefusalIsNotARejectionAndCarriesNoSensitiveText() {
        fake.failure=of(ParticipantFailure.Kind.TRANSIENT);for(int i=0;i<3;i++)tolerate(this::sales);
        assertThatThrownBy(this::sales).isInstanceOf(ParticipantFailure.class).hasMessage("CIRCUIT_OPEN").hasNoCause()
            .extracting(e->((ParticipantFailure)e).kind()).isNotEqualTo(ParticipantFailure.Kind.REJECTED);
    }
    @Test void breakerStateIsNotPartOfTheSagaOrItsPrivateExecutionRecord() {
        assertThat(Arrays.stream(Saga.class.getRecordComponents()).map(c->c.getName().toLowerCase())).noneMatch(n->n.contains("circuit")||n.contains("breaker"));
        assertThat(Arrays.stream(Saga.Execution.class.getRecordComponents()).map(c->c.getName())).containsExactly("correlationId","pendingStep","attempts");
    }

    // ---- CONTROLLED HTTP (local fixture server, real HttpSaleParticipants). Not evidence against the real services. ----
    @Test void controlledHttpParticipantFailsRepeatedlyThenRecovers() throws Exception {
        var hits=new AtomicInteger();var status=new AtomicInteger(503);var activeBody=new AtomicReference<>("{}");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{
            hits.incrementAndGet();ex.getRequestBody().readAllBytes();
            byte[] bytes=activeBody.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(status.get(),bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });
        var executor=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(executor);server.start();
        var transport=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
        try {
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var participants=new CircuitBreakingParticipants(new HttpSaleParticipants(transport,JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofSeconds(5)),clock);
            for(int i=0;i<3;i++)assertThat(kindOf(()->participants.validateCustomer(customer,"k","trace"))).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
            assertThat(hits).hasValue(3);
            assertThat(kindOf(()->participants.validateCustomer(customer,"k","trace"))).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN);
            assertThat(hits).hasValue(3); // no network call while OPEN
            clock.advance(Duration.ofSeconds(15));status.set(200);
            activeBody.set("{\"customerId\":\""+customer+"\",\"name\":\"Buyer\",\"identityDocument\":\"1\",\"registrationDate\":\"2026-10-12T12:00:00Z\",\"active\":true}");
            assertThat(kindOf(()->participants.validateCustomer(customer,"k","trace"))).isNull(); // validated success closes
            assertThat(hits).hasValue(4);
            assertThat(kindOf(()->participants.validateCustomer(customer,"k","trace"))).isNull();
            // a malformed 200 is a contract failure, not a validated success: it must not be counted as an outage either
            activeBody.set("{broken");
            for(int i=0;i<5;i++)assertThat(kindOf(()->participants.validateCustomer(customer,"k","trace"))).isEqualTo(ParticipantFailure.Kind.INVALID_RESPONSE);
        } finally {transport.close();server.stop(0);executor.shutdownNow();}
    }
}
