package ru.evolenta.pgs.worker;

import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static ru.evolenta.pgs.worker.Util.*;

/** Single-slot external-task worker, transport compatible with the repository's DTO shapes. */
public final class Main {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -jar pgs-data-worker.jar /path/worker.properties");Properties p=new Properties();try(InputStream in=Files.newInputStream(Paths.get(args[0]))){p.load(new InputStreamReader(in,"UTF-8"));}
        if(!Boolean.parseBoolean(p.getProperty("worker.integrationVerified","false")))throw new IllegalStateException("Сначала подтвердите API-контракт и настройте worker.integrationVerified=true");
        if(!"local-single".equals(p.getProperty("worker.journalMode")))throw new IllegalStateException("Standalone Main requires local-single test mode; production uses example-service and shared storage");
        String instance=p.getProperty("worker.instanceId","");if(instance.isEmpty()||instance.contains("CHANGE"))throw new IllegalArgumentException("Unique worker.instanceId required");
        boolean allowHttp=Boolean.parseBoolean(p.getProperty("worker.allowHttp","false"));int timeout=Integer.parseInt(p.getProperty("worker.timeoutMillis","30000")),lockMillis=Integer.parseInt(p.getProperty("worker.lockMillis","300000"));if(lockMillis<timeout*3)throw new IllegalArgumentException("Lock must exceed 3 HTTP timeouts");
        HttpJson camunda=transport(p,"camunda",p.getProperty("camunda.baseUrl"),"CAMUNDA_TOKEN",timeout,allowHttp);PgsApi api=new HttpPgsApi(transport(p,"pgs",p.getProperty("pgs.baseUrl"),"PGS_TOKEN",timeout,allowHttp),transport(p,"file",p.getProperty("file.baseUrl",p.getProperty("pgs.baseUrl")),"FILE_TOKEN",timeout,allowHttp),p);
        String topic=p.getProperty("worker.topic","pgs-data-processing-v1");boolean extend=Boolean.parseBoolean(p.getProperty("worker.extendLockSupported","false")),failure=Boolean.parseBoolean(p.getProperty("worker.failureSupported","false"));
        StandardAllowlist standardCodes=new StandardAllowlist(p);
        try(Journal journal=new Journal(Paths.get(p.getProperty("worker.journalDirectory","./worker-state")))){
            System.out.println(now()+" worker ready instance="+instance+" topic="+topic+" writesEnabled="+Boolean.parseBoolean(p.getProperty("worker.writesEnabled","false")));
            while(!Thread.currentThread().isInterrupted()){
                try{
                    Object fetched=camunda.call("POST","external-task/fetchAndLock",obj("workerId",instance,"maxTasks",1,"topics",Arrays.asList(obj("topicName",topic,"lockDuration",lockMillis,"variables",Arrays.asList("mainId","collectionName","camundaBusinessInfoId","rbuDomain","rbuProfile","rbuOperation","rbuContractVersion"),"deserializeValues",false))));
                    for(Map<String,Object> task:maps(fetched))process(task,api,camunda,journal,instance,lockMillis,extend,failure,standardCodes,Boolean.parseBoolean(p.getProperty("worker.writesEnabled","false")),p.getProperty("worker.uiBaseUrl",""),timeout);
                }catch(Exception e){System.err.println(now()+" polling error "+e.getClass().getSimpleName()+": "+safe(e));}
                Thread.sleep(Long.parseLong(p.getProperty("worker.pollMillis","3000")));
            }
        }
    }
    private static HttpJson transport(Properties p,String prefix,String base,String env,int timeout,boolean allowHttp){String token=System.getenv(env);if(prefix.equals("file")&&token==null)token=System.getenv("PGS_TOKEN");return new HttpJson(base,token,timeout,allowHttp,p.getProperty(prefix+".tokenFile",""),p.getProperty(prefix+".cookieFile",""),Boolean.parseBoolean(p.getProperty(prefix+".sendTokenHeader","false")));}
    private static String safe(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.substring(0,Math.min(s.length(),1000)).replaceAll("[\r\n]"," ");}
    private static Map<String,Object> typed(Map<String,Object> raw){Map<String,Object>v=obj();for(Map.Entry<String,Object>e:raw.entrySet()){Object x=e.getValue();String type=x instanceof Boolean?"Boolean":x instanceof Integer?"Integer":x instanceof Number?"Double":"String";if(x instanceof Map||x instanceof List)throw new IllegalArgumentException("Do not put bulk objects into process variables");v.put(e.getKey(),obj("type",type,"value",x));}return v;}
    private static void process(Map<String,Object>task,PgsApi api,HttpJson camunda,Journal journal,String worker,int lockMillis,boolean extend,boolean failure,StandardAllowlist allowed,boolean writes,String ui,int timeout)throws Exception{
        long startedNanos=System.nanoTime();
        AtomicBoolean lost=new AtomicBoolean(false);AtomicLong expires=new AtomicLong(System.currentTimeMillis()+lockMillis);String taskId=clean(task.get("id"));String failureKey=hash(obj("taskId",taskId));if(bool(journal.read(failureKey).get("manualReviewRequired"))){System.err.println(now()+" task="+taskId+" awaits operator review");return;}try{String date=clean(task.get("lockExpirationTime"));if(!date.isEmpty()){date=date.replaceFirst("([+-]\\d{2})(\\d{2})$","$1:$2");expires.set(java.time.OffsetDateTime.parse(date).toInstant().toEpochMilli());}}catch(Exception ignored){}
        // No network in scripts: lease traffic belongs to this Java process.
        ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();if(extend)heartbeat.scheduleWithFixedDelay(()->{try{camunda.call("POST","external-task/"+HttpJson.enc(taskId)+"/extendLock",obj("workerId",worker,"newDuration",lockMillis));expires.set(System.currentTimeMillis()+lockMillis);}catch(Exception e){lost.set(true);}},lockMillis/3,lockMillis/3,TimeUnit.MILLISECONDS);
        Operations.Lease lease=()->{if(lost.get()||System.currentTimeMillis()>expires.get()-timeout-1000)throw new IllegalStateException("Блокировка external task потеряна или истекает");};
        try{
            Command command=new Command(task);System.out.println(now()+" received task="+taskId+" process="+command.processId+" domain="+command.domain+" profile="+command.profile+" operation="+command.operation);Context context=new Context(command,api);context.setLease(lease);allowed.require(context);lease.check();Map<String,Object> vars=new Operations(journal,lease,writes,ui).execute(context);lease.check();camunda.call("POST","external-task/"+HttpJson.enc(taskId)+"/complete",obj("workerId",worker,"variables",typed(vars)));System.out.println(now()+" completed task="+taskId+" process="+command.processId+" domain="+command.domain+" profile="+command.profile+" operation="+command.operation+" durationMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedNanos));
        }catch(Exception e){System.err.println(now()+" failed task="+taskId+" "+e.getClass().getSimpleName()+": "+safe(e));String key=failureKey;int attempts=integer(journal.read(key).get("attempts"))+1;journal.save(key,obj("status","FAILED","attempts",attempts,"manualReviewRequired",e instanceof IllegalArgumentException||attempts>=3,"taskId",taskId,"errorType",e.getClass().getSimpleName(),"message",safe(e),"failedAt",now()));
            if(failure&&!lost.get()){int retries=task.get("retries")==null?3:Math.max(0,integer(task.get("retries"))-1);if(e instanceof Operations.UnsupportedOperation||e instanceof IllegalArgumentException)retries=0;camunda.call("POST","external-task/"+HttpJson.enc(taskId)+"/failure",obj("workerId",worker,"errorMessage",safe(e),"retries",retries,"retryTimeout",30000));}
        }finally{heartbeat.shutdownNow();heartbeat.awaitTermination(5,TimeUnit.SECONDS);}
    }
}
