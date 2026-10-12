package co.edu.corhuila.synkro.workflow.application.port.out;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
public interface ExecutionScheduler { CompletableFuture<Void> schedule(UUID sagaId); }
