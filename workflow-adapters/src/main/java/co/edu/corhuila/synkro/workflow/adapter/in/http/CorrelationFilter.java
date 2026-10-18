package co.edu.corhuila.synkro.workflow.adapter.in.http;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
public final class CorrelationFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String trace=request.getHeader("X-Correlation-Id");if(trace==null||trace.isBlank())trace=UUID.randomUUID().toString();
        request.setAttribute("workflow.trace",trace);response.setHeader("X-Correlation-Id",trace);chain.doFilter(request,response);
    }
}
