package co.edu.corhuila.synkro.workflow.adapter.out.http;
import co.edu.corhuila.synkro.workflow.application.port.out.RetryDelay;
import java.util.function.LongUnaryOperator;
/** Compilation-only scaffold; no retry delay implemented yet. */
public final class FullJitterRetryDelay implements RetryDelay {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public FullJitterRetryDelay(LongUnaryOperator draw,Sleeper sleep) {}
    public static long bound(int attempt) { return 0; }
    public static FullJitterRetryDelay system() { return new FullJitterRetryDelay(n->0,n->{}); }
    public void pause(int attempt) {}
}
