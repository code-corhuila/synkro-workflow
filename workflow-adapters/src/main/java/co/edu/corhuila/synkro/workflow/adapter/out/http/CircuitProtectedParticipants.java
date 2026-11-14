package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.synkro.workflow.application.port.out.SaleParticipants;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/** Participant-port Decorator; reservation and release share the Products circuit. */
public final class CircuitProtectedParticipants implements SaleParticipants {
    private final SaleParticipants delegate;
    private final ParticipantCircuit customers,products,sales;

    public CircuitProtectedParticipants(SaleParticipants delegate,Clock clock) {
        this.delegate=delegate;
        customers=new ParticipantCircuit(clock,3,Duration.ofSeconds(15));
        products=new ParticipantCircuit(clock,3,Duration.ofSeconds(15));
        sales=new ParticipantCircuit(clock,3,Duration.ofSeconds(15));
    }
    public void validateCustomer(UUID id,String key,String correlation) {
        dispatch(customers,()->{delegate.validateCustomer(id,key,correlation);return null;});
    }
    public Reservation reserve(SaleInput input,String key,String correlation) {
        return dispatch(products,()->delegate.reserve(input,key,correlation));
    }
    public UUID register(Saga saga,String key,String correlation) {
        return dispatch(sales,()->delegate.register(saga,key,correlation));
    }
    public void release(Reservation reservation,String key,String correlation) {
        dispatch(products,()->{delegate.release(reservation,key,correlation);return null;});
    }
    private static <T> T dispatch(ParticipantCircuit circuit,Supplier<T> operation) {
        var admission=circuit.tryEnter();
        if(admission==null)throw new ParticipantFailure(ParticipantFailure.Kind.CIRCUIT_OPEN);
        try {
            T receipt=operation.get();
            admission.success();
            return receipt;
        } catch(ParticipantFailure failure) {
            if(failure.kind()==ParticipantFailure.Kind.TRANSIENT)admission.failure();
            else admission.neutral();
            throw failure;
        } catch(RuntimeException | Error failure) {
            admission.neutral();
            throw failure;
        }
    }
}
