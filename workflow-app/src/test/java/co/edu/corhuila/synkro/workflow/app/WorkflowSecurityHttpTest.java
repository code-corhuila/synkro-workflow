package co.edu.corhuila.synkro.workflow.app;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Issues 285 and 286 on the real Spring HTTP stack, real JWT validation, real controller and use case:
 * the validated human subject is the initiator handed to the Sales participant whatever the body says,
 * and opaque correlation values are preserved across storage, participants and audit logs.
 * The participants are fakes that record what the use case gives them (the real Sales call is covered by
 * the Sales repository's cross-service security harness).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkflowSecurityHttpTest.Fixtures.class)
@org.springframework.test.context.ActiveProfiles("test")
class WorkflowSecurityHttpTest {
    static final KeyPair keys=keys();
    static final UUID customer=UUID.randomUUID(),product=UUID.randomUUID();
    static final WorkflowHttpTest.Memory memory=new WorkflowHttpTest.Memory();
    static final List<Saga> registered=new CopyOnWriteArrayList<>();
    static final List<String> tracesSeenByParticipants=new CopyOnWriteArrayList<>();
    static final String TOKEN_MARKER="TOKEN-MARKER-7d41c9";
    @LocalServerPort int port;
    final TestRestTemplate rest=new TestRestTemplate();
    static KeyPair keys(){try{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("JWT_PUBLIC_KEY",()->"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        p.add("WORKFLOW_RESPONSE_TIMEOUT_MS",()->"2000");p.add("WORKFLOW_SCAN_INTERVAL_MS",()->"100");
    }
    static String jwt(String subject,String role) {
        try {
            var b=Base64.getUrlEncoder().withoutPadding();
            String h=b.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String claims="{\"sub\":\""+subject+"\",\"roles\":[\""+role+"\"],\"permissions\":[],\"iat\":"+(System.currentTimeMillis()/1000-60)+",\"exp\":"+(System.currentTimeMillis()/1000+600)+",\"jti\":\""+UUID.randomUUID()+"\"}";
            String value=h+"."+b.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(keys.getPrivate());signature.update(value.getBytes(StandardCharsets.UTF_8));
            return value+"."+b.encodeToString(signature.sign());
        }catch(Exception e){throw new AssertionError(e);}
    }
    ResponseEntity<String> post(String token,String key,String body,String correlation) {
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        if(correlation!=null)h.set("X-Correlation-Id",correlation);
        if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);
        return rest.exchange("http://localhost:"+port+"/api/v1/sagas/register-sale",HttpMethod.POST,new HttpEntity<>(body,h),String.class);
    }
    String input(String extra){return "{"+extra+"\"customerId\":\""+customer+"\",\"lines\":[{\"productId\":\""+product+"\",\"quantity\":2}]}";}
    Saga awaitCompleted(String key) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            var saga=memory.rows.values().stream().filter(s->s.idempotencyKey().equals(key)).findFirst();
            if(saga.isPresent()&&saga.get().status()==Saga.Status.COMPLETED)return saga.get();
            Thread.sleep(10);
        }
        return fail("Saga did not complete");
    }

    @Test void validatedHumanSubjectIsTheInitiatorHandedToSalesAndBodyAuthorshipIsIgnored() throws Exception {
        UUID human=UUID.randomUUID(),forged=UUID.randomUUID();
        String key="author-"+UUID.randomUUID();
        var response=post(jwt(human.toString(),"SALESPERSON"),key,input("\"createdBy\":\""+forged+"\",\"initiatedBy\":\""+forged+"\","),null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Saga done=awaitCompleted(key);
        assertThat(done.initiatedBy()).isEqualTo(human).isNotEqualTo(forged);
        var sentToSales=registered.stream().filter(s->s.idempotencyKey().equals(key)).findFirst().orElseThrow();
        assertThat(sentToSales.initiatedBy()).isEqualTo(human);
        assertThat(registered.stream().filter(s->s.idempotencyKey().equals(key)).count()).isEqualTo(1);
    }

    @Test void requestsWithoutAHumanCredentialCreateNoSagaAndCallNoParticipant() {
        int rows=memory.rows.size(),calls=registered.size();
        String key="denied-"+UUID.randomUUID();
        assertThat(post(null,key,input(""),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("not-a-jwt",key,input(""),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(jwt("synkro-workflow","SERVICE"),key,input(""),null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(jwt(UUID.randomUUID().toString(),"INVENTORY"),key,input(""),null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(memory.rows).hasSize(rows);assertThat(registered).hasSize(calls);
    }

    @Test void opaqueCorrelationIsPreservedAndCredentialsStayOutOfErrors() throws Exception {
        String trace="process-correlation", key="opaque-"+UUID.randomUUID();
        String credential=jwt(UUID.randomUUID().toString(),"ADMIN");
        var response=post(credential,key,input(""),trace);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(trace);
        assertThat(awaitCompleted(key).execution().correlationId()).isEqualTo(trace);
        assertThat(tracesSeenByParticipants).contains(trace);
        var invalid=post(TOKEN_MARKER,"short",input(""),trace);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getBody()).contains("UNAUTHORIZED",trace).doesNotContain(TOKEN_MARKER,credential);
    }

    @Test void aCanonicalUuidCorrelationIsPreservedEndToEnd() throws Exception {
        String trace=UUID.randomUUID().toString(),key="trace-"+UUID.randomUUID();
        var response=post(jwt(UUID.randomUUID().toString(),"ADMIN"),key,input(""),trace);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(trace);
        assertThat(awaitCompleted(key).execution().correlationId()).isEqualTo(trace);
        assertThat(tracesSeenByParticipants).contains(trace);
    }

    @TestConfiguration static class Fixtures {
        @Bean WorkflowHttpTest.Memory store(){return memory;}
        @Bean @Primary SaleParticipants testParticipants(){return new SaleParticipants(){
            public void validateCustomer(UUID id,String key,String trace){tracesSeenByParticipants.add(trace);}
            public Reservation reserve(SaleInput input,String key,String trace){tracesSeenByParticipants.add(trace);return new Reservation(UUID.randomUUID(),List.of(new Reservation.Line(product,2,125)));}
            public UUID register(Saga saga,String key,String trace){tracesSeenByParticipants.add(trace);registered.add(saga);return UUID.randomUUID();}
            public void release(Reservation r,String key,String trace){}
        };}
    }
}
