package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.synkro.workflow.application.port.out.SaleParticipants;
import co.edu.corhuila.synkro.workflow.domain.model.Reservation;
import co.edu.corhuila.synkro.workflow.domain.model.Saga;
import co.edu.corhuila.synkro.workflow.domain.model.SaleInput;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Decorator of the participant port: one breaker per participant, not per endpoint, so reserve-stock and
 * release-stock share the Products circuit. Only a fully validated success closes or resets a circuit; only a
 * classified transient failure counts toward opening it. Business rejections, authorization, local and contract
 * failures say nothing about availability, so they are neutral and never leave a HALF_OPEN probe stuck.
 * An open circuit is reported as CIRCUIT_OPEN: it is not a rejection and not evidence that an earlier write failed.
 */
public final class CircuitBreakingParticipants implements SaleParticipants {
    public static final int FAILURE_THRESHOLD=3;
    public static final Duration COOLDOWN=Duration.ofSeconds(15);
    private final SaleParticipants delegate;
    private final CircuitBreaker customers,products,sales;
    public CircuitBreakingParticipants(SaleParticipants delegate,Clock clock) {
        this(delegate,new CircuitBreaker(FAILURE_THRESHOLD,COOLDOWN,clock),new CircuitBreaker(FAILURE_THRESHOLD,COOLDOWN,clock),new CircuitBreaker(FAILURE_THRESHOLD,COOLDOWN,clock));
    }
    CircuitBreakingParticipants(SaleParticipants delegate,CircuitBreaker customers,CircuitBreaker products,CircuitBreaker sales) {
        this.delegate=delegate;this.customers=customers;this.products=products;this.sales=sales;
    }
    public void validateCustomer(UUID id,String key,String trace){guarded(customers,()->{delegate.validateCustomer(id,key,trace);return null;});}
    public Reservation reserve(SaleInput input,String key,String trace){return guarded(products,()->delegate.reserve(input,key,trace));}
    public UUID register(Saga saga,String key,String trace){return guarded(sales,()->delegate.register(saga,key,trace));}
    public void release(Reservation original,String key,String trace){guarded(products,()->{delegate.release(original,key,trace);return null;});}
    private static <T> T guarded(CircuitBreaker breaker,Supplier<T> call) {
        CircuitBreaker.Permit permit=breaker.acquire();
        if(permit==null)throw new ParticipantFailure(ParticipantFailure.Kind.CIRCUIT_OPEN);
        try {
            T result=call.get();
            permit.success();
            return result;
        }catch(ParticipantFailure failure) {
            if(failure.kind()==ParticipantFailure.Kind.TRANSIENT)permit.failure();else permit.neutral();
            throw failure;
        }catch(RuntimeException|Error failure){permit.neutral();throw failure;}
    }
}
