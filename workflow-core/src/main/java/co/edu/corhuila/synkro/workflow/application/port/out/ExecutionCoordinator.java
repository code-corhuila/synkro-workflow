package co.edu.corhuila.synkro.workflow.application.port.out;
import co.edu.corhuila.synkro.workflow.domain.model.Saga;
import java.util.*;
public interface ExecutionCoordinator {
    Optional<Lease> tryOwn(UUID sagaId);
    interface Lease extends AutoCloseable {
        Saga load();
        void save(Saga saga);
        void close();
    }
}
