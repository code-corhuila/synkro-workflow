package co.edu.corhuila.synkro.workflow.domain.model;

import java.time.Instant;
import java.util.*;

public record Saga(UUID id, String idempotencyKey, UUID initiatedBy, SaleInput input,
                   Status status, List<String> completedSteps, String failedStep,
                   String errorDetail, Reservation reservation, UUID saleId,
                   Execution execution, Instant createdAt, Instant updatedAt) {
    public enum Status { RUNNING, COMPLETED, COMPENSATED, FAILED }
    public record Execution(String correlationId, String pendingStep, int attempts) {}
    public Saga { completedSteps=List.copyOf(completedSteps); }
    public static Saga create(UUID id,String key,UUID caller,SaleInput input,String trace,Instant now) {
        if(key==null || key.length()<8 || key.length()>128 || caller==null || trace==null)
            throw new WorkflowError("VALIDATION_ERROR");
        return new Saga(id,key,caller,input,Status.RUNNING,List.of(),null,null,null,null,new Execution(trace,null,0),now,now);
    }
    public Saga attempt(String step,Instant now) {
        return copy(status,completedSteps,failedStep,null,reservation,saleId,
            new Execution(execution.correlationId(),step,execution.attempts()+1),now);
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
    public Saga unresolved(Instant now) {
        return copy(Status.RUNNING,completedSteps,null,"OUTCOME_UNRESOLVED",reservation,null,execution,now);
    }
    private Saga copy(Status status,List<String> steps,String failed,String error,Reservation r,UUID sale,Execution x,Instant now) {
        return new Saga(id,idempotencyKey,initiatedBy,input,status,steps,failed,error,r,sale,x,createdAt,now);
    }
}
