package co.edu.corhuila.synkro.workflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowHttpTest {

    @LocalServerPort
    int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void health_returnsOkWithNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"ok\"");
        assertThat(response.getBody()).contains("\"service\":\"synkro-workflow\"");
    }

    @Test
    void protectedRoute_returns401WithNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/sagas/anything"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"");
    }

    @Test
    void unauthorizedResponse_hasUtf8ContentType() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/sagas/anything"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String contentType = response.getHeaders().getFirst("Content-Type");
        assertThat(contentType).containsIgnoringCase("UTF-8");
    }

    @Test
    void protectedRoute_withPresentButMalformedToken_reachesTheController() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer not-a-real-token");
        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/sagas/anything"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void authenticatedRequestToAnUnknownRoute_keepsItsRealStatus() {
        // Guards against the /error-dispatch bug found in HU-AUTH-01:
        // Spring Security re-evaluating its internal error dispatch must
        // not turn a real 404 into a 401.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer x.y.z");
        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/does-not-exist"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void directRequestToErrorPath_isStillDenied() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/error"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
