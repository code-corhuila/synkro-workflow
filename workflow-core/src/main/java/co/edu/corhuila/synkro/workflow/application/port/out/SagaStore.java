package co.edu.corhuila.synkro.workflow.application.port.out;
import co.edu.corhuila.synkro.workflow.domain.model.Saga;
import java.util.*;
public interface SagaStore {
    record Created(Saga saga, boolean created) {}
    Created create(Saga saga);
    Optional<Saga> find(UUID id);
    List<UUID> running(int limit);
    boolean healthy();
}
