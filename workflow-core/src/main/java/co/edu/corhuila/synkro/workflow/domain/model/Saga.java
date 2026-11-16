package co.edu.corhuila.synkro.workflow.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record Saga(UUID id, String idempotencyKey, UUID initiatedBy, SaleInput input,
                   Status status, List<String> completedSteps, String failedStep,
                   String errorDetail, Reservation reservation, UUID saleId,
                   Execution execution, Instant createdAt, Instant updatedAt) {
    public enum Status { RUNNING, COMPLETED, COMPENSATED, FAILED }
    /** Public answer to "who resumes this RUNNING saga": the background process later, or an operator. */
    public enum Recovery { AUTOMATIC_RETRY, OPERATOR_REQUIRED }
    static final String RETRY_DEFERRED="RETRY_DEFERRED";
    static final String OUTCOME_UNRESOLVED="OUTCOME_UNRESOLVED";

    /** Private progress of the pending step: attempts in the current round and deferred rounds already waited. */
    public record Execution(String correlationId, String pendingStep, int attempts, int deferrals, Instant retryAt) {
        public Execution { retryAt=retryAt==null?null:retryAt.truncatedTo(ChronoUnit.MILLIS); }
        public Execution(String correlationId,String pendingStep,int attempts) { this(correlationId,pendingStep,attempts,0,null); }
    }
    public Saga {
        completedSteps=List.copyOf(completedSteps);
        createdAt=createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt=updatedAt.truncatedTo(ChronoUnit.MICROS);
    }
    public static Saga create(UUID id,String key,UUID caller,SaleInput input,String trace,Instant now) {
        if(key==null || key.length()<8 || key.length()>128 || caller==null || trace==null)
            throw new WorkflowError("VALIDATION_ERROR");
        return new Saga(id,key,caller,input,Status.RUNNING,List.of(),null,null,null,null,new Execution(trace,null,0),now,now);
    }
    public Saga attempt(String step,Instant now) {
        return copy(status,completedSteps,failedStep,null,reservation,saleId,
            new Execution(execution.correlationId(),step,execution.attempts()+1,execution.deferrals(),null),now);
    }
    public Saga success(String step,Reservation reserved,UUID sale,Instant now) {
        var steps=new ArrayList<>(completedSteps);steps.add(step);
        return copy(sale!=null?Status.COMPLETED:Status.RUNNING,steps,null,null,reserved,sale,
            new Execution(execution.correlationId(),null,0),now);
    }
    public Saga reject(String step,Instant now) {
        return copy(Status.RUNNING,completedSteps,step,"PARTICIPANT_REJECTED",reservation,null,
            new Execution(execution.correlationId(),null,0),now);
    }
    public Saga finishCompensation(boolean success,Instant now) {
        return copy(success?Status.COMPENSATED:Status.FAILED,completedSteps,failedStep,
            success?"PARTICIPANT_REJECTED":"COMPENSATION_EXHAUSTED",reservation,null,execution,now);
    }
    /** The outcome cannot be trusted: only an operator with participant evidence may continue. */
    public Saga unresolved(Instant now) {
        return copy(Status.RUNNING,completedSteps,null,OUTCOME_UNRESOLVED,reservation,null,execution,now);
    }
    /** The participant was unavailable: keep the pending step and its key, and retry forward at retryAt. */
    public Saga defer(Instant retryAt,Instant now) {
        return copy(Status.RUNNING,completedSteps,null,RETRY_DEFERRED,reservation,null,
            new Execution(execution.correlationId(),execution.pendingStep(),execution.attempts(),execution.deferrals()+1,retryAt),now);
    }
    /** Opens a new bounded round of attempts for a deferred step whose retry is due. */
    public Saga resumeDeferred(Instant now) {
        return copy(Status.RUNNING,completedSteps,null,null,reservation,null,
            new Execution(execution.correlationId(),execution.pendingStep(),0,execution.deferrals(),null),now);
    }
    public boolean isDeferred() { return status==Status.RUNNING && RETRY_DEFERRED.equals(errorDetail); }
    public boolean isRetryDue(Instant now) { return isDeferred() && !execution.retryAt().isAfter(now); }
    public boolean requiresOperator() { return status==Status.RUNNING && OUTCOME_UNRESOLVED.equals(errorDetail); }
    public Recovery recovery() {
        if(isDeferred()) return Recovery.AUTOMATIC_RETRY;
        if(requiresOperator()) return Recovery.OPERATOR_REQUIRED;
        return null;
    }
    private Saga copy(Status status,List<String> steps,String failed,String error,Reservation r,UUID sale,Execution x,Instant now) {
        return new Saga(id,idempotencyKey,initiatedBy,input,status,steps,failed,error,r,sale,x,createdAt,now);
    }
}
