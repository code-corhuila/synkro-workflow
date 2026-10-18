package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import java.io.IOException;
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
        HttpRequest request;
        try {
            String credential=token.get();if(credential==null || credential.isBlank())throw new ParticipantFailure(ParticipantFailure.Kind.AUTHORIZATION);
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization","Bearer "+credential).header("Idempotency-Key",key).header("X-Correlation-Id",trace).header("Accept","application/json");
            if(method.equals("GET"))builder.GET();
            else builder.header("Content-Type","application/json").POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            request=builder.build();
        }catch(ParticipantFailure failure){throw failure;}
        catch(JsonProcessingException|RuntimeException failure){throw new ParticipantFailure(ParticipantFailure.Kind.LOCAL_FAILURE);}
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request,info->new LimitedBody());
            HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            int code=response.statusCode();
            if(code==401||code==403)throw new ParticipantFailure(ParticipantFailure.Kind.AUTHORIZATION);
            if(Set.of(408,429,500,502,503,504).contains(code))throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);
            if(confirmedRejections.contains(code)) {
                validateRejection(response,code,trace);
                throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED);
            }
            if(code!=200&&code!=201)throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);
            return responseJson(response.body());
        }catch(ParticipantFailure e){throw e;}
        catch(TimeoutException e){throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ParticipantFailure(ParticipantFailure.Kind.LOCAL_FAILURE);}
        catch(ExecutionException e){throw asynchronousFailure(e.getCause());}
        catch(RuntimeException e){throw new ParticipantFailure(ParticipantFailure.Kind.LOCAL_FAILURE);}
        finally {if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private JsonNode responseJson(byte[] bytes) {
        try(var parser=json.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value=json.readTree(parser);
            if(value==null || !value.isObject() || parser.nextToken()!=null)throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);
            return value;
        }catch(IOException e){throw new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);}
    }
    private void validateRejection(HttpResponse<byte[]> response,int status,String trace) {
        JsonNode envelope=responseJson(response.body());
        validate(()->{
            equal(response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().toLowerCase(Locale.ROOT),"application/json");
            String code=text(envelope,"error");text(envelope,"message");equal(text(envelope,"traceId"),trace);
            Set<String> allowed=switch(status) {
                case 400 -> Set.of("VALIDATION_ERROR");
                case 404 -> Set.of("NOT_FOUND");
                case 422 -> Set.of("BUSINESS_RULE_VIOLATION","INVALID_STATUS_TRANSITION");
                default -> Set.of();
            };
            if(!allowed.contains(code))throw new IllegalArgumentException();
            JsonNode details=envelope.get("details");
            if(details!=null) {
                if(!details.isArray())throw new IllegalArgumentException();
                for(JsonNode detail:details){text(detail,"field");text(detail,"message");}
            }
        });
    }
    private static ParticipantFailure asynchronousFailure(Throwable failure) {
        while((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause()!=null)failure=failure.getCause();
        // Contract/configuration failures can be nested inside a transport wrapper.
        var visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null && visited.add(cause);cause=cause.getCause()) {
            if(cause instanceof ResponseLimitExceeded || cause instanceof java.net.ProtocolException)
                return new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE);
            if(cause instanceof javax.net.ssl.SSLException || cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException)
                return new ParticipantFailure(ParticipantFailure.Kind.LOCAL_FAILURE);
        }
        return new ParticipantFailure(failure instanceof IOException?ParticipantFailure.Kind.TRANSIENT:ParticipantFailure.Kind.LOCAL_FAILURE);
    }
    private static final class ResponseLimitExceeded extends RuntimeException {}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        Flow.Subscription subscription;int size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers)size+=b.remaining();
            if(size>1048576){subscription.cancel();delegate.onError(new ResponseLimitExceeded());return;}
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
