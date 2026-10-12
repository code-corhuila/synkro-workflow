package co.edu.corhuila.synkro.workflow.app;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
public final class BackgroundExecution implements ExecutionScheduler {
    private final SagaStore store;private final RegisterSale usecase;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"workflow-scan"));
    private final ConcurrentMap<UUID,CompletableFuture<Void>> scheduled=new ConcurrentHashMap<>();
    private final long interval;private volatile boolean closing;
    public BackgroundExecution(SagaStore store,RegisterSale usecase,int capacity,int queue,long interval){
        if(capacity<1||capacity>16||queue<1||queue>1000||interval<100||interval>60000)throw new IllegalArgumentException("Invalid executor bounds");
        this.store=store;this.usecase=usecase;this.interval=interval;
        workers=new ThreadPoolExecutor(capacity,capacity,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queue),r->new Thread(r,"workflow-executor"),new ThreadPoolExecutor.AbortPolicy());
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){scanner.scheduleWithFixedDelay(this::scan,0,interval,TimeUnit.MILLISECONDS);}
    public CompletableFuture<Void> schedule(UUID id) {
        if(closing)return CompletableFuture.completedFuture(null);
        var future=new CompletableFuture<Void>();var prior=scheduled.putIfAbsent(id,future);if(prior!=null)return prior;
        try{workers.execute(()->{try{usecase.execute(id);future.complete(null);}catch(Exception e){future.completeExceptionally(new IllegalStateException("Execution deferred"));}finally{scheduled.remove(id,future);}});}
        catch(RejectedExecutionException e){scheduled.remove(id,future);future.complete(null);}
        return future;
    }
    private void scan(){if(closing)return;try{for(UUID id:store.running(100))schedule(id);}catch(Exception ignored){}}
    @PreDestroy public void close(){
        closing=true;scanner.shutdownNow();workers.shutdown();
        try{if(!workers.awaitTermination(5,TimeUnit.SECONDS)){workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}}
        catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}
    }
}
