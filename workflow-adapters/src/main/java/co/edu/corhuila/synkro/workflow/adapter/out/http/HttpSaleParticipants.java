package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

public final class HttpSaleParticipants implements SaleParticipants {
    private final HttpClient client;
    private final ObjectMapper json;
    private final URI customers,products,sales;
    private final Supplier<String> token;
    private final Duration timeout;
    public HttpSaleParticipants(HttpClient client,ObjectMapper json,URI customers,URI products,URI sales,Supplier<String> token,Duration timeout) {
        this.client=client;this.json=json;this.customers=customers;this.products=products;this.sales=sales;this.token=token;this.timeout=timeout;
    }
    public void validateCustomer(UUID id,String key,String trace) {
        JsonNode r=request(customers,"/api/v1/customers/"+id,"GET",null,key,trace,Set.of(404));
        validate(()->{equal(uuid(r,"customerId"),id);text(r,"name");text(r,"identityDocument");date(r,"registrationDate");bool(r,"active");});
        if(!r.get("active").booleanValue())throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED);
    }
    public Reservation reserve(SaleInput input,String key,String trace) {
        JsonNode response=request(products,"/api/v1/stock-reservations","POST",Map.of("lines",input.lines()),key,trace,Set.of(400,422));
        Reservation reservation=parseReservation(response,"RESERVED");
        validate(()->equal(reservation.lines().stream().map(l->new SaleInput.Line(l.productId(),l.quantity())).sorted(Comparator.comparing(l->l.productId().toString())).toList(),input.lines()));
        return reservation;
    }
    public UUID register(Saga saga,String key,String trace) {
        JsonNode r=request(sales,"/api/v1/sales","POST",Map.of("customerId",saga.input().customerId(),"createdBy",saga.initiatedBy(),"details",saga.reservation().lines()),key,trace,Set.of(400,422));
        validate(()->{
            uuid(r,"saleId");equal(uuid(r,"customerId"),saga.input().customerId());equal(uuid(r,"createdBy"),saga.initiatedBy());date(r,"date");equal(bool(r,"active"),true);
            JsonNode details=array(r,"details");equal(details.size(),saga.reservation().lines().size());
            Map<UUID,Reservation.Line> expected=new HashMap<>();saga.reservation().lines().forEach(l->expected.put(l.productId(),l));
            long total=0;
            for(JsonNode line:details) {
                uuid(line,"detailId");UUID product=uuid(line,"productId");Reservation.Line original=expected.remove(product);
                if(original==null)throw new IllegalArgumentException();
                equal(number(line,"quantity"),(long)original.quantity());equal(number(line,"unitPriceCents"),original.unitPriceCents());equal(bool(line,"active"),true);
                long subtotal=Math.multiplyExact(original.quantity(),original.unitPriceCents());equal(number(line,"subtotalCents"),subtotal);total=Math.addExact(total,subtotal);
            }
            equal(number(r,"totalCents"),total);
        });
        return uuid(r,"saleId");
    }
    public void release(Reservation original,String key,String trace) {
        JsonNode read=request(products,"/api/v1/stock-reservations/"+original.reservationId(),"GET",null,key,trace,Set.of(404));
        String status=read.path("status").asText();
        Reservation current=parseReservation(read,status);
        validate(()->equal(current,original));
        if(status.equals("RELEASED"))return;
        Reservation released=parseReservation(request(products,"/api/v1/stock-reservations/"+original.reservationId()+"/release","POST",null,key,trace,Set.of(404)),"RELEASED");
        validate(()->equal(released,original));
    }
    private Reservation parseReservation(JsonNode r,String expectedStatus) {
        try {
            if(!Set.of("RESERVED","RELEASED").contains(expectedStatus))throw new IllegalArgumentException();
            equal(text(r,"status"),expectedStatus);date(r,"createdAt");
            if(expectedStatus.equals("RELEASED"))date(r,"releasedAt");
            var lines=new ArrayList<Reservation.Line>();var ids=new HashSet<UUID>();
            for(JsonNode line:array(r,"lines")) {
                uuid(line,"lineId");UUID id=uuid(line,"productId");if(!ids.add(id))throw new IllegalArgumentException();
                lines.add(new Reservation.Line(id,Math.toIntExact(number(line,"quantity")),number(line,"unitPriceCents")));
            }
            lines.sort(Comparator.comparing(l->l.productId().toString()));
            return new Reservation(uuid(r,"reservationId"),lines);
        }catch(Exception e){throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);}
    }
    private JsonNode request(URI base,String path,String method,Object body,String key,String trace,Set<Integer> confirmedRejections) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            String credential=token.get();if(credential==null || credential.isBlank())throw new ParticipantFailure(ParticipantFailure.Kind.AUTHORIZATION);
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization","Bearer "+credential).header("Idempotency-Key",key).header("X-Correlation-Id",trace).header("Accept","application/json");
            if(method.equals("GET"))builder.GET();
            else builder.header("Content-Type","application/json").POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            pending=client.sendAsync(builder.build(),info->new LimitedBody());
            HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            int code=response.statusCode();
            if(code==401||code==403)throw new ParticipantFailure(ParticipantFailure.Kind.AUTHORIZATION);
            if(Set.of(408,429,500,502,503,504).contains(code))throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);
            if(confirmedRejections.contains(code))throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED);
            if(code!=200&&code!=201)throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);
            try{return json.readTree(response.body());}catch(Exception e){throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);}
        }catch(ParticipantFailure e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);}
        catch(Exception e){
            System.getLogger(HttpSaleParticipants.class.getName()).log(System.Logger.Level.WARNING,"Participant transport failure: "+e.getClass().getSimpleName());
            throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);
        }
        finally {if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        Flow.Subscription subscription;int size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers)size+=b.remaining();
            if(size>1048576){subscription.cancel();delegate.onError(new IllegalArgumentException("Response limit"));return;}
            delegate.onNext(buffers);
        }
        public void onError(Throwable t){delegate.onError(t);}
        public void onComplete(){delegate.onComplete();}
    }
    private static void validate(Runnable action){try{action.run();}catch(Exception e){throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);}}
    private static void equal(Object actual,Object expected){if(!Objects.equals(actual,expected))throw new IllegalArgumentException();}
    private static String text(JsonNode r,String field){JsonNode n=r.get(field);if(n==null||!n.isTextual()||n.textValue().isBlank())throw new IllegalArgumentException();return n.textValue();}
    private static UUID uuid(JsonNode r,String field){String text=text(r,field);UUID id=UUID.fromString(text);equal(id.toString(),text.toLowerCase(Locale.ROOT));return id;}
    private static long number(JsonNode r,String field){JsonNode n=r.get(field);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong())throw new IllegalArgumentException();return n.longValue();}
    private static boolean bool(JsonNode r,String field){JsonNode n=r.get(field);if(n==null||!n.isBoolean())throw new IllegalArgumentException();return n.booleanValue();}
    private static void date(JsonNode r,String field){Instant.parse(text(r,field));}
    private static JsonNode array(JsonNode r,String field){JsonNode n=r.get(field);if(n==null||!n.isArray()||n.isEmpty())throw new IllegalArgumentException();return n;}
}
