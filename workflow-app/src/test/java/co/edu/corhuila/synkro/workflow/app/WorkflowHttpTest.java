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
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkflowHttpTest.Fixtures.class)
@org.springframework.test.context.ActiveProfiles("test")
class WorkflowHttpTest {
    static final KeyPair keys=keys();
    static final UUID caller=UUID.randomUUID(),customer=UUID.randomUUID(),product=UUID.randomUUID();
    static final Memory memory=new Memory();
    static final AtomicInteger effects=new AtomicInteger();
    @LocalServerPort int port;
    final TestRestTemplate rest=new TestRestTemplate();
    static KeyPair keys(){try{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("JWT_PUBLIC_KEY",()->"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        p.add("WORKFLOW_RESPONSE_TIMEOUT_MS",()->"30");p.add("WORKFLOW_SCAN_INTERVAL_MS",()->"100");
    }
    static String token(String role,long expiration,KeyPair pair,String algorithm) {
        try {
            var b=Base64.getUrlEncoder().withoutPadding();
            String h=b.encodeToString(("{\"alg\":\""+algorithm+"\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            String claims="{\"sub\":\""+caller+"\",\"roles\":[\""+role+"\"],\"permissions\":[],\"iat\":"+(System.currentTimeMillis()/1000-60)+",\"exp\":"+expiration+",\"jti\":\""+UUID.randomUUID()+"\"}";
            String value=h+"."+b.encodeToString(claims.getBytes(StandardCharsets.UTF_8));Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(value.getBytes(StandardCharsets.UTF_8));return value+"."+b.encodeToString(signature.sign());
        }catch(Exception e){throw new AssertionError(e);}
    }
    String jwt(String role){return token(role,System.currentTimeMillis()/1000+600,keys,"RS256");}
    String withoutClaim(String field) throws Exception {
        String[] pieces=jwt("ADMIN").split("\\.");var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Base64.getUrlDecoder().decode(pieces[1]));payload.remove(field);
        String value=pieces[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(payload));
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(keys.getPrivate());signature.update(value.getBytes(StandardCharsets.UTF_8));
        return value+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }
    ResponseEntity<String> request(String path,HttpMethod method,String token,String key,String body){
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("X-Correlation-Id","http-trace");
        if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);
        return rest.exchange("http://localhost:"+port+path,method,new HttpEntity<>(body,h),String.class);
    }
    String input(){return "{\"customerId\":\""+customer+"\",\"lines\":[{\"productId\":\""+product+"\",\"quantity\":2}]}";}
    @BeforeEach void healthy(){memory.healthy=true;}
    @Test void healthChecksActualStoreAndIsPublic(){
        assertThat(request("/health",HttpMethod.GET,null,null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        memory.healthy=false;
        var response=request("/health",HttpMethod.GET,null,null,null);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("\"database\":\"down\"").doesNotContain("password","jdbc:");
    }
    @Test void authenticationActuallyVerifiesTokensAndKeepsUtf8(){
        for(String token:Arrays.asList(null,"not-a-real-token",token("ADMIN",System.currentTimeMillis()/1000-120,keys,"RS256"),token("ADMIN",System.currentTimeMillis()/1000+600,keys(),"RS256"),token("ADMIN",System.currentTimeMillis()/1000+600,keys,"HS256"))) {
            var response=request("/api/v1/sagas/"+UUID.randomUUID(),HttpMethod.GET,token,null,null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst("Content-Type")).containsIgnoringCase("UTF-8");
            assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"","\"traceId\":\"http-trace\"");
        }
    }
    @Test void unauthorizedRolesCannotStartOrRead(){
        for(String role:List.of("INVENTORY","SERVICE")) {
            assertThat(request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt(role),"role-key-123",input()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request("/api/v1/sagas/"+UUID.randomUUID(),HttpMethod.GET,jwt(role),null,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
    @Test void allRequiredClaimsAreEnforced() throws Exception {
        for(String field:List.of("sub","roles","permissions","iat","exp","jti")) {
            assertThat(request("/api/v1/sagas/"+UUID.randomUUID(),HttpMethod.GET,withoutClaim(field),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
    @Test void creationReplayAndPublicProjection() throws Exception {
        String key="http-"+UUID.randomUUID();var first=request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("SALESPERSON"),key,input());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String location=first.getHeaders().getFirst("Location");assertThat(location).startsWith("/api/v1/sagas/");
        assertThat(first.getBody()).contains("\"sagaType\":\"register-sale\"").doesNotContain("input","step_results","error_detail","execution","initiatedBy","service-sentinel");
        var replay=request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("ADMIN"),key,input());assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(location,HttpMethod.GET,jwt("SALESPERSON"),null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var conflict=request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("ADMIN"),key,input().replace("\"quantity\":2","\"quantity\":3"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(conflict.getBody()).doesNotContain(location.substring(location.lastIndexOf('/')+1),customer.toString());
    }
    @Test void malformedRequestsCannotReachParticipants(){
        for(String body:List.of("{bad","{}",input().replace("\"quantity\":2","\"quantity\":0"),input().replace("\"quantity\":2","\"quantity\":1.5"),input().replace(customer.toString(),"1-1-1-1-1"))) {
            var response=request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("ADMIN"),"invalid-key",body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody()).contains("\"error\":\"VALIDATION_ERROR\"","details","http-trace");
        }
        assertThat(request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("ADMIN"),null,input()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void authenticatedUnknownRouteRemains404AndDirectErrorIsProtected(){
        var response=request("/api/v1/does-not-exist",HttpMethod.GET,jwt("ADMIN"),null,null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(response.getBody()).contains("\"error\":\"NOT_FOUND\"");
        assertThat(request("/error",HttpMethod.GET,null,null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void httpDeadlineDoesNotCancelDurableContinuation() throws Exception {
        String key="slow-"+UUID.randomUUID();var response=request("/api/v1/sagas/register-sale",HttpMethod.POST,jwt("ADMIN"),key,input());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String location=response.getHeaders().getFirst("Location");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            var result=request(location,HttpMethod.GET,jwt("ADMIN"),null,null);
            if(result.getBody().contains("\"COMPLETED\""))return;
            Thread.sleep(10);
        }
        fail("Background execution did not finish after response deadline");
    }
    @TestConfiguration static class Fixtures {
        @Bean Memory store(){return memory;}
        @Bean @Primary SaleParticipants testParticipants(){return new SaleParticipants(){
            public void validateCustomer(UUID id,String key,String trace){try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            public Reservation reserve(SaleInput input,String key,String trace){effects.incrementAndGet();return new Reservation(UUID.randomUUID(),List.of(new Reservation.Line(product,2,125)));}
            public UUID register(Saga saga,String key,String trace){return UUID.randomUUID();}
            public void release(Reservation r,String key,String trace){}
        };}
    }
    static final class Memory implements SagaStore,ExecutionCoordinator {
        final ConcurrentMap<UUID,Saga> rows=new ConcurrentHashMap<>();final Set<UUID> owners=ConcurrentHashMap.newKeySet();volatile boolean healthy=true;
        public synchronized Created create(Saga saga){Saga prior=rows.values().stream().filter(s->s.idempotencyKey().equals(saga.idempotencyKey())).findFirst().orElse(null);if(prior!=null)return new Created(prior,false);rows.put(saga.id(),saga);return new Created(saga,true);}
        public Optional<Saga> find(UUID id){return Optional.ofNullable(rows.get(id));}
        public List<UUID> running(int limit){return rows.values().stream().filter(s->s.status()==Saga.Status.RUNNING).limit(limit).map(Saga::id).toList();}
        public boolean healthy(){return healthy;}
        public Optional<Lease> tryOwn(UUID id){if(!owners.add(id))return Optional.empty();return Optional.of(new Lease(){
            public Saga load(){return rows.get(id);}public void save(Saga s){rows.put(id,s);}public void close(){owners.remove(id);}
        });}
    }
}
