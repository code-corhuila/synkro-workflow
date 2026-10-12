package co.edu.corhuila.synkro.workflow.adapter.in.http;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import co.edu.corhuila.synkro.workflow.application.port.out.ExecutionScheduler;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
@RestController
@RequestMapping("/api/v1/sagas")
public class SagaController {
    private final RegisterSale usecase;private final SagaStore store;private final ExecutionScheduler background;private final long deadline;
    public SagaController(RegisterSale usecase,SagaStore store,ExecutionScheduler background,@Value("${WORKFLOW_RESPONSE_TIMEOUT_MS:1000}") long deadline){
        if(deadline<1||deadline>10000)throw new IllegalArgumentException("Invalid response deadline");
        this.usecase=usecase;this.store=store;this.background=background;this.deadline=deadline;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(UUID sagaId,String sagaType,Saga.Status status,List<String> completedSteps,String failedStep,UUID saleId,Instant createdAt,Instant updatedAt) {
        static Response from(Saga s){return new Response(s.id(),"register-sale",s.status(),s.completedSteps(),s.status()==Saga.Status.COMPENSATED||s.status()==Saga.Status.FAILED?s.failedStep():null,s.status()==Saga.Status.COMPLETED?s.saleId():null,s.createdAt(),s.updatedAt());}
    }
    @PostMapping("/register-sale")
    ResponseEntity<Response> start(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal Jwt token,HttpServletRequest request) {
        SaleInput input=parse(body);
        if(key==null||key.length()<8||key.length()>128)throw new InvalidInput(List.of(Map.of("field","Idempotency-Key","message","must contain 8 to 128 characters")));
        var created=usecase.start(input,UUID.fromString(token.getSubject()),key,HttpErrors.trace(request));
        if(created.created()) {
            try{background.schedule(created.saga().id()).get(deadline,TimeUnit.MILLISECONDS);}
            catch(TimeoutException|ExecutionException ignored){}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        Saga saga=store.find(created.saga().id()).orElseThrow(()->new WorkflowError("INTERNAL_ERROR"));
        var response=ResponseEntity.status(created.created()?201:200);
        if(created.created())response.location(URI.create("/api/v1/sagas/"+saga.id()));
        return response.body(Response.from(saga));
    }
    @GetMapping("/{id}") Response get(@PathVariable("id") String id) {
        UUID uuid;try{uuid=strictUuid(id);}catch(Exception e){throw new InvalidInput(List.of(Map.of("field","id","message","must be a UUID")));}
        return Response.from(store.find(uuid).orElseThrow(()->new WorkflowError("NOT_FOUND")));
    }
    static SaleInput parse(JsonNode body) {
        var errors=new ArrayList<Map<String,String>>();UUID customer=null;var lines=new ArrayList<SaleInput.Line>();
        try{customer=strictUuid(body.path("customerId").textValue());}catch(Exception e){errors.add(Map.of("field","customerId","message","must be a UUID"));}
        JsonNode items=body.path("lines");
        if(!items.isArray()||items.isEmpty()||items.size()>100)errors.add(Map.of("field","lines","message","must contain 1 to 100 lines"));
        else for(int i=0;i<items.size();i++) {
            JsonNode line=items.get(i);UUID product=null;int quantity=0;
            try{product=strictUuid(line.path("productId").textValue());}catch(Exception e){errors.add(Map.of("field","lines["+i+"].productId","message","must be a UUID"));}
            JsonNode q=line.path("quantity");
            if(!q.isIntegralNumber()||!q.canConvertToInt()||q.intValue()<1)errors.add(Map.of("field","lines["+i+"].quantity","message","must be a positive integer"));
            else quantity=q.intValue();
            if(product!=null&&quantity>0)lines.add(new SaleInput.Line(product,quantity));
        }
        if(!errors.isEmpty())throw new InvalidInput(errors);
        if(lines.stream().map(SaleInput.Line::productId).distinct().count()!=lines.size())throw new InvalidInput(List.of(Map.of("field","lines","message","each product must appear once")));
        return new SaleInput(customer,lines);
    }
    static UUID strictUuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();return id;}
    static final class InvalidInput extends RuntimeException {final List<Map<String,String>> details;InvalidInput(List<Map<String,String>> details){this.details=List.copyOf(details);}}
}
