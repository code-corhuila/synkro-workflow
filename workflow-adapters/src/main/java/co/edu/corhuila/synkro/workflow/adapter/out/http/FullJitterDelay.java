package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.RetryDelay;
import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Strategy behind the RetryDelay port: exponential full jitter. The wait is drawn from 0..min(2000, 100&lt;&lt;(attempt-1)) ms.
 * Randomness and waiting are injected so tests are fast and deterministic; the use case only sees the port.
 */
public final class FullJitterDelay implements RetryDelay {
    @FunctionalInterface public interface Wait { void sleep(long millis) throws InterruptedException; }
    private final LongUnaryOperator inclusiveDraw;
    private final Wait wait;
    /** @param inclusiveDraw returns a value in 0..bound, both inclusive */
    public FullJitterDelay(LongUnaryOperator inclusiveDraw,Wait wait){this.inclusiveDraw=inclusiveDraw;this.wait=wait;}
    public static FullJitterDelay system(){return new FullJitterDelay(bound->ThreadLocalRandom.current().nextLong(bound+1),Thread::sleep);}
    public static long bound(int attempt){return Math.min(2000L,100L<<(Math.max(1,Math.min(attempt,30))-1));}
    public void pause(int attempt) {
        long bound=bound(attempt),millis=inclusiveDraw.applyAsLong(bound);
        if(millis<0||millis>bound)throw new WorkflowError("INTERNAL_ERROR");
        try{wait.sleep(millis);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new WorkflowError("INTERNAL_ERROR");}
    }
}
