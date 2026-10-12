package co.edu.corhuila.synkro.workflow.adapter.out.http;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class HttpSaleParticipantsTest {
    HttpServer server; HttpSaleParticipants client; HttpClient transport; java.util.concurrent.ExecutorService fixtureExecutor;
    final UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),reservation=UUID.randomUUID(),sale=UUID.randomUUID(),caller=UUID.randomUUID();
    final SaleInput input=new SaleInput(customer,List.of(new SaleInput.Line(product,2)));
    final List<String> seen=new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile int status=200; volatile String body; volatile String sentBody; volatile String contentType="application/json";
    String reservationBody(String status) {return "{\"reservationId\":\""+reservation+"\",\"status\":\""+status+"\",\"createdAt\":\"2026-10-12T12:00:00Z\",\"releasedAt\":\"2026-10-12T12:01:00Z\",\"lines\":[{\"lineId\":\""+UUID.randomUUID()+"\",\"productId\":\""+product+"\",\"quantity\":2,\"unitPriceCents\":125}]}";}
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("Authorization")+" "+exchange.getRequestHeaders().getFirst("Idempotency-Key")+" "+exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",contentType);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });fixtureExecutor=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(fixtureExecutor);server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        transport=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(); client=new HttpSaleParticipants(transport,JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofSeconds(10));
    }
    @AfterEach void stop(){transport.close();server.stop(0);fixtureExecutor.shutdownNow();}
    @Test void customerCredentialCorrelationAndActiveValidation() {
        body="{\"customerId\":\""+customer+"\",\"name\":\"Buyer\",\"identityDocument\":\"123\",\"registrationDate\":\"2026-10-12T12:00:00Z\",\"active\":true}";
        client.validateCustomer(customer,"saga:validate-customer","trace");
        assertThat(seen).containsExactly("GET /api/v1/customers/"+customer+" Bearer service-sentinel saga:validate-customer trace");
        body=body.replace("true","false");
        assertThatThrownBy(()->client.validateCustomer(customer,"key","trace")).isInstanceOf(ParticipantFailure.class)
            .extracting(e->((ParticipantFailure)e).kind()).isEqualTo(ParticipantFailure.Kind.REJECTED);
        status=404;body=error("NOT_FOUND");assertThatThrownBy(()->client.validateCustomer(customer,"key","trace")).hasMessage("REJECTED");
    }
    @Test void reserveSendsAllLinesAndChecksFrozenPricesAndMatchingInput() {
        body=reservationBody("RESERVED");status=201;
        Reservation r=client.reserve(input,"saga:reserve-stock","trace");assertThat(r.lines().getFirst().unitPriceCents()).isEqualTo(125);
        assertThat(sentBody).contains("\"lines\"","\"quantity\":2").doesNotContain("price","createdBy");
        body=body.replace("\"quantity\":2","\"quantity\":3");
        assertThatThrownBy(()->client.reserve(input,"key","trace")).isInstanceOf(ParticipantFailure.class);
    }
    @Test void salesAcceptsOnlyValidatedMatchingResultAndSendsNoComputedAmounts() {
        Saga saga=Saga.create(UUID.randomUUID(),"some-key",caller,input,"trace",Instant.now()).success("validate-customer",null,null,Instant.now())
            .success("reserve-stock",new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),null,Instant.now());
        body="{\"saleId\":\""+sale+"\",\"customerId\":\""+customer+"\",\"createdBy\":\""+caller+"\",\"date\":\"2026-10-12T12:00:00Z\",\"totalCents\":250,\"active\":true,\"details\":[{\"detailId\":\""+UUID.randomUUID()+"\",\"productId\":\""+product+"\",\"quantity\":2,\"unitPriceCents\":125,\"subtotalCents\":250,\"active\":true}]}";
        assertThat(client.register(saga,"saga:register-sale","trace")).isEqualTo(sale);
        assertThat(sentBody).contains("\"createdBy\":\""+caller+"\"","\"unitPriceCents\":125").doesNotContain("subtotalCents","totalCents");
        body=body.replace("\"totalCents\":250","\"totalCents\":251");
        assertThatThrownBy(()->client.register(saga,"key","trace")).isInstanceOf(ParticipantFailure.class);
        status=404;assertThatThrownBy(()->client.register(saga,"key","trace")).hasMessage("INVALID_RESPONSE");
    }
    @Test void allNormalizedLinesTravelInOneReservationOperation() throws Exception {
        UUID second=UUID.randomUUID();SaleInput command=new SaleInput(customer,List.of(new SaleInput.Line(second,1),new SaleInput.Line(product,2)));
        var mapper=JsonMapper.builder().build();var response=mapper.readTree(reservationBody("RESERVED"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)response.get("lines")).addObject().put("lineId",UUID.randomUUID().toString()).put("productId",second.toString()).put("quantity",1).put("unitPriceCents",200);
        body=mapper.writeValueAsString(response);status=201;
        Reservation result=client.reserve(command,"saga:reserve-stock","trace");
        assertThat(result.lines().stream().map(l->new SaleInput.Line(l.productId(),l.quantity())).toList()).isEqualTo(command.lines());
        assertThat(seen).hasSize(1);assertThat(mapper.readTree(sentBody).get("lines").size()).isEqualTo(2);
        assertThat(sentBody).contains(product.toString(),second.toString()).doesNotContain("unitPriceCents","createdBy");
    }
    @Test void readReleasedReservationAvoidsRepeatRelease() {
        body=reservationBody("RELEASED");client.release(new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),"saga:release-stock","trace");
        assertThat(seen).hasSize(1);assertThat(seen.getFirst()).startsWith("GET /api/v1/stock-reservations/"+reservation);
        status=404;body=error("NOT_FOUND");assertThatThrownBy(()->client.release(new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),"key","trace")).hasMessage("REJECTED");
    }
    @Test void statusClassificationAndSafeErrors() {
        body="sentinel-driver-password-url";
        for(int code:List.of(400,404,422,401,403,408,429,500,502,503,504,501,505,409,415,418)) {
            status=code;
            ParticipantFailure.Kind expected=code==401||code==403?ParticipantFailure.Kind.AUTHORIZATION:Set.of(408,429,500,502,503,504).contains(code)?ParticipantFailure.Kind.TRANSIENT:ParticipantFailure.Kind.INVALID_RESPONSE;
            assertThatThrownBy(()->client.reserve(input,"key","trace")).isInstanceOf(ParticipantFailure.class)
                .hasMessage(expected.name()).extracting(e->((ParticipantFailure)e).kind()).isEqualTo(expected);
        }
        status=200;body="{broken";
        assertThatThrownBy(()->client.reserve(input,"key","trace")).hasMessage("INVALID_RESPONSE");
    }

    String error(String code) {return "{\"error\":\""+code+"\",\"message\":\"Rejected\",\"traceId\":\"trace\"}";}
    Saga reservedSaga() {return Saga.create(UUID.randomUUID(),"error-regression",caller,input,"trace",Instant.EPOCH)
        .success("validate-customer",null,null,Instant.EPOCH)
        .success("reserve-stock",new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),null,Instant.EPOCH);}
    HttpSaleParticipants localClient(ObjectMapper mapper,URI base,java.util.function.Supplier<String> credential) {
        return new HttpSaleParticipants(transport,mapper,base,base,base,credential,Duration.ofSeconds(10));
    }
    @Test void documentedErrorEnvelopesConfirmOnlyTheirOwningRejections() {
        for(int code:List.of(400,422)) {
            status=code;body=error(code==400?"VALIDATION_ERROR":"BUSINESS_RULE_VIOLATION");
            assertThatThrownBy(()->client.reserve(input,"key","trace")).hasMessage("REJECTED");
            assertThatThrownBy(()->client.register(reservedSaga(),"key","trace")).hasMessage("REJECTED");
        }
        body=error("INVALID_STATUS_TRANSITION");
        assertThatThrownBy(()->client.register(reservedSaga(),"key","trace")).hasMessage("REJECTED");
        status=404;body=error("NOT_FOUND");
        assertThatThrownBy(()->client.register(reservedSaga(),"key","trace")).hasMessage("INVALID_RESPONSE");
        status=422;body=error("BUSINESS_RULE_VIOLATION").replace("}",",\"details\":[{\"field\":\"details\",\"message\":\"invalid\"}]}");
        assertThatThrownBy(()->client.register(reservedSaga(),"key","trace")).hasMessage("REJECTED");
    }
    @Test void malformedOrContradictoryRejectionCannotAuthorizeCompensation() {
        status=422;
        List<String> invalid=List.of("{broken","null","[]","{}",error("UNKNOWN"),error("INTERNAL_ERROR"),error("VALIDATION_ERROR"),
            error("BUSINESS_RULE_VIOLATION").replace("trace","other"),
            "{\"error\":\"BUSINESS_RULE_VIOLATION\",\"message\":7,\"traceId\":\"trace\"}",
            "{\"error\":\"BUSINESS_RULE_VIOLATION\",\"message\":\"Rejected\"}",
            error("BUSINESS_RULE_VIOLATION").replace("}",",\"details\":{}}"),
            error("BUSINESS_RULE_VIOLATION").replace("}",",\"details\":[{\"field\":\"details\"}]}"),
            error("BUSINESS_RULE_VIOLATION")+" {}",
            error("BUSINESS_RULE_VIOLATION").replace("}",",\"error\":\"INTERNAL_ERROR\"}"));
        for(String invalidBody:invalid) {
            body=invalidBody;seen.clear();assertSuspendedWithoutRetry(client);
            assertThat(seen).hasSize(1).allMatch(s->s.startsWith("POST /api/v1/sales "));
        }
        body=error("BUSINESS_RULE_VIOLATION");contentType="text/html";seen.clear();assertSuspendedWithoutRetry(client);
        assertThat(seen).hasSize(1);
    }
    @Test void wrongStatusEnvelopeAndMalformedSuccessRemainInvalid() {
        for(int code:List.of(400,404)) {
            status=code;body=error("BUSINESS_RULE_VIOLATION");
            assertThatThrownBy(()->client.validateCustomer(customer,"key","trace")).hasMessage("INVALID_RESPONSE");
            assertThatThrownBy(()->client.reserve(input,"key","trace")).hasMessage("INVALID_RESPONSE");
        }
        status=200;body="null";assertSuspendedWithoutRetry(client);
    }
    @Test void oversizedResponseIsAContractFailureWithoutRetryOrRelease() {
        status=422;body="x".repeat(1048577);assertSuspendedWithoutRetry(client);assertThat(seen).hasSize(1);
        assertThatThrownBy(()->client.register(reservedSaga(),"key","trace")).hasMessage("INVALID_RESPONSE");
    }
    @Test void localCredentialConfigurationAndSerializationFailuresAreNotTransportRetries() {
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());body=error("BUSINESS_RULE_VIOLATION");
        var badMapper=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("secret-sentinel"){};
        }};
        List<HttpSaleParticipants> invalid=List.of(localClient(JsonMapper.builder().build(),base,()->{throw new IllegalStateException("secret-sentinel");}),
            localClient(badMapper,base,()->"service-sentinel"),localClient(JsonMapper.builder().build(),URI.create("ftp://invalid"),()->"service-sentinel"),
            localClient(JsonMapper.builder().build(),base,()->"invalid\nheader"));
        for(var participant:invalid) {
            assertThatThrownBy(()->participant.register(reservedSaga(),"key","trace")).hasMessage("LOCAL_FAILURE");
            assertSuspendedWithoutRetry(participant);
        }
        assertThat(seen).isEmpty();
    }
    @Test void asyncProtocolTlsAndProgrammingFailuresDoNotBecomeTransportRetries() {
        for(Throwable cause:List.of(new java.net.ProtocolException("secret-sentinel"),new javax.net.ssl.SSLHandshakeException("secret-sentinel"),
                new java.net.UnknownHostException("secret-sentinel"),new IllegalStateException("secret-sentinel"))) {
            String expected=cause instanceof java.net.ProtocolException?"INVALID_RESPONSE":"LOCAL_FAILURE";
            var participant=faultClient(cause);
            assertThatThrownBy(()->participant.register(reservedSaga(),"key","trace")).hasMessage(expected);
            assertSuspendedWithoutRetry(participant);
        }
    }
    @Test void actualAsyncTransportFailuresRemainBoundedAndRecoverable() {
        for(Throwable cause:List.of(new java.net.ConnectException("connection refused"),new HttpTimeoutException("timeout"),new java.io.EOFException("response lost"))) {
            var participant=faultClient(cause);
            assertThatThrownBy(()->participant.register(reservedSaga(),"key","trace")).hasMessage("TRANSIENT");
            var memory=new StoredSaga(reservedSaga());var pauses=new AtomicInteger();
            new RegisterSale(memory,memory,participant,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),UUID::randomUUID,n->pauses.incrementAndGet(),3).execute(memory.row.id());
            assertThat(memory.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(memory.row.failedStep()).isNull();
            assertThat(memory.row.reservation().reservationId()).isEqualTo(reservation);
            assertThat(memory.row.execution().attempts()).isEqualTo(3);assertThat(pauses).hasValue(2);
        }
    }
    HttpSaleParticipants faultClient(Throwable cause) {
        URI base=URI.create("http://participant");
        return new HttpSaleParticipants(new FaultTransport(cause),JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofSeconds(10));
    }
    static class FaultTransport extends HttpClient {
        final Throwable cause;FaultTransport(Throwable cause){this.cause=cause;}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(2));}
        public Redirect followRedirects(){return Redirect.NEVER;}
        public Optional<ProxySelector> proxy(){return Optional.empty();}
        public javax.net.ssl.SSLContext sslContext(){try{return javax.net.ssl.SSLContext.getDefault();}catch(Exception e){throw new IllegalStateException(e);}}
        public javax.net.ssl.SSLParameters sslParameters(){return new javax.net.ssl.SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_1_1;}
        public Optional<java.util.concurrent.Executor> executor(){return Optional.empty();}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new UnsupportedOperationException();}
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){
            return java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(cause));
        }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){return sendAsync(request,handler);}
    }
    void assertSuspendedWithoutRetry(SaleParticipants participant) {
        var memory=new StoredSaga(reservedSaga());var pauses=new AtomicInteger();
        var usecase=new RegisterSale(memory,memory,participant,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),UUID::randomUUID,n->pauses.incrementAndGet(),3);
        usecase.execute(memory.row.id());
        assertThat(memory.row.status()).isEqualTo(Saga.Status.RUNNING);
        assertThat(memory.row.failedStep()).isNull();assertThat(memory.row.reservation().reservationId()).isEqualTo(reservation);
        assertThat(memory.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");
        assertThat(memory.row.execution().pendingStep()).isEqualTo("register-sale");
        assertThat(memory.row.execution().attempts()).isEqualTo(1);assertThat(pauses).hasValue(0);
    }
    static class StoredSaga implements SagaStore,ExecutionCoordinator {
        Saga row;StoredSaga(Saga row){this.row=row;}
        public Created create(Saga saga){throw new UnsupportedOperationException();}
        public Optional<Saga> find(UUID id){return Optional.of(row);}
        public List<UUID> running(int limit){return List.of(row.id());}
        public boolean healthy(){return true;}
        public Optional<Lease> tryOwn(UUID id){return Optional.of(new Lease(){public Saga load(){return row;}public void save(Saga saga){row=saga;}public void close(){}});}
    }
}
