package ru.evolenta.pgs.worker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static ru.evolenta.pgs.worker.Util.*;

/** Local regression: a server may cap the requested page size. */
public final class SearchCompletenessTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        run("capped", false, 2);
        run("repeat", true, 0);
        run("empty-incomplete", true, 0);
        run("list", false, 2);
        System.out.println("PASS search completeness " + assertions + " assertions");
    }
    private static void run(String mode, boolean mustFail, int expected) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{try{
            String body=new String(readBounded(e.getRequestBody(),1024*1024),StandardCharsets.UTF_8);
            Map<String,Object> request=body.isEmpty()?obj():map(Json.parse(body));
            Object response;
            if(e.getRequestURI().getPath().contains("/search/")){
                int page=integer(request.get("page"));
                List<Object> records=page<2?Arrays.asList(obj("_id",mode.equals("repeat")?"same":"row-"+page)):Collections.emptyList();
                if(mode.equals("empty-incomplete"))records=Collections.emptyList();
                response=mode.equals("list")?records:obj("content",records,"number",page,"size",1,"totalElements",2,"last",page>=1);
            }else response=obj("_id","appeal","guid","appeal-guid","subservices",Arrays.asList(obj("guid","sub-guid","xsdData",obj(),"camundaProcessInfo",obj("id","process","camundaBusinessInfoId","info"))));
            byte[] bytes=Json.write(response).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
        }finally{e.close();}});server.start();
        try{
            Properties p=new Properties();p.setProperty("pgs.find","api/v1/find/{collection}?mainId={id}");p.setProperty("pgs.search","api/v1/search/{collection}");
            PgsApi api=new HttpPgsApi(new HttpJson("http://127.0.0.1:"+server.getAddress().getPort()+"/",null,2000,true),p);
            Map<String,Object> vars=obj();for(Map.Entry<String,Object>x:obj("mainId","appeal","collectionName","rbuModulesAppeals","camundaBusinessInfoId","info","rbuDomain","ADVERTISING","rbuProfile","ROIV","rbuOperation","PARSE_VALIDATE","rbuContractVersion","1").entrySet())vars.put(x.getKey(),obj("value",x.getValue()));
            Context c=new Context(new Command(obj("id","task","processInstanceId","process","activityId","step","variables",vars)),api);
            boolean failed=false;List<Map<String,Object>> found=Collections.emptyList();
            try{found=c.searchAll("marketingLicenses",Collections.emptyList());}catch(IllegalStateException expectedFailure){failed=true;}
            if(failed!=mustFail)throw new AssertionError(mode+": failure expected="+mustFail+", actual="+failed);
            assertions++;
            if(!mustFail){if(found.size()!=expected)throw new AssertionError(mode+": expected "+expected+", got "+found.size());assertions++;}
        }finally{server.stop(0);}
    }
}
