package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.RetryDelay;
import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/** Full-jitter Strategy; draw and wait are injectable to keep tests deterministic. */
public final class FullJitterRetryDelay implements RetryDelay {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final LongUnaryOperator draw;
    private final Sleeper sleep;

    public FullJitterRetryDelay(LongUnaryOperator draw,Sleeper sleep) {
        this.draw=draw;
        this.sleep=sleep;
    }
    public static FullJitterRetryDelay system() {
        return new FullJitterRetryDelay(limit->ThreadLocalRandom.current().nextLong(limit+1),Thread::sleep);
    }
    public static long bound(int attempt) {
        if(attempt<=1)return 100;
        if(attempt>=6)return 2000;
        return 100L<<(attempt-1);
    }
    public void pause(int attempt) {
        long limit=bound(attempt),millis=draw.applyAsLong(limit);
        if(millis<0 || millis>limit)throw new WorkflowError("INTERNAL_ERROR");
        try { sleep.sleep(millis); }
        catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkflowError("INTERNAL_ERROR");
        }
    }
}
