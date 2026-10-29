package ru.evolenta.server.worker;
import java.io.*;import java.nio.file.*;import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import ru.evolenta.server.service.FileStorageService;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.integration.RepositoryTaskAdapter;
@Configuration
@ConditionalOnProperty(name="worker.enabled",havingValue="true")
public class AdvertisingWorkerConfiguration {
 @Bean public Properties rbuWorkerProperties(@Value("${worker.config-file}") String file)throws Exception{Properties p=new Properties();try(Reader in=Files.newBufferedReader(Paths.get(file),java.nio.charset.StandardCharsets.UTF_8)){p.load(in);}if(!Boolean.parseBoolean(p.getProperty("worker.integrationVerified","false")))throw new IllegalStateException("Verify API configuration before enabling worker");return p;}
 @Bean public StandardAllowlist rbuStandardAllowlist(@org.springframework.beans.factory.annotation.Qualifier("rbuWorkerProperties") Properties properties){return new StandardAllowlist(properties);}
 @Bean(destroyMethod="close") @ConditionalOnMissingBean(CheckpointStore.class)
 public Journal advertisingLocalJournal(@org.springframework.beans.factory.annotation.Qualifier("rbuWorkerProperties") Properties rbuWorkerProperties)throws Exception{Properties p=rbuWorkerProperties;if(!"local-single".equals(p.getProperty("worker.journalMode")))throw new IllegalStateException("Provide approved shared CheckpointStore bean; local-single is for one-instance testing only");return new Journal(Paths.get(p.getProperty("worker.journalDirectory","worker-state")));}
 @Bean @ConditionalOnMissingBean(PgsApi.class)
 public PgsApi advertisingPgsApi(@org.springframework.beans.factory.annotation.Qualifier("rbuWorkerProperties") Properties rbuWorkerProperties,ObjectProvider<FileStorageService> nativeStorage){FileStorageService storage=nativeStorage.getIfAvailable();if(storage==null)throw new IllegalStateException("Native FileStorageService bean is required before enabling the RBU worker");Properties p=rbuWorkerProperties;int timeout=Integer.parseInt(p.getProperty("worker.timeoutMillis","30000"));boolean allow=Boolean.parseBoolean(p.getProperty("worker.allowHttp","false"));return new SdkFilePgsApi(new HttpPgsApi(http(p,"pgs",p.getProperty("pgs.baseUrl"),"PGS_TOKEN",timeout,allow),p),storage);}
 private HttpJson http(Properties p,String prefix,String base,String env,int timeout,boolean allow){return new HttpJson(base,System.getenv(env),timeout,allow,p.getProperty(prefix+".tokenFile",""),p.getProperty(prefix+".cookieFile",""),Boolean.parseBoolean(p.getProperty(prefix+".sendTokenHeader","false")));}
 @Bean public RepositoryTaskAdapter advertisingTaskAdapter(PgsApi api,CheckpointStore store,StandardAllowlist standards,@org.springframework.beans.factory.annotation.Qualifier("rbuWorkerProperties") Properties rbuWorkerProperties){Properties p=rbuWorkerProperties;return new RepositoryTaskAdapter(api,store,Boolean.parseBoolean(p.getProperty("worker.writesEnabled","false")),p.getProperty("worker.uiBaseUrl",""),standards);}
}
