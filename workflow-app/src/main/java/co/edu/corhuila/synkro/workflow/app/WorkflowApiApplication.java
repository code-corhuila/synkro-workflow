package co.edu.corhuila.synkro.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.synkro.workflow", exclude = {UserDetailsServiceAutoConfiguration.class, org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class})
public class WorkflowApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApiApplication.class, args);
    }
}
