package co.edu.corhuila.synkro.workflow.adapter.in.http;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
@Configuration
public class SecurityConfiguration {
    @Bean JwtDecoder decoder(@Value("${JWT_PUBLIC_KEY:}") String pem,@Value("${JWT_PUBLIC_KEY_FILE:}") String file) {
        try {
            if(pem.isBlank())pem=Files.readString(Path.of(file));
            byte[] bytes=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""));
            var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
            if(key.getModulus().bitLength()<2048)throw new IllegalArgumentException();
            var decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),jwt->{
                try {
                    if(jwt.getExpiresAt()==null||jwt.getIssuedAt()==null||jwt.getId()==null||jwt.getId().isBlank()||jwt.getSubject()==null||jwt.getSubject().isBlank())throw new IllegalArgumentException();
                    for(String field:List.of("roles","permissions")) {
                        Object claim=jwt.getClaims().get(field);
                        if(!(claim instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof String text)||text.isBlank()))throw new IllegalArgumentException();
                    }
                    List<String> roles=jwt.getClaimAsStringList("roles");if(roles.isEmpty())throw new IllegalArgumentException();
                    if(!roles.contains("SERVICE")) {
                        UUID id=UUID.fromString(jwt.getSubject());if(!id.toString().equals(jwt.getSubject().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
                    }
                    if(jwt.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(30))||!jwt.getExpiresAt().isAfter(jwt.getIssuedAt()))throw new IllegalArgumentException();
                    return OAuth2TokenValidatorResult.success();
                }catch(Exception e){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));}
            }));
            return decoder;
        }catch(Exception e){throw new IllegalStateException("Valid Auth public key is required");}
    }
    @Bean SecurityFilterChain filterChain(HttpSecurity http,ObjectMapper json) throws Exception {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt->jwt.getClaimAsStringList("roles").stream().<org.springframework.security.core.GrantedAuthority>map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll().requestMatchers("/health").permitAll()
                .requestMatchers("/api/v1/sagas/**").hasAnyRole("ADMIN","SALESPERSON").anyRequest().authenticated())
            .addFilterBefore(new CorrelationFilter(),BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(c->c.jwt(j->j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((r,s,e)->HttpErrors.write(json,r,s,401,"UNAUTHORIZED"))
                .accessDeniedHandler((r,s,e)->HttpErrors.write(json,r,s,403,"FORBIDDEN")))
            .exceptionHandling(c->c.authenticationEntryPoint((r,s,e)->HttpErrors.write(json,r,s,401,"UNAUTHORIZED"))
                .accessDeniedHandler((r,s,e)->HttpErrors.write(json,r,s,403,"FORBIDDEN")));
        return http.build();
    }
}
