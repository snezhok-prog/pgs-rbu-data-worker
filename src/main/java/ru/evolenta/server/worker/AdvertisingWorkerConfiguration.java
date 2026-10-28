package ru.evolenta.server.worker;
import java.io.*;import java.nio.file.*;import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.integration.RepositoryTaskAdapter;
@Configuration
@ConditionalOnProperty(name="worker.enabled",havingValue="true")
public class AdvertisingWorkerConfiguration {
 @Bean public Properties advertisingWorkerProperties(@Value("${worker.config-file}") String file)throws Exception{Properties p=new Properties();try(Reader in=Files.newBufferedReader(Paths.get(file),java.nio.charset.StandardCharsets.UTF_8)){p.load(in);}if(!Boolean.parseBoolean(p.getProperty("worker.integrationVerified","false")))throw new IllegalStateException("Verify API configuration before enabling worker");return p;}
 @Bean(destroyMethod="close") @ConditionalOnMissingBean(CheckpointStore.class)
 public Journal advertisingLocalJournal(@org.springframework.beans.factory.annotation.Qualifier("advertisingWorkerProperties") Properties advertisingWorkerProperties)throws Exception{Properties p=advertisingWorkerProperties;if(!"local-single".equals(p.getProperty("worker.journalMode")))throw new IllegalStateException("Provide approved shared CheckpointStore bean; local-single is for one-instance testing only");return new Journal(Paths.get(p.getProperty("worker.journalDirectory","worker-state")));}
 @Bean @ConditionalOnMissingBean(PgsApi.class)
 public PgsApi advertisingPgsApi(@org.springframework.beans.factory.annotation.Qualifier("advertisingWorkerProperties") Properties advertisingWorkerProperties){Properties p=advertisingWorkerProperties;int timeout=Integer.parseInt(p.getProperty("worker.timeoutMillis","30000"));boolean allow=Boolean.parseBoolean(p.getProperty("worker.allowHttp","false"));return new HttpPgsApi(http(p,"pgs",p.getProperty("pgs.baseUrl"),"PGS_TOKEN",timeout,allow),http(p,"file",p.getProperty("file.baseUrl",p.getProperty("pgs.baseUrl")),"FILE_TOKEN",timeout,allow),p);}
 private HttpJson http(Properties p,String prefix,String base,String env,int timeout,boolean allow){return new HttpJson(base,System.getenv(env),timeout,allow,p.getProperty(prefix+".tokenFile",""),p.getProperty(prefix+".cookieFile",""),Boolean.parseBoolean(p.getProperty(prefix+".sendTokenHeader","false")));}
 @Bean public RepositoryTaskAdapter advertisingTaskAdapter(PgsApi api,CheckpointStore store,@org.springframework.beans.factory.annotation.Qualifier("advertisingWorkerProperties") Properties advertisingWorkerProperties){Properties p=advertisingWorkerProperties;return new RepositoryTaskAdapter(api,store,Boolean.parseBoolean(p.getProperty("worker.writesEnabled","false")),p.getProperty("worker.uiBaseUrl",""));}
}
