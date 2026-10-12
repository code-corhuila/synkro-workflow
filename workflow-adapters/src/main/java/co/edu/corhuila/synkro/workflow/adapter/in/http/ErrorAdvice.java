package co.edu.corhuila.synkro.workflow.adapter.in.http;
import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestControllerAdvice
public class ErrorAdvice {
    private static final MediaType UTF8=MediaType.parseMediaType("application/json;charset=UTF-8");
    @ExceptionHandler(SagaController.InvalidInput.class) ResponseEntity<?> validation(SagaController.InvalidInput e,HttpServletRequest r){
        var body=new LinkedHashMap<>(HttpErrors.body("VALIDATION_ERROR",HttpErrors.trace(r)));body.put("details",e.details);return ResponseEntity.badRequest().contentType(UTF8).body(body);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> malformed(HttpServletRequest r){return validation(new SagaController.InvalidInput(List.of(Map.of("field","body","message","must be valid JSON"))),r);}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> notfound(HttpServletRequest r){return response(404,"NOT_FOUND",r);}
    @ExceptionHandler(WorkflowError.class) ResponseEntity<?> workflow(WorkflowError e,HttpServletRequest r){
        return switch(e.getMessage()){case "FORBIDDEN"->response(403,"FORBIDDEN",r);case "VALIDATION_ERROR"->validation(new SagaController.InvalidInput(List.of(Map.of("field","request","message","invalid request"))),r);case "NOT_FOUND"->response(404,"NOT_FOUND",r);default->response(500,"INTERNAL_ERROR",r);};
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(HttpServletRequest r){return response(500,"INTERNAL_ERROR",r);}
    private ResponseEntity<?> response(int status,String code,HttpServletRequest r){return ResponseEntity.status(status).contentType(UTF8).body(HttpErrors.body(code,HttpErrors.trace(r)));}
}
