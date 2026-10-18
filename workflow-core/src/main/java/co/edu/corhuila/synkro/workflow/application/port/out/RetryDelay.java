package co.edu.corhuila.synkro.workflow.application.port.out;
/** Runtime supplies bounded exponential backoff/jitter; tests inject virtual scheduling. */
@FunctionalInterface public interface RetryDelay { void pause(int attempt); }
