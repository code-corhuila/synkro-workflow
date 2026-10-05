package co.edu.corhuila.synkro.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WorkflowApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApiApplication.class, args);
    }
}
