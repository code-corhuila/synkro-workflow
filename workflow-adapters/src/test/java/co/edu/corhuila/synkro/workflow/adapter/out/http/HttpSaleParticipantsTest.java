package co.edu.corhuila.synkro.workflow.adapter.out.http;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
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
    HttpServer server; HttpSaleParticipants client;
    final UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),reservation=UUID.randomUUID(),sale=UUID.randomUUID(),caller=UUID.randomUUID();
    final SaleInput input=new SaleInput(customer,List.of(new SaleInput.Line(product,2)));
    final List<String> seen=new ArrayList<>();
    int status=200; String body; String sentBody;
    String reservationBody(String status) {return "{\"reservationId\":\""+reservation+"\",\"status\":\""+status+"\",\"createdAt\":\"2026-10-12T12:00:00Z\",\"releasedAt\":\"2026-10-12T12:01:00Z\",\"lines\":[{\"lineId\":\""+UUID.randomUUID()+"\",\"productId\":\""+product+"\",\"quantity\":2,\"unitPriceCents\":125}]}";}
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("Authorization")+" "+exchange.getRequestHeaders().getFirst("Idempotency-Key")+" "+exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        client=new HttpSaleParticipants(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).followRedirects(HttpClient.Redirect.NEVER).build(),JsonMapper.builder().build(),base,base,base,()->"service-sentinel",Duration.ofMillis(500));
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void customerCredentialCorrelationAndActiveValidation() {
        body="{\"customerId\":\""+customer+"\",\"name\":\"Buyer\",\"identityDocument\":\"123\",\"registrationDate\":\"2026-10-12T12:00:00Z\",\"active\":true}";
        client.validateCustomer(customer,"saga:validate-customer","trace");
        assertThat(seen).containsExactly("GET /api/v1/customers/"+customer+" Bearer service-sentinel saga:validate-customer trace");
        body=body.replace("true","false");
        assertThatThrownBy(()->client.validateCustomer(customer,"key","trace")).isInstanceOf(ParticipantFailure.class)
            .extracting(e->((ParticipantFailure)e).kind()).isEqualTo(ParticipantFailure.Kind.REJECTED);
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
    }
    @Test void readReleasedReservationAvoidsRepeatRelease() {
        body=reservationBody("RELEASED");client.release(new Reservation(reservation,List.of(new Reservation.Line(product,2,125))),"saga:release-stock","trace");
        assertThat(seen).hasSize(1);assertThat(seen.getFirst()).startsWith("GET /api/v1/stock-reservations/"+reservation);
    }
    @Test void statusClassificationAndSafeErrors() {
        body="sentinel-driver-password-url";
        for(int code:List.of(400,404,422,401,403,429,500,503)) {
            status=code;
            ParticipantFailure.Kind expected=code==401||code==403?ParticipantFailure.Kind.AUTHORIZATION:code==429||code>=500?ParticipantFailure.Kind.TRANSIENT:ParticipantFailure.Kind.REJECTED;
            assertThatThrownBy(()->client.reserve(input,"key","trace")).isInstanceOf(ParticipantFailure.class)
                .hasMessage(expected.name()).extracting(e->((ParticipantFailure)e).kind()).isEqualTo(expected);
        }
        status=200;body="{broken";
        assertThatThrownBy(()->client.reserve(input,"key","trace")).hasMessage("INVALID_RESPONSE");
    }
}
