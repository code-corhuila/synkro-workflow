package co.edu.corhuila.synkro.workflow.domain.model;

import java.util.*;

public record SaleInput(UUID customerId, List<Line> lines) {
    public SaleInput {
        if (customerId==null || lines==null || lines.isEmpty() || lines.size()>100 || lines.stream().anyMatch(Objects::isNull))
            throw new WorkflowError("VALIDATION_ERROR");
        lines=lines.stream().sorted(Comparator.comparing(l->l.productId().toString())).toList();
        if(lines.stream().map(Line::productId).distinct().count()!=lines.size()) throw new WorkflowError("VALIDATION_ERROR");
    }
    public record Line(UUID productId, int quantity) {
        public Line { if(productId==null || quantity<1) throw new WorkflowError("VALIDATION_ERROR"); }
    }
}
