package co.edu.corhuila.synkro.workflow.app;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.UUID;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Fix #1 (HU-AUTH-01): without this, Spring's internal
                // second dispatch to /error gets re-evaluated with a
                // cleared security context and turns every real error
                // behind this gate into a 401. A direct, external request
                // to /error is a REQUEST dispatch, not ERROR, so it is NOT
                // covered here and stays denied (see the test above).
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new MinimalBearerCheckFilter(), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                // Fix #3 (HU-AUTH-01): Spring defaults to ISO-8859-1 otherwise.
                response.setContentType("application/json;charset=UTF-8");
                String traceId = UUID.randomUUID().toString();
                response.getWriter().write(
                    "{\"error\":\"UNAUTHORIZED\",\"message\":\"a valid Authorization header is required\",\"traceId\":\"" + traceId + "\"}"
                );
            }));
        return http.build();
    }
}
