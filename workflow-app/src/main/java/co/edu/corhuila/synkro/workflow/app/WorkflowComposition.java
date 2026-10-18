package co.edu.corhuila.synkro.workflow.app;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import co.edu.corhuila.synkro.workflow.adapter.out.persistence.JdbcSagaStore;
import co.edu.corhuila.synkro.workflow.adapter.out.http.HttpSaleParticipants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
@Configuration
public class WorkflowComposition {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean @Profile("!test") @ConditionalOnMissingBean(SagaStore.class) DataSource dataSource(@Value("${WORKFLOW_DATABASE_URL:jdbc:postgresql://synkro-db:5432/synkro}") String url,@Value("${WORKFLOW_APP_PASSWORD_FILE:}") String passwordFile){
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("workflow_app");config.setPassword(secret(passwordFile));
        config.setMaximumPoolSize(12);config.setMinimumIdle(0);config.setConnectionTimeout(3000);config.setValidationTimeout(2000);config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","10");config.addDataSourceProperty("ApplicationName","synkro-workflow");
        return new HikariDataSource(config);
    }
    @Bean @Profile("!test") @ConditionalOnMissingBean(SagaStore.class) JdbcSagaStore sagaStore(DataSource ds,ObjectMapper json){return new JdbcSagaStore(ds,json);}
    @Bean @ConditionalOnMissingBean(SaleParticipants.class) SaleParticipants participants(ObjectMapper json,
        @Value("${CUSTOMERS_BASE_URL:http://synkro-customers-api:8080}") String customers,
        @Value("${PRODUCTS_BASE_URL:http://synkro-products-api:8080}") String products,
        @Value("${SALES_BASE_URL:http://synkro-sales-api:8080}") String sales,
        @Value("${SERVICE_TOKEN_FILE:}") String file,@Value("${SERVICE_TOKEN:}") String token){
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new HttpSaleParticipants(client,json,URI.create(customers),URI.create(products),URI.create(sales),()->file.isBlank()?token:secret(file),Duration.ofSeconds(3));
    }
    @Bean RegisterSale usecase(SagaStore store,ExecutionCoordinator coordination,SaleParticipants participants,Clock clock){
        return new RegisterSale(store,coordination,participants,clock,UUID::randomUUID,attempt->{
            long bound=Math.min(2000,100L<<(attempt-1));
            try{Thread.sleep(bound+ThreadLocalRandom.current().nextLong(bound/2+1));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new WorkflowError("INTERNAL_ERROR");}
        },3);
    }
    @Bean BackgroundExecution background(SagaStore store,RegisterSale usecase,@Value("${WORKFLOW_EXECUTORS:4}") int capacity,@Value("${WORKFLOW_QUEUE:64}") int queue,@Value("${WORKFLOW_SCAN_INTERVAL_MS:1000}") long interval){
        return new BackgroundExecution(store,usecase,capacity,queue,interval);
    }
    private static String secret(String file){
        try{String value=Files.readString(Path.of(file)).replaceFirst("[\\r\\n]+$","");if(value.isEmpty())throw new IllegalArgumentException();return value;}
        catch(Exception e){throw new IllegalStateException("Required readable runtime secret file is absent");}
    }
}
