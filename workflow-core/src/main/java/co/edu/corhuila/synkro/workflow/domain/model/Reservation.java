package co.edu.corhuila.synkro.workflow.domain.model;

import java.util.*;

public record Reservation(UUID reservationId, List<Line> lines) {
    public Reservation {
        if(reservationId==null || lines==null || lines.isEmpty()) throw new WorkflowError("INTERNAL_ERROR");
        lines=List.copyOf(lines);
    }
    public record Line(UUID productId, int quantity, long unitPriceCents) {
        public Line { if(productId==null || quantity<1 || unitPriceCents<1) throw new WorkflowError("INTERNAL_ERROR"); }
    }
}
