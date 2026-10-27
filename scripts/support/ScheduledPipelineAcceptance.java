import java.sql.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Standalone acceptance driver; launches an independent production JAR. Never invokes a Dispatcher or performs DDL/cleanup. */
class ScheduledPipelineAcceptance {
    static class AcceptanceFailure extends RuntimeException { AcceptanceFailure(String message){super(message);} }
    record Sample(String id,String status,double acceptedMs,double completedMs){}
    static final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static String base;
    static String prefix;
    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args[0]).toAbsolutePath(), output=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        String run=UUID.randomUUID().toString(); prefix="phase5:"+run+":";
        Properties credentials=new Properties();credentials.setProperty("user","ai_task_app");credentials.setProperty("password",required("MYSQL_IT_PASSWORD"));
        Process app=null;
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try(Connection db=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_task_platform?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowMultiQueries=false&connectTimeout=5000&socketTimeout=10000",credentials)) {
            credentials.clear();db.setReadOnly(true);
            require("ai_task_platform".equals(scalar(db,"SELECT DATABASE()")),"Wrong database");
            String mysql=scalar(db,"SELECT VERSION()");require(mysql.matches("8\\.0\\.\\d+.*"),"Requires MySQL 8.0");
            require(scalar(db,"SELECT CURRENT_USER()").startsWith("ai_task_app@"),"Requires business account");
            require(count(db,"SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key LIKE ?",prefix+"%") == 0,"Namespace already exists");
            String tasksBefore=fingerprint(db,false),eventsBefore=fingerprint(db,true);
            int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
            base="http://127.0.0.1:"+port;
            var process=new ProcessBuilder(java(),"-Xms128m","-Xmx256m","-jar",jar.toString(),"--debug=false","--logging.level.root=INFO");
            var env=process.environment();
            env.put("SERVER_ADDRESS","127.0.0.1");env.put("SERVER_PORT",Integer.toString(port));
            env.put("TASK_STORAGE_MODE","mysql");env.put("AI_TASK_DB_HOST","127.0.0.1");env.put("AI_TASK_DB_PORT","3306");
            env.put("AI_TASK_DB_USERNAME","ai_task_app");env.put("AI_TASK_DB_PASSWORD",required("MYSQL_IT_PASSWORD"));
            env.put("TASK_MESSAGING_MODE","rabbit");env.put("TASK_QUEUE_PREFIX","p1.phase5."+run);
            env.put("RABBITMQ_HOST","127.0.0.1");env.put("RABBITMQ_PORT","5673");
            env.put("RABBITMQ_USERNAME",required("RABBITMQ_USERNAME"));env.put("RABBITMQ_PASSWORD",required("RABBITMQ_PASSWORD"));
            env.put("RABBITMQ_VHOST","/ai-task-platform");env.put("TASK_OUTBOX_ENABLED","true");
            env.put("TASK_ACCEPTANCE_KEY_PREFIX",prefix);env.put("TASK_EXECUTION_TIMEOUT_ENABLED","true");
            env.put("TASK_EXECUTION_TIMEOUT_MS","60000");env.put("TASK_EXECUTION_TIMEOUT_CHECK_MS","1000");
            env.put("TASK_OUTBOX_POLL_INTERVAL_MS","1000");env.put("TASK_OUTBOX_BATCH_SIZE","20");
            env.put("TASK_RATE_LIMIT_ENABLED","false");env.put("MODEL_MODE","mock");env.remove("MODEL_API_KEY");
            process.redirectOutput(output.resolve("app.log").toFile());process.redirectError(output.resolve("app-error.log").toFile());
            app=process.start(); final Process running=app;
            long ready=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(true){require(running.isAlive(),"Production JAR exited during startup");try { if(call("GET","/api/tasks/"+UUID.randomUUID(),null).statusCode()==404){break;} }catch(java.io.IOException unavailable){}
                require(System.nanoTime()<ready,"Production startup deadline exceeded");Thread.sleep(100);}
            List<Sample> sequential=new ArrayList<>(),concurrent=new ArrayList<>();
            for(int i=0;i<8;i++){sequential.add(submit("seq-"+i,"hello phase5",true));}
            List<Future<Sample>> work=new ArrayList<>();
            for(int i=0;i<16;i++){int index=i;work.add(pool.submit(()->submit("parallel-"+index,"hello phase5",true)));}
            for(var future:work){concurrent.add(future.get(20,TimeUnit.SECONDS));}
            var failed=submit("failure","[MOCK_FAIL]",false);
            require("MODEL_CALL_FAILED".equals(scalar(db,"SELECT error_code FROM ai_text_task WHERE task_id=?",failed.id())),"Failure code mismatch");
            String first=sequential.get(0).id();
            var reused=call("POST","/api/tasks",payload(prefix+"seq-0","hello phase5"));
            require(reused.statusCode()==200&&first.equals(field(reused.body(),"taskId")),"Idempotent request mismatch");
            require(call("POST","/api/tasks",payload(prefix+"seq-0","different")).statusCode()==409,"Input conflict missing");
            require(count(db,"SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key LIKE ? AND status='SUCCESS' AND version=2 AND completed_at IS NOT NULL AND execution_token IS NULL AND execution_deadline IS NULL",prefix+"%") == 24,"Success invariants failed");
            require(count(db,"SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key LIKE ? AND status='FAILED' AND version=2",prefix+"%") == 1,"Failure invariants failed");
            require(count(db,"SELECT COUNT(*) FROM ai_task_outbox e JOIN ai_text_task t ON e.task_id=t.task_id WHERE t.idempotency_key LIKE ? AND e.status='SENT'",prefix+"%") == 25,"Events were not automatically sent");
            require(tasksBefore.equals(fingerprint(db,false))&&eventsBefore.equals(fingerprint(db,true)),"Historical metadata changed; fail acceptance");
            Files.writeString(output.resolve("samples.csv"),csv(sequential,concurrent,failed),StandardCharsets.UTF_8);
            String result=String.format(Locale.ROOT,"{\"passed\":true,\"run\":\"%s\",\"java\":\"%s\",\"mysql\":\"%s\",\"applicationPid\":%d,\"port\":%d,\"scheduledDispatch\":true,\"historicalUnchanged\":true,\"model\":\"mock\",\"thirdPartyCalls\":0,\"success\":24,\"failed\":1,\"timeout\":0,\"eventsSent\":25,\"concurrency\":4,\"sequential\":%s,\"concurrent\":%s,\"failureTask\":\"%s\",\"firstTask\":\"%s\",\"keyPrefix\":\"%s\"}%n",
                    run,System.getProperty("java.version"),mysql,app.pid(),port,stats(sequential),stats(concurrent),failed.id(),first,prefix);
            Files.writeString(output.resolve("result.json"),result,StandardCharsets.UTF_8);
            System.out.println("PHASE5_RESULT "+result.strip());
        }catch(Exception failure){System.err.println("PHASE5_FAILED type="+failure.getClass().getSimpleName()
                +(failure instanceof AcceptanceFailure ? " check="+failure.getMessage() : "")
                +"; diagnostics redacted; see local application log; no fallback");throw new AssertionError("Production acceptance failed; no success assumed");}
        finally {credentials.clear();pool.shutdownNow();if(app!=null){app.destroy();if(!app.waitFor(5,TimeUnit.SECONDS)){app.destroyForcibly();app.waitFor(5,TimeUnit.SECONDS);}}}
    }
    static Sample submit(String suffix,String input,boolean success)throws Exception {
        long begin=System.nanoTime();var accepted=call("POST","/api/tasks",payload(prefix+suffix,input));long acknowledged=System.nanoTime();
        require(accepted.statusCode()==202,"HTTP submit not accepted");String id=field(accepted.body(),"taskId");require(id.matches("[0-9a-f-]{36}"),"Invalid task ID");
        long deadline=begin+TimeUnit.SECONDS.toNanos(15);String state;
        do {var response=call("GET","/api/tasks/"+id,null);require(response.statusCode()==200,"Status query failed");state=field(response.body(),"status");
            if(state.equals("SUCCESS")||state.equals("FAILED")){break;}require(System.nanoTime()<deadline,"Task completion deadline exceeded");Thread.sleep(25);
        }while(true);
        require(state.equals(success?"SUCCESS":"FAILED"),"Unexpected terminal state");
        var result=call("GET","/api/tasks/"+id+"/result",null);require(result.statusCode()==(success?200:409),"Result query contract failed");
        if(success){require(result.body().contains("[MOCK] hello phase5"),"Result content mismatch");}
        return new Sample(id,state,(acknowledged-begin)/1e6,(System.nanoTime()-begin)/1e6);
    }
    static HttpResponse<String> call(String method,String path,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5));
        if(body==null){request.GET();}else{request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));}
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static String field(String body,String name){var match=java.util.regex.Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]*)\"").matcher(body);require(match.find(),"Missing API field");return match.group(1);}
    static String payload(String key,String input){return "{\"idempotencyKey\":\""+key+"\",\"input\":\""+input+"\"}";}
    static String stats(List<Sample> samples){double[] accepted=samples.stream().mapToDouble(Sample::acceptedMs).sorted().toArray(),completed=samples.stream().mapToDouble(Sample::completedMs).sorted().toArray();return String.format(Locale.ROOT,"{\"n\":%d,\"acceptedP50Ms\":%.3f,\"acceptedP95Ms\":%.3f,\"completedP50Ms\":%.3f,\"completedP95Ms\":%.3f}",samples.size(),percentile(accepted,.5),percentile(accepted,.95),percentile(completed,.5),percentile(completed,.95));}
    static double percentile(double[] values,double p){return values[(int)Math.ceil(values.length*p)-1];}
    static String csv(List<Sample> sequential,List<Sample> concurrent,Sample failed){var text=new StringBuilder("group,taskId,status,acceptedMs,completedMs\n");for(String group:List.of("sequential","concurrent","failure")){var samples=group.equals("sequential")?sequential:group.equals("concurrent")?concurrent:List.of(failed);for(var sample:samples){text.append(String.format(Locale.ROOT,"%s,%s,%s,%.3f,%.3f%n",group,sample.id(),sample.status(),sample.acceptedMs(),sample.completedMs()));}}return text.toString();}
    static String fingerprint(Connection db,boolean events)throws Exception {
        String query=events?"SELECT e.event_id,e.status,e.version,e.attempts,e.updated_at FROM ai_task_outbox e JOIN ai_text_task t ON e.task_id=t.task_id WHERE t.idempotency_key NOT LIKE ? ORDER BY e.event_id":"SELECT task_id,status,version,started_at,completed_at FROM ai_text_task WHERE idempotency_key NOT LIKE ? ORDER BY task_id";
        var digest=MessageDigest.getInstance("SHA-256");try(var statement=db.prepareStatement(query)){statement.setString(1,prefix+"%");try(var row=statement.executeQuery()){while(row.next()){for(int i=1;i<=5;i++){digest.update(Objects.toString(row.getObject(i),"NULL").getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}}}}
        return HexFormat.of().formatHex(digest.digest());
    }
    static long count(Connection db,String sql,String arg)throws SQLException{return Long.parseLong(scalar(db,sql,arg));}
    static String scalar(Connection db,String sql,String... args)throws SQLException{try(var statement=db.prepareStatement(sql)){for(int i=0;i<args.length;i++){statement.setString(i+1,args[i]);}try(var row=statement.executeQuery()){require(row.next(),"Missing database row");return row.getString(1);}}}
    static String java(){return Path.of(System.getProperty("java.home"),"bin","java.exe").toString();}
    static String required(String name){String value=System.getenv(name);require(value!=null&&!value.isBlank(),"Missing external credential");return value;}
    static void require(boolean value,String message){if(!value){throw new AcceptanceFailure(message);}}
}
