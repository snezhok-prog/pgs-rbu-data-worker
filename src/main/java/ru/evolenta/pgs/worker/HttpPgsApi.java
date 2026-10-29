package ru.evolenta.pgs.worker;
import java.util.*;import static ru.evolenta.pgs.worker.Util.*;
/** HTTP adapter: paths are isolated and configurable, not scattered in business rules. */
public final class HttpPgsApi implements PgsApi {
    private final HttpJson http,files;private final Properties p;
    private static final Set<String> ALLOWED=new HashSet<String>(Arrays.asList("uploadedFiles","rbuModulesAppeals","rbuModulesCoordination","marketingAppeals","organizations","rbuRegionPassportsMarketing","rbuRegionMunicipalitiesMarketing","rbuRegionMunicipalAdvert","placeReestrRKCollection","nsiUnifiedPGS_object_marketing","biddingReestrRKCollection","contractReestrRKCollection","obligationReestrRKCollection","marketingLicenses","marketingRegistry","socialAdReestrRKCollection","unauthorizedReestrRKCollection","dismantleReestrRKCollection",
        "rbuRegionPassports","rbuRegionMunicipalities","rbuRegionOrganizations",
        "NTOmesto","nsiLocalObjectNTO","reestrbiddingReestr","reestrpermitsReestr","reestrmarketReestr","nsiLocalObjectMarket","FairMesto","FairPermits","Fair","nsiLocalObjectFair",
        "metaReglamentNtoAppeals","reglamentNtoAppeals","reglamentMarketAppeals","nsiUnifiedStatusProjectPlace","nsiUnifiedspecialMarket",
        "rbuPetRegionPassports","rbuPetRegionMunicipalities","rbuPetRegionOrganizations","myPetAppeals",
        "animalCatchOrderRegistryCollection","animalsRecordsCollectionTwo","animalCatchActRegistryCollection","myPetAnimalCardReestr","animalReleaseActRegistryEntry","animalTransferActRegistryCollection"));
    private static final Set<String> READ_ONLY=Set.of("uploadedFiles","rbuRegionPassports","rbuRegionMunicipalities","rbuRegionOrganizations","metaReglamentNtoAppeals","reglamentNtoAppeals","reglamentMarketAppeals","nsiUnifiedStatusProjectPlace","nsiUnifiedspecialMarket","rbuPetRegionPassports","rbuPetRegionMunicipalities","rbuPetRegionOrganizations","myPetAppeals");
    public HttpPgsApi(HttpJson http,Properties p){this(http,http,p);}
    public HttpPgsApi(HttpJson http,HttpJson files,Properties p){this.http=http;this.files=files;this.p=p;}
    private String route(String name,String collection,Map<String,Object> values){if(!collection.isEmpty()&&!ALLOWED.contains(collection))throw new IllegalArgumentException("Collection not allowed: "+collection);if(READ_ONLY.contains(collection)&&!name.equals("find")&&!name.equals("search"))throw new IllegalArgumentException("Collection is read-only: "+collection);String r=p.getProperty("pgs."+name);if(r==null)throw new IllegalArgumentException("Missing pgs."+name);r=r.replace("{collection}",collection);for(Map.Entry<String,Object> e:values.entrySet())r=r.replace("{"+e.getKey()+"}",HttpJson.enc(clean(e.getValue())));if(r.contains("{"))throw new IllegalArgumentException("Unbound API route");return r;}
    public Map<String,Object> find(String c,String id)throws Exception{try{return map(http.call("GET",route("find",c,obj("id",id)),null));}catch(HttpJson.HttpError e){if(e.status==404)return new LinkedHashMap<String,Object>();throw e;}}
    public List<Map<String,Object>> search(String c,List<Object> f,int page,int size)throws Exception{
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        Object result=http.call("POST",route("search",c,obj()),obj("search",obj("search",f),"page",page,"size",size,"sort","_id,ASC"));
        if(result instanceof List)return maps(result);
        Map<String,Object> envelope=map(result);
        for(String k:Arrays.asList("content","results","items","data"))if(envelope.get(k) instanceof List){
            List<Map<String,Object>> records=maps(envelope.get(k));
            if(envelope.containsKey("number")&&integer(envelope.get("number"))!=page)throw new IllegalStateException("Search returned the wrong page");
            int actualSize=envelope.containsKey("size")?integer(envelope.get("size")):size;
            if(actualSize<1)throw new IllegalStateException("Invalid server page size");
            if(records.isEmpty()&&(Boolean.FALSE.equals(envelope.get("last"))||envelope.get("totalElements") instanceof Number&&((Number)envelope.get("totalElements")).longValue()>(long)page*actualSize))throw new IllegalStateException("Empty search page contradicts pagination metadata");
            return records;
        }
        throw new IllegalStateException("Unknown search response shape");
    }
    public void update(String c,String id,String guid,Map<String,Object> patch)throws Exception{http.call("PUT",route("update",c,obj("id",id,"guid",guid)),patch);}
    public void updateSub(String c,String id,String guid,String parent,Map<String,Object> patch)throws Exception{http.call("PUT",route("updateSub",c,obj("id",id,"guid",guid,"parentEntries",parent)),patch);}
    public Map<String,Object> create(String c,Map<String,Object> v)throws Exception{return map(http.call("POST",route("create",c,obj()),v));}
    public void delete(String c,String id,String guid)throws Exception{try{http.call("DELETE",route("delete",c,obj("id",id,"guid",guid)),null);}catch(HttpJson.HttpError e){if(e.status!=404)throw e;}}
    public byte[] download(String id,long max)throws Exception{return files.bytes("GET",route("download","",obj("id",id)),null,max);}
}
