package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.channels.ClosedChannelException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Transport classification of {@link HttpSaleParticipants}, observed only through its public operations
 * and through the use case. This is transport classification, not participant acceptance.
 */
class RetryPolicyRemediationTest {
    final UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),reservation=UUID.randomUUID(),caller=UUID.randomUUID();
    final SaleInput input=new SaleInput(customer,List.of(new SaleInput.Line(product,2)));
    final List<String> seen=new CopyOnWriteArrayList<>();
    HttpServer server;ExecutorService fixtureExecutor;HttpClient transport;HttpSaleParticipants client;volatile int status=200;

    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());exchange.getRequestBody().readAllBytes();
            byte[] bytes="not-a-contract-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        fixtureExecutor=Executors.newCachedThreadPool();server.setExecutor(fixtureExecutor);server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        transport=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        client=new HttpSaleParticipants(transport,JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofSeconds(10));
    }
    @AfterEach void stop(){transport.close();server.stop(0);fixtureExecutor.shutdownNow();}

    // ---------- helpers ----------
    Saga reservedSaga() {
        return Saga.create(UUID.randomUUID(),"retry-regression",caller,input,"trace",Instant.EPOCH)
            .success("validate-customer",null,null,Instant.EPOCH)
            .success("reserve-stock",new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),null,Instant.EPOCH);
    }
    HttpSaleParticipants faulty(Throwable failure) {
        URI base=URI.create("http://participant");
        return new HttpSaleParticipants(new FaultTransport(()->CompletableFuture.failedFuture(failure)),JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofSeconds(10));
    }
    ParticipantFailure.Kind kindOf(HttpSaleParticipants participant) {
        Throwable thrown=catchThrowable(()->participant.register(reservedSaga(),"key","trace"));
        assertThat(thrown).isInstanceOf(ParticipantFailure.class);
        return ((ParticipantFailure)thrown).kind();
    }
    static IOException io(Throwable cause){return new IOException("wrapper",cause);}
    static <T extends Throwable> T caused(T outer,Throwable cause){outer.initCause(cause);return outer;}
    Memory run(SaleParticipants participants,Saga row,AtomicInteger pauses) {
        var memory=new Memory(row);
        new RegisterSale(memory,memory,participants,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),UUID::randomUUID,n->pauses.incrementAndGet(),3).execute(row.id());
        return memory;
    }
    void assertUnresolvedAfterSingleAttempt(HttpSaleParticipants participant) {
        var pauses=new AtomicInteger();var memory=run(participant,reservedSaga(),pauses);
        assertThat(memory.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(memory.row.failedStep()).isNull();
        assertThat(memory.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");assertThat(memory.row.reservation().reservationId()).isEqualTo(reservation);
        assertThat(memory.row.execution().attempts()).isEqualTo(1);assertThat(pauses).hasValue(0);
    }

    // ---------- HTTP statuses ----------
    @Test void approvedStatusesAreTransient() {
        for(int code:List.of(429,500,502,503,504)) {
            status=code;
            assertThatThrownBy(()->client.reserve(input,"key","trace")).as("status "+code).isInstanceOfSatisfying(ParticipantFailure.class,e->assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.TRANSIENT));
        }
    }
    @Test void requestTimeoutAndEveryOtherUnlistedStatusAreNotTransient() {
        for(int code:List.of(408,409,410,415,418,425,451,501,505,507,511,599)) {
            status=code;
            assertThatThrownBy(()->client.reserve(input,"key","trace")).as("status "+code).isInstanceOfSatisfying(ParticipantFailure.class,e->assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.INVALID_RESPONSE));
        }
    }

    // ---------- transport failures that are retried ----------
    @Test void recognizedTransportFailuresAreTransientDirectOrWrapped() {
        var recognized=List.<Throwable>of(new EOFException("lost"),new ConnectException("refused"),new SocketException("reset"),new SocketTimeoutException("slow"),
            new HttpTimeoutException("timeout"),new HttpConnectTimeoutException("connect timeout"),new ClosedChannelException());
        for(Throwable cause:recognized) {
            var shapes=List.<Throwable>of(cause,io(cause),new CompletionException(cause),new ExecutionException(cause),
                new CompletionException(io(cause)),new ExecutionException(new CompletionException(cause)),io(io(cause)));
            for(Throwable shape:shapes)
                assertThat(kindOf(faulty(shape))).as(cause+" as "+shape).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
        }
    }
    @Test void aCallTimeoutIsTransientAndCancelsThePendingRequest() {
        var pending=new CompletableFuture<HttpResponse<Object>>();
        URI base=URI.create("http://participant");
        var slow=new HttpSaleParticipants(new FaultTransport(()->pending),JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofMillis(50));
        assertThat(kindOf(slow)).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
        assertThat(pending).isCancelled();
    }

    // ---------- transport failures that must never be retried ----------
    static class CustomIo extends IOException {CustomIo(){super("custom");}}
    @Test void arbitraryAndUnclassifiedFailuresAreNeverTransient() {
        var denied=List.<Throwable>of(new IOException("generic"),new CustomIo(),new java.io.FileNotFoundException("x"),new java.io.UncheckedIOException(new IOException("x")),
            new BindException("bind"),new NoRouteToHostException("route"),new PortUnreachableException("port"),
            new javax.net.ssl.SSLHandshakeException("tls"),new javax.net.ssl.SSLException("tls"),new UnknownHostException("dns"),
            new java.nio.channels.UnresolvedAddressException(),new ProtocolException("protocol"),new IllegalStateException("bug"),
            new NullPointerException(),new ClassCastException(),new RuntimeException("generic"));
        for(Throwable cause:denied) {
            for(Throwable shape:List.<Throwable>of(cause,io(cause),new CompletionException(cause),new ExecutionException(cause),new CompletionException(io(cause)))) {
                var kind=kindOf(faulty(shape));
                assertThat(kind).as(cause+" as "+shape).isNotEqualTo(ParticipantFailure.Kind.TRANSIENT).isNotEqualTo(ParticipantFailure.Kind.REJECTED);
            }
        }
    }
    @Test void classificationOfTheDocumentedNonTransientCategoriesStaysExact() {
        assertThat(kindOf(faulty(new javax.net.ssl.SSLHandshakeException("tls")))).isEqualTo(ParticipantFailure.Kind.LOCAL_FAILURE);
        assertThat(kindOf(faulty(new UnknownHostException("dns")))).isEqualTo(ParticipantFailure.Kind.LOCAL_FAILURE);
        assertThat(kindOf(faulty(new java.nio.channels.UnresolvedAddressException()))).isEqualTo(ParticipantFailure.Kind.LOCAL_FAILURE);
        assertThat(kindOf(faulty(new IllegalStateException("bug")))).isEqualTo(ParticipantFailure.Kind.LOCAL_FAILURE);
        assertThat(kindOf(faulty(new ProtocolException("protocol")))).isEqualTo(ParticipantFailure.Kind.INVALID_RESPONSE);
        assertThat(kindOf(faulty(new IOException("generic")))).isEqualTo(ParticipantFailure.Kind.LOCAL_FAILURE);
    }
    @Test void negativeNestedCausesDominateEvenWhenTheOuterFailureLooksTransient() {
        var dominated=List.<Throwable>of(
            caused(new EOFException("outer"),new javax.net.ssl.SSLHandshakeException("tls")),
            caused(new ConnectException("outer"),new UnknownHostException("dns")),
            caused(new SocketTimeoutException("outer"),new ProtocolException("protocol")),
            caused(new HttpTimeoutException("outer"),new java.nio.channels.UnresolvedAddressException()),
            new CompletionException(io(caused(new EOFException("outer"),new IllegalStateException("bug")))),
            new ExecutionException(caused(new ClosedChannelException(),new BindException("bind"))),
            io(caused(new ConnectException("outer"),new NoRouteToHostException("route"))));
        for(Throwable failure:dominated)
            assertThat(kindOf(faulty(failure))).as(failure.toString()).isNotEqualTo(ParticipantFailure.Kind.TRANSIENT);
    }
    @Test void causeCyclesTerminateAndKeepTheirClassification() {
        var eof=new EOFException("a");var wrapper=new IOException("b");eof.initCause(wrapper);wrapper.initCause(eof);
        var tls=new javax.net.ssl.SSLException("a");var other=new IOException("b");tls.initCause(other);other.initCause(tls);
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
            assertThat(kindOf(faulty(eof))).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
            assertThat(kindOf(faulty(new CompletionException(wrapper)))).isEqualTo(ParticipantFailure.Kind.TRANSIENT);
            assertThat(kindOf(faulty(tls))).isNotEqualTo(ParticipantFailure.Kind.TRANSIENT);
            assertThat(kindOf(faulty(new CompletionException(other)))).isNotEqualTo(ParticipantFailure.Kind.TRANSIENT);
        });
    }

    // ---------- use-case observation (durable attempt budget) ----------
    @Test void requestTimeoutStatusConsumesOneDurableAttemptAndKeepsTheReservationFrozen() {
        status=408;var pauses=new AtomicInteger();
        var memory=run(client,reservedSaga(),pauses);
        assertThat(seen).containsExactly("POST /api/v1/sales"); // no retry, and no release call to Products
        assertThat(memory.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(memory.row.failedStep()).isNull();
        assertThat(memory.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");
        assertThat(memory.row.execution().attempts()).isEqualTo(1);assertThat(memory.row.execution().pendingStep()).isEqualTo("register-sale");
        assertThat(memory.row.reservation().reservationId()).isEqualTo(reservation);assertThat(pauses).hasValue(0);
    }
    @Test void unclassifiedAndNegativeTransportFailuresSuspendAfterASingleAttempt() {
        assertUnresolvedAfterSingleAttempt(faulty(new IOException("generic")));
        assertUnresolvedAfterSingleAttempt(faulty(io(new javax.net.ssl.SSLHandshakeException("tls"))));
        assertUnresolvedAfterSingleAttempt(faulty(caused(new EOFException("outer"),new ProtocolException("protocol"))));
    }
    @Test void transientFailuresConsumeAtMostThreeAttemptsAndTwoPauses() {
        status=503;var pauses=new AtomicInteger();
        var memory=run(client,reservedSaga(),pauses);
        assertThat(seen).containsExactly("POST /api/v1/sales","POST /api/v1/sales","POST /api/v1/sales");
        assertThat(memory.row.execution().attempts()).isEqualTo(3);assertThat(pauses).hasValue(2);
        assertThat(memory.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(memory.row.errorDetail()).isEqualTo("RETRY_DEFERRED");
        assertThat(memory.row.reservation().reservationId()).isEqualTo(reservation);
    }
    @Test void aRestartedObjectContinuingTheSamePostGetsNoFreshBudget() {
        status=503;var pauses=new AtomicInteger();
        var memory=run(client,reservedSaga(),pauses);int requestsBefore=seen.size();
        assertThat(requestsBefore).isEqualTo(3);
        new RegisterSale(memory,memory,client,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),UUID::randomUUID,n->pauses.incrementAndGet(),3).execute(memory.row.id());
        assertThat(seen).hasSize(requestsBefore);assertThat(memory.row.execution().attempts()).isEqualTo(3);
        assertThat(pauses).hasValue(2);assertThat(memory.row.errorDetail()).isEqualTo("RETRY_DEFERRED");
    }

    // ---------- fixtures ----------
    static class Memory implements SagaStore,ExecutionCoordinator {
        Saga row;Memory(Saga row){this.row=row;}
        public Created create(Saga saga){throw new UnsupportedOperationException();}
        public Optional<Saga> find(UUID id){return Optional.of(row);}
        public List<UUID> running(int limit){return List.of(row.id());}
        public boolean healthy(){return true;}
        public Optional<Lease> tryOwn(UUID id){return Optional.of(new Lease(){public Saga load(){return row;}public void save(Saga saga){row=saga;}public void close(){}});}
    }
    /** Transport whose asynchronous send is entirely scripted; it never touches a socket. */
    static class FaultTransport extends HttpClient {
        final java.util.function.Supplier<CompletableFuture<?>> script;
        FaultTransport(java.util.function.Supplier<CompletableFuture<?>> script){this.script=script;}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(2));}
        public Redirect followRedirects(){return Redirect.NEVER;}
        public Optional<ProxySelector> proxy(){return Optional.empty();}
        public javax.net.ssl.SSLContext sslContext(){try{return javax.net.ssl.SSLContext.getDefault();}catch(Exception e){throw new IllegalStateException(e);}}
        public javax.net.ssl.SSLParameters sslParameters(){return new javax.net.ssl.SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new UnsupportedOperationException();}
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){return (CompletableFuture<HttpResponse<T>>)script.get();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){return sendAsync(request,handler);}
    }
}
