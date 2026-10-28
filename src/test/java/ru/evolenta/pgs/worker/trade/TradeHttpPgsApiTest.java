package ru.evolenta.pgs.worker.trade;

import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.*;

/** Exercises the real HTTP adapter over loopback; no live platform or credentials. */
class TradeHttpPgsApiTest {
    private static final List<String> TARGETS=List.of("NTOmesto","nsiLocalObjectNTO","reestrbiddingReestr","reestrpermitsReestr","reestrmarketReestr","nsiLocalObjectMarket","FairMesto","FairPermits","Fair","nsiLocalObjectFair");
    private static final List<String> READ_ONLY=List.of("rbuRegionPassports","rbuRegionMunicipalities","rbuRegionOrganizations","metaReglamentNtoAppeals","reglamentNtoAppeals","reglamentMarketAppeals","nsiUnifiedStatusProjectPlace","nsiUnifiedspecialMarket");
    private HttpServer server;
    private HttpPgsApi api;
    private Properties routes;
    private final List<Map<String,Object>> requests=Collections.synchronizedList(new ArrayList<>());
    private final Map<String,Map<String,Map<String,Object>>> records=new LinkedHashMap<>();
    private final AtomicReference<Throwable> handlerFailure=new AtomicReference<>();

    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{
                String[] path=exchange.getRequestURI().getPath().split("/");
                String operation=path[1],collection=path[2],method=exchange.getRequestMethod();
                Map<String,String> query=new LinkedHashMap<>();String raw=exchange.getRequestURI().getRawQuery();
                if(raw!=null)for(String pair:raw.split("&")){String[] parts=pair.split("=",2);query.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}
                byte[] input=readBounded(exchange.getRequestBody(),1024*1024);Object body=input.length==0?null:Json.parse(new String(input,StandardCharsets.UTF_8));
                requests.add(obj("operation",operation,"collection",collection,"method",method,"query",query,"body",body));
                if(Boolean.TRUE.equals(map(body).get("forceError"))){byte[] output="{\"error\":\"private-error-detail\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,output.length);exchange.getResponseBody().write(output);return;}
                Map<String,Map<String,Object>> data=records.computeIfAbsent(collection,k->new LinkedHashMap<>());Object response;int status=200;
                switch(operation){
                    case "find" -> {response=data.get(query.get("mainId"));if(response==null){status=404;response=obj("error","missing");}}
                    case "search" -> response=obj("content",new ArrayList<>(data.values()),"number",map(body).get("page"),"size",map(body).get("size"),"last",true);
                    case "create" -> {response=copy(map(body));map(response).put("_id","created-record");data.put("created-record",map(response));status=201;}
                    case "update" -> {response=data.get(query.get("mainId"));if(response==null)throw new IllegalStateException("Missing update target");map(response).putAll(copy(map(body)));}
                    case "delete" -> {Map<String,Object> removed=data.remove(query.get("mainId"));if(removed!=null&&!clean(removed.get("guid")).equals(query.get("guid")))throw new IllegalStateException("Wrong DELETE guid");response=null;status=204;}
                    default -> throw new IllegalArgumentException("Unexpected route: "+operation);
                }
                byte[] output=response==null?new byte[0]:Json.write(response).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,status==204?-1:output.length);if(status!=204)exchange.getResponseBody().write(output);
            }catch(Throwable failure){handlerFailure.compareAndSet(null,failure);byte[] output="test handler failure".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,output.length);exchange.getResponseBody().write(output);}
            finally{exchange.close();}
        });
        server.start();routes=new Properties();routes.setProperty("pgs.find","find/{collection}?mainId={id}");routes.setProperty("pgs.search","search/{collection}");routes.setProperty("pgs.create","create/{collection}");routes.setProperty("pgs.update","update/{collection}?mainId={id}&guid={guid}");routes.setProperty("pgs.updateSub","update/{collection}/subservices?mainId={id}&guid={guid}&parentEntries={parentEntries}");routes.setProperty("pgs.delete","delete/{collection}?mainId={id}&guid={guid}");
        api=new HttpPgsApi(new HttpJson("http://127.0.0.1:"+server.getAddress().getPort()+"/",null,2000,true),routes);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);assertNull(handlerFailure.get(),()->"HTTP test handler failed: "+handlerFailure.get());}

    @Test void readsEveryTradeCollectionIncludingScopeAndReferenceSources()throws Exception{
        Set<String> collections=new LinkedHashSet<>(TARGETS);collections.addAll(READ_ONLY);collections.add("organizations");collections.add("rbuModulesAppeals");
        for(String collection:collections){records.put(collection,new LinkedHashMap<>(Map.of("record",obj("_id","record","guid","record-guid"))));assertEquals("record",api.find(collection,"record").get("_id"),collection);assertEquals(1,api.search(collection,List.of(Context.filter("guid","record-guid")),0,100).size(),collection);}
        assertEquals(collections.size()*2,requests.size());Map<String,Object> search=requests.stream().filter(r->r.get("operation").equals("search")).findFirst().orElseThrow();assertEquals("guid",at(search,"body.search.search.0.field"));assertEquals("record-guid",at(search,"body.search.search.0.value"));assertEquals("_id,ASC",at(search,"body.sort"));
    }
    @Test void writesAndReadsBackAllTenTradeTargets()throws Exception{
        assertEquals(TradeModule.COLLECTIONS,new HashSet<>(TARGETS));
        for(String collection:TARGETS){Map<String,Object> created=api.create(collection,obj("guid","record-guid","value","initial"));assertEquals("created-record",id(created),collection);assertEquals("initial",api.find(collection,id(created)).get("value"),collection);api.update(collection,id(created),"record-guid",obj("value","updated"));assertEquals("updated",api.find(collection,id(created)).get("value"),collection);api.delete(collection,id(created),"record-guid");assertTrue(api.find(collection,id(created)).isEmpty(),collection);}
        assertEquals(TARGETS.size()*6,requests.size());
    }
    @Test void refusesMutationOfReadonlyTradeContextAndReferenceCollections(){
        for(String collection:READ_ONLY){assertThrows(IllegalArgumentException.class,()->api.create(collection,obj("guid","new")),collection);assertThrows(IllegalArgumentException.class,()->api.update(collection,"record","record-guid",obj()),collection);assertThrows(IllegalArgumentException.class,()->api.updateSub(collection,"record","sub","subservices",obj()),collection);assertThrows(IllegalArgumentException.class,()->api.delete(collection,"record","record-guid"),collection);}
        assertTrue(requests.isEmpty());
    }
    @Test void refusesUnknownCollectionsForEveryOperationBeforeNetwork(){
        for(String collection:List.of("unrelatedSecrets","NTOmesto/other","../organizations","rbuRegionOrganizations.notAllowed")){assertThrows(IllegalArgumentException.class,()->api.find(collection,"record"));assertThrows(IllegalArgumentException.class,()->api.search(collection,List.of(),0,100));assertThrows(IllegalArgumentException.class,()->api.create(collection,obj()));assertThrows(IllegalArgumentException.class,()->api.update(collection,"record","guid",obj()));assertThrows(IllegalArgumentException.class,()->api.updateSub(collection,"record","sub","subservices",obj()));assertThrows(IllegalArgumentException.class,()->api.delete(collection,"record","guid"));}
        assertTrue(requests.isEmpty());
    }
    @Test void errorReportsMethodAndRouteWithoutQueryRequestBodyOrResponseBody(){
        routes.setProperty("pgs.create","create/{collection}?access_token=dummy-query-secret");
        HttpJson.HttpError error=assertThrows(HttpJson.HttpError.class,()->api.create("nsiLocalObjectNTO",obj("forceError",true,"privateValue","dummy-body-secret")));
        assertEquals(500,error.status);assertEquals("HTTP 500 POST create/nsiLocalObjectNTO",error.getMessage());assertFalse(error.getMessage().contains("dummy-query-secret"));assertFalse(error.getMessage().contains("dummy-body-secret"));assertFalse(error.getMessage().contains("private-error-detail"));assertEquals(1,requests.size());
    }
}
