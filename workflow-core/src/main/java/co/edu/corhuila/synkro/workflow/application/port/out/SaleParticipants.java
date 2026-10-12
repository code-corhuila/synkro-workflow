package co.edu.corhuila.synkro.workflow.application.port.out;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import java.util.UUID;
public interface SaleParticipants {
    void validateCustomer(UUID id,String key,String correlation);
    Reservation reserve(SaleInput input,String key,String correlation);
    UUID register(Saga saga,String key,String correlation);
    void release(Reservation reservation,String key,String correlation);
}
