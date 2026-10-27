import java.sql.*;
import java.util.Properties;

/** Read-only deployment gate. Never starts the app, writes rows, performs DDL or prints credentials/input. */
class DatabasePreflight {
    public static void main(String[] args) {
        try {
            String host=required("MYSQL_IT_HOST"), port=required("MYSQL_IT_PORT");
            if(!host.matches("[A-Za-z0-9.-]+") || Integer.parseInt(port)<1 || Integer.parseInt(port)>65535
                    || !"ai_task_app".equals(required("MYSQL_IT_USERNAME"))) { throw new IllegalArgumentException(); }
            Properties credentials=new Properties();
            credentials.setProperty("user","ai_task_app"); credentials.setProperty("password",required("MYSQL_IT_PASSWORD"));
            String url="jdbc:mysql://"+host+":"+port+"/ai_task_platform?connectionTimeZone=UTC"
                    +"&forceConnectionTimeZoneToSession=true&allowMultiQueries=false&connectTimeout=5000&socketTimeout=10000";
            try(Connection db=DriverManager.getConnection(url,credentials)) {
                credentials.clear(); db.setReadOnly(true);
                if(!"ai_task_platform".equals(text(db,"SELECT DATABASE()"))
                        || !text(db,"SELECT CURRENT_USER()").startsWith("ai_task_app@")) { throw new IllegalStateException(); }
                String version=text(db,"SELECT VERSION()");
                if(!version.matches("8\\.0\\.\\d+.*")) {throw new IllegalStateException();}
                if(number(db,"SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='ai_task_platform'"
                        +" AND TABLE_NAME='ai_text_task' AND CONSTRAINT_NAME IN ('ck_task_lifecycle','ck_task_execution_ownership') AND ENFORCED='YES'")!=2) {
                    throw new IllegalStateException();
                }
                try(Statement statement=db.createStatement(); ResultSet grants=statement.executeQuery("SHOW GRANTS")) {
                    boolean business=false;
                    while(grants.next()) {
                        String grant=grants.getString(1);
                        if(grant.startsWith("GRANT USAGE ON *.* TO ")&&!grant.contains("WITH GRANT OPTION")){continue;}
                        if(!grant.startsWith("GRANT SELECT, INSERT, UPDATE ON `ai_task_platform`.* TO ")||grant.contains("WITH GRANT OPTION")) {
                            throw new IllegalStateException();
                        }
                        business=true;
                    }
                    if(!business){throw new IllegalStateException();}
                }
                // Also requires existing 002/003 columns; no schema initialization or migration fallback.
                try(Statement statement=db.createStatement()) {
                    statement.executeQuery("SELECT execution_token,execution_deadline FROM ai_text_task WHERE 1=0").close();
                    statement.executeQuery("SELECT event_id,claim_token,locked_until FROM ai_task_outbox WHERE 1=0").close();
                }
                long pending=number(db,"SELECT COUNT(*) FROM ai_text_task WHERE status='PENDING'");
                long legacy=number(db,"SELECT COUNT(*) FROM ai_text_task WHERE status='RUNNING' AND execution_token IS NULL AND execution_deadline IS NULL");
                long owned=number(db,"SELECT COUNT(*) FROM ai_text_task WHERE status='RUNNING' AND execution_token IS NOT NULL");
                long events=number(db,"SELECT COUNT(*) FROM ai_task_outbox WHERE status IN ('PENDING','RETRY','DISPATCHING')");
                long terminalEvents=number(db,"SELECT COUNT(*) FROM ai_task_outbox e JOIN ai_text_task t ON t.task_id=e.task_id"
                        +" WHERE e.status IN ('PENDING','RETRY','DISPATCHING') AND t.status IN ('SUCCESS','FAILED')");
                boolean safe=events==0&&owned==0;
                System.out.printf("{\"readOnly\":true,\"mysql\":\"%s\",\"schema002003\":true,\"minimalPermissions\":true,"
                        +"\"oldPending\":%d,\"legacyRunning\":%d,\"ownedRunning\":%d,\"undispatchedOutbox\":%d,"
                        +"\"undispatchedTerminalTasks\":%d,\"globalScanSafe\":%s}%n", version,pending,legacy,owned,events,terminalEvents,safe);
            } finally {credentials.clear();}
        } catch(Exception failure) {
            String category=failure instanceof SQLException sql ? "SQL_FAILURE vendorCode="+sql.getErrorCode() : failure.getClass().getSimpleName();
            System.err.println("READ_ONLY_PREFLIGHT_FAILED "+category+"; original diagnostic redacted; no write/fallback");
            System.exit(2);
        }
    }
    private static long number(Connection db,String sql)throws SQLException {
        try(Statement statement=db.createStatement();ResultSet row=statement.executeQuery(sql)){row.next();return row.getLong(1);}
    }
    private static String text(Connection db,String sql)throws SQLException {
        try(Statement statement=db.createStatement();ResultSet row=statement.executeQuery(sql)){row.next();return row.getString(1);}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank()){throw new IllegalArgumentException();}return value;}
}
