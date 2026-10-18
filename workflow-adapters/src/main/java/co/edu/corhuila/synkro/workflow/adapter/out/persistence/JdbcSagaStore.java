package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Dedicated PostgreSQL session owns all committed transitions of one executor. */
public final class JdbcSagaStore implements SagaStore,ExecutionCoordinator {
    private final DataSource dataSource;
    private final ObjectMapper json;
    private static final String COLUMNS="saga_id,idempotency_key,initiated_by,input,status,completed_steps,failed_step,error_detail,step_results,created_at,updated_at";
    public record Results(Reservation reservation,UUID saleId,Saga.Execution execution) {}
    public JdbcSagaStore(DataSource dataSource,ObjectMapper json) {this.dataSource=dataSource;this.json=json;}
    private Connection connect() throws SQLException {
        Connection c=dataSource.getConnection();
        try(var s=c.createStatement()) {
            s.execute("SET statement_timeout='5s'");
            s.execute("SET lock_timeout='3s'");
            s.execute("SET idle_session_timeout='15s'");
        } catch(SQLException e) {c.close();throw e;}
        return c;
    }
    public Created create(Saga saga) {
        String sql="INSERT INTO workflow_schema.saga_instance(saga_id,saga_type,idempotency_key,initiated_by,input,step_results,created_at,updated_at) VALUES (?,'register-sale',?,?,?::jsonb,?::jsonb,?,?) ON CONFLICT (saga_type,idempotency_key) DO NOTHING";
        try(var c=connect();var insert=c.prepareStatement(sql)) {
            insert.setObject(1,saga.id());insert.setString(2,saga.idempotencyKey());insert.setObject(3,saga.initiatedBy());
            insert.setString(4,json.writeValueAsString(saga.input()));insert.setString(5,results(saga));
            insert.setTimestamp(6,Timestamp.from(saga.createdAt()));insert.setTimestamp(7,Timestamp.from(saga.updatedAt()));
            boolean created=insert.executeUpdate()==1;
            try(var select=c.prepareStatement("SELECT "+COLUMNS+" FROM workflow_schema.saga_instance WHERE saga_type='register-sale' AND idempotency_key=?")) {
                select.setString(1,saga.idempotencyKey());try(var rs=select.executeQuery()) {if(!rs.next())throw new SQLException();return new Created(read(rs),created);}
            }
        } catch(Exception e) {throw safe();}
    }
    public Optional<Saga> find(UUID id) {try(var c=connect()){return find(c,id);}catch(Exception e){throw safe();}}
    private Optional<Saga> find(Connection c,UUID id) throws Exception {
        try(var s=c.prepareStatement("SELECT "+COLUMNS+" FROM workflow_schema.saga_instance WHERE saga_id=?")) {
            s.setObject(1,id);try(var r=s.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }
    }
    public List<UUID> running(int limit) {
        try(var c=connect();var s=c.prepareStatement("SELECT saga_id FROM workflow_schema.saga_instance WHERE status='RUNNING' AND error_detail IS DISTINCT FROM 'OUTCOME_UNRESOLVED' ORDER BY created_at,saga_id LIMIT ?")) {
            s.setInt(1,Math.max(1,Math.min(limit,1000)));try(var r=s.executeQuery()){var ids=new ArrayList<UUID>();while(r.next())ids.add(r.getObject(1,UUID.class));return ids;}
        }catch(Exception e){throw safe();}
    }
    public boolean healthy() {
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT saga_id FROM workflow_schema.saga_instance LIMIT 1")) {return true;}
        catch(Exception e){return false;}
    }
    public Optional<Lease> tryOwn(UUID id) {
        Connection c=null;
        int a=(int)(id.getMostSignificantBits()^(id.getMostSignificantBits()>>>32));
        int b=(int)(id.getLeastSignificantBits()^(id.getLeastSignificantBits()>>>32));
        try {
            c=connect();
            try(var s=c.createStatement()){s.execute("SET application_name='synkro-workflow-owner'");}
            try(var s=c.prepareStatement("SELECT pg_try_advisory_lock(?,?)")) {
                s.setInt(1,a);s.setInt(2,b);try(var r=s.executeQuery()){r.next();if(!r.getBoolean(1)){c.close();return Optional.empty();}}
            }
            return Optional.of(new Owned(c,id,a,b));
        }catch(Exception e){if(c!=null)try{c.close();}catch(SQLException ignored){}throw safe();}
    }
    private final class Owned implements Lease {
        private final Connection connection;
        private final UUID id;
        private final int a,b;
        private boolean closed;
        Owned(Connection c,UUID id,int a,int b){this.connection=c;this.id=id;this.a=a;this.b=b;}
        public Saga load(){try{return find(connection,id).orElse(null);}catch(Exception e){throw safe();}}
        public void save(Saga saga) {
            if(closed || !id.equals(saga.id()))throw safe();
            String sql="UPDATE workflow_schema.saga_instance SET status=?,step_results=?::jsonb,completed_steps=?,failed_step=?,error_detail=?,updated_at=? WHERE saga_id=? AND status='RUNNING'";
            try(var s=connection.prepareStatement(sql)) {
                s.setString(1,saga.status().name());s.setString(2,results(saga));s.setArray(3,connection.createArrayOf("text",saga.completedSteps().toArray(String[]::new)));
                s.setString(4,saga.failedStep());s.setString(5,saga.errorDetail());s.setTimestamp(6,Timestamp.from(saga.updatedAt()));s.setObject(7,id);
                if(s.executeUpdate()!=1)throw safe();
            }catch(Exception e){throw safe();}
        }
        public void close() {
            if(closed)return;closed=true;
            try(var s=connection.prepareStatement("SELECT pg_advisory_unlock(?,?)")){s.setInt(1,a);s.setInt(2,b);s.execute();}
            catch(SQLException e){try{connection.abort(Runnable::run);}catch(SQLException ignored){}}
            finally {try{connection.close();}catch(SQLException ignored){}}
        }
    }
    private String results(Saga s) throws Exception {return json.writeValueAsString(new Results(s.reservation(),s.saleId(),s.execution()));}
    private Saga read(ResultSet r) throws Exception {
        Results result=json.readValue(r.getString("step_results"),Results.class);
        return new Saga(r.getObject("saga_id",UUID.class),r.getString("idempotency_key"),r.getObject("initiated_by",UUID.class),
            json.readValue(r.getString("input"),SaleInput.class),Saga.Status.valueOf(r.getString("status")),
            Arrays.asList((String[])r.getArray("completed_steps").getArray()),r.getString("failed_step"),r.getString("error_detail"),
            result.reservation(),result.saleId(),result.execution(),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private static WorkflowError safe(){return new WorkflowError("INTERNAL_ERROR");}
}
