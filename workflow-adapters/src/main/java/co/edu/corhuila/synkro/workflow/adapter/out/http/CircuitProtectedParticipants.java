package co.edu.corhuila.synkro.workflow.adapter.out.http;
import co.edu.corhuila.synkro.workflow.application.port.out.SaleParticipants;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import java.time.Clock;
import java.util.UUID;
/** Compilation-only scaffold; unchanged delegation, no protection implemented yet. */
public final class CircuitProtectedParticipants implements SaleParticipants {
    private final SaleParticipants delegate;
    public CircuitProtectedParticipants(SaleParticipants delegate,Clock clock) { this.delegate=delegate; }
    public void validateCustomer(UUID id,String key,String correlation) { delegate.validateCustomer(id,key,correlation); }
    public Reservation reserve(SaleInput input,String key,String correlation) { return delegate.reserve(input,key,correlation); }
    public UUID register(Saga saga,String key,String correlation) { return delegate.register(saga,key,correlation); }
    public void release(Reservation reservation,String key,String correlation) { delegate.release(reservation,key,correlation); }
}
