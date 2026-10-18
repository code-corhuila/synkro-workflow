package co.edu.corhuila.synkro.workflow.adapter.in.http;
import co.edu.corhuila.synkro.workflow.application.port.out.SagaStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.Map;
@RestController
public class StoreHealthController {
    private final SagaStore store;private final Clock clock;
    public StoreHealthController(SagaStore store,Clock clock){this.store=store;this.clock=clock;}
    @GetMapping("/health") ResponseEntity<?> health(){
        boolean healthy=store.healthy();return ResponseEntity.status(healthy?200:503).body(Map.of("status",healthy?"ok":"degraded","service","synkro-workflow","timestamp",clock.instant(),"dependencies",Map.of("database",healthy?"ok":"down")));
    }
}
