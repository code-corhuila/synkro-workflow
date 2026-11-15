package co.edu.corhuila.synkro.workflow.adapter.out.logging;

import co.edu.corhuila.synkro.workflow.adapter.out.http.HttpSaleParticipants;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

/**
 * Issue 286, joint capture: the real RegisterSale use case, the real HTTP participant adapter, the
 * real SLF4J audit adapter and a real local HTTP server standing in for Customers, Products and
 * Sales. The service credential, a synthetic customer name, document and password travel through
 * the real code paths; no log line of any logger may contain them, on completion or on failure.
 */
class SafeAuditTrailIntegrationTest {
    static final String TOKEN_MARKER = "SERVICE-TOKEN-MARKER-9f3ad1", NAME = "Synthetic Buyer Ximena", DOCUMENT = "SYN-DOC-778899", PASSWORD = "synthetic-password-xyz";
    static final String TRACE = "6e0f5a1d-3b94-4c27-8d16-2a7c9e4b0f58";
    static final Set<String> ALLOWED = Set.of("timestamp", "service", "correlationId", "sagaId", "step", "actorId", "action", "result");
    final UUID customer = UUID.randomUUID(), product = UUID.randomUUID(), reservation = UUID.randomUUID(), caller = UUID.randomUUID();
    final SaleInput input = new SaleInput(customer, List.of(new SaleInput.Line(product, 2)));
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    volatile String salesMode = "ok";
    HttpServer server;
    ExecutorService executor;
    HttpClient transport;
    HttpSaleParticipants participants;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        participants = new HttpSaleParticipants(transport, JsonMapper.builder().build(), base, base, base, () -> TOKEN_MARKER, Duration.ofSeconds(10));
    }

    @AfterEach void stop() {
        transport.close();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test void completedSagaLeavesACoherentAllowlistedTraceAndNoSecrets() throws Exception {
        var run = execute("ok");
        assertThat(run.store.row.status()).isEqualTo(Saga.Status.COMPLETED);
        assertThat(authorizations).isNotEmpty().allMatch(("Bearer " + TOKEN_MARKER)::equals);
        assertAudit(run, List.of("execute-step/validate-customer/succeeded", "execute-step/reserve-stock/succeeded", "execute-step/register-sale/succeeded", "finish-saga/-/completed"));
        assertNoSecrets(run.logs);
    }

    @Test void failedSagaLeavesNoSecretsEvenWhenTheParticipantEchoesThem() throws Exception {
        var run = execute("explode");
        assertThat(run.store.row.status()).isEqualTo(Saga.Status.RUNNING);
        assertThat(run.store.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");
        assertAudit(run, List.of("execute-step/validate-customer/succeeded", "execute-step/reserve-stock/succeeded",
            "execute-step/register-sale/transient-failure", "execute-step/register-sale/transient-failure", "execute-step/register-sale/transient-failure", "execute-step/register-sale/unresolved"));
        assertNoSecrets(run.logs);
    }

    @Test void confirmedRejectionCompensatesWithATraceAndNoSecrets() throws Exception {
        var run = execute("reject");
        assertThat(run.store.row.status()).isEqualTo(Saga.Status.COMPENSATED);
        assertAudit(run, List.of("execute-step/validate-customer/succeeded", "execute-step/reserve-stock/succeeded",
            "execute-step/register-sale/rejected", "compensate/release-stock/succeeded", "finish-saga/-/compensated"));
        assertNoSecrets(run.logs);
    }

    Run execute(String mode) {
        salesMode = mode;
        var store = new Memory(Saga.create(UUID.randomUUID(), "key-" + UUID.randomUUID(), caller, input, TRACE, Instant.now()));
        try (var logs = new LogCapture()) {
            new RegisterSale(store, store, participants, Clock.systemUTC(), UUID::randomUUID, n -> { }, 3, new Slf4jAuditTrail()).execute(store.row.id());
            return new Run(store, logs.auditLines(), logs.everything());
        }
    }

    void assertAudit(Run run, List<String> expected) throws Exception {
        var mapper = JsonMapper.builder().build();
        List<String> shape = new ArrayList<>();
        for (String line : run.audit) {
            JsonNode record = mapper.readTree(line);
            assertThat(record.fieldNames()).toIterable().isSubsetOf(ALLOWED);
            assertThat(record.get("service").asText()).isEqualTo("synkro-workflow");
            assertThat(record.get("sagaId").asText()).isEqualTo(run.store.row.id().toString());
            assertThat(record.get("correlationId").asText()).isEqualTo(TRACE);
            assertThat(record.get("actorId").asText()).isEqualTo(caller.toString());
            assertThat(Instant.parse(record.get("timestamp").asText())).isNotNull();
            shape.add(record.get("action").asText() + "/" + (record.has("step") ? record.get("step").asText() : "-") + "/" + record.get("result").asText());
        }
        assertThat(shape).containsExactlyElementsOf(expected);
    }

    void assertNoSecrets(String everything) {
        assertThat(everything).isNotBlank();
        for (String secret : List.of(TOKEN_MARKER, NAME, DOCUMENT, PASSWORD, "Bearer", "Authorization", "java.lang.", "Exception", "\tat ")) {
            assertThat(everything).as("log output must not contain " + secret).doesNotContain(secret);
        }
    }

    void answer(HttpExchange exchange) throws IOException {
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
        int status = 200;
        String body;
        if (method.equals("GET") && path.startsWith("/api/v1/customers/")) {
            body = "{\"customerId\":\"" + customer + "\",\"name\":\"" + NAME + "\",\"identityDocument\":\"" + DOCUMENT + "\",\"password\":\"" + PASSWORD + "\",\"registrationDate\":\"2026-10-12T12:00:00Z\",\"active\":true}";
        } else if (method.equals("POST") && path.equals("/api/v1/stock-reservations")) {
            status = 201;
            body = reservation("RESERVED");
        } else if (method.equals("GET") && path.startsWith("/api/v1/stock-reservations/")) {
            body = reservation("RESERVED");
        } else if (method.equals("POST") && path.endsWith("/release")) {
            body = reservation("RELEASED");
        } else if (method.equals("POST") && path.equals("/api/v1/sales")) {
            switch (salesMode) {
                case "explode" -> {
                    status = 500;
                    body = "{\"error\":\"INTERNAL_ERROR\",\"detail\":\"" + TOKEN_MARKER + " " + PASSWORD + " java.lang.IllegalStateException at com.driver.Mongo\"}";
                }
                case "reject" -> {
                    status = 422;
                    body = "{\"error\":\"BUSINESS_RULE_VIOLATION\",\"message\":\"" + NAME + "\",\"traceId\":\"" + exchange.getRequestHeaders().getFirst("X-Correlation-Id") + "\",\"details\":[{\"field\":\"identityDocument\",\"message\":\"" + DOCUMENT + "\"}]}";
                }
                default -> {
                    status = 201;
                    body = "{\"saleId\":\"" + UUID.randomUUID() + "\",\"customerId\":\"" + customer + "\",\"createdBy\":\"" + caller + "\",\"date\":\"2026-10-12T12:00:00Z\",\"totalCents\":250,\"active\":true,\"details\":[{\"detailId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"quantity\":2,\"unitPriceCents\":125,\"subtotalCents\":250,\"active\":true}]}";
                }
            }
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String reservation(String status) {
        return "{\"reservationId\":\"" + reservation + "\",\"status\":\"" + status + "\",\"createdAt\":\"2026-10-12T12:00:00Z\",\"releasedAt\":\"2026-10-12T12:01:00Z\",\"lines\":[{\"lineId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"quantity\":2,\"unitPriceCents\":125}]}";
    }

    record Run(Memory store, List<String> audit, String logs) { }

    static final class Memory implements SagaStore, ExecutionCoordinator {
        Saga row;
        Memory(Saga row) { this.row = row; }
        public Created create(Saga saga) { throw new UnsupportedOperationException(); }
        public Optional<Saga> find(UUID id) { return Optional.of(row); }
        public List<UUID> running(int limit) { return List.of(row.id()); }
        public boolean healthy() { return true; }
        public Optional<Lease> tryOwn(UUID id) {
            return Optional.of(new Lease() {
                public Saga load() { return row; }
                public void save(Saga saga) { row = saga; }
                public void close() { }
            });
        }
    }
}
