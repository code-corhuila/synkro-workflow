package co.edu.corhuila.synkro.workflow.adapter.in.http;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
public final class HttpErrors {
    private HttpErrors(){}
    public static String trace(HttpServletRequest r){return (String)r.getAttribute("workflow.trace");}
    public static Map<String,Object> body(String code,String trace){return Map.of("error",code,"message",switch(code){case "UNAUTHORIZED"->"Missing or invalid authentication token";case "FORBIDDEN"->"Operation not authorized";case "NOT_FOUND"->"Resource not found";case "VALIDATION_ERROR"->"Invalid request";default->"Unexpected error";},"traceId",trace);}
    public static void write(ObjectMapper json,HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),body(code,trace(request)));
    }
}
