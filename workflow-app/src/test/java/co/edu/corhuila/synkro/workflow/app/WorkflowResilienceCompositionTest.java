package co.edu.corhuila.synkro.workflow.app;

import co.edu.corhuila.synkro.workflow.adapter.out.http.CircuitProtectedParticipants;
import co.edu.corhuila.synkro.workflow.adapter.out.http.FullJitterRetryDelay;
import co.edu.corhuila.synkro.workflow.adapter.out.http.HttpSaleParticipants;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Narrow wiring test of the existing factory methods. Private collaborators are located by their type
 * (never by source text) because the composition exposes no accessors.
 */
class WorkflowResilienceCompositionTest {
    final WorkflowComposition composition=new WorkflowComposition();

    SaleParticipants participants() {
        return composition.participants(JsonMapper.builder().build(),"http://customers.invalid","http://products.invalid","http://sales.invalid","","service-sentinel");
    }
    static <T> T fieldOfType(Object owner,Class<T> type) throws Exception {
        List<T> found=new ArrayList<>();
        for(Field field:owner.getClass().getDeclaredFields()) {
            if(!type.isAssignableFrom(field.getType()))continue;
            field.setAccessible(true);found.add(type.cast(field.get(owner)));
        }
        assertThat(found).as("exactly one collaborator of type "+type.getSimpleName()+" in "+owner.getClass().getSimpleName()).hasSize(1);
        return found.getFirst();
    }
    static int attemptBudget(RegisterSale usecase) throws Exception {
        Field field=RegisterSale.class.getDeclaredField("maxAttempts");field.setAccessible(true);return field.getInt(usecase);
    }
    RegisterSale usecase() {
        var none=new ExecutionCoordinator(){public Optional<Lease> tryOwn(UUID id){return Optional.empty();}};
        var store=new SagaStore(){
            public Created create(co.edu.corhuila.synkro.workflow.domain.model.Saga saga){throw new UnsupportedOperationException();}
            public Optional<co.edu.corhuila.synkro.workflow.domain.model.Saga> find(UUID id){return Optional.empty();}
            public List<UUID> running(int limit){return List.of();}
            public boolean healthy(){return true;}
        };
        return composition.usecase(store,none,participants(),Clock.systemUTC());
    }

    @Test void theParticipantsPortIsTheCircuitDecoratorOverTheRealHttpAdapter() throws Exception {
        SaleParticipants wired=participants();
        assertThat(wired).isInstanceOf(CircuitProtectedParticipants.class);
        assertThat(fieldOfType(wired,SaleParticipants.class)).isInstanceOf(HttpSaleParticipants.class);
    }
    @Test void theHttpAdapterKeepsTheApprovedTimeouts() throws Exception {
        var http=fieldOfType(participants(),SaleParticipants.class);
        assertThat(fieldOfType(http,Duration.class)).isEqualTo(Duration.ofSeconds(3));            // call timeout
        assertThat(fieldOfType(http,HttpClient.class).connectTimeout()).contains(Duration.ofSeconds(2)); // connect timeout
    }
    @Test void theUseCaseUsesTheFullJitterStrategyBehindTheRetryDelayPort() throws Exception {
        RegisterSale usecase=usecase();
        RetryDelay delay=fieldOfType(usecase,RetryDelay.class);
        assertThat(delay).isInstanceOf(FullJitterRetryDelay.class);
    }
    @Test void theUseCaseKeepsTheThreeAttemptBudget() throws Exception {
        assertThat(attemptBudget(usecase())).isEqualTo(3);
    }
}
