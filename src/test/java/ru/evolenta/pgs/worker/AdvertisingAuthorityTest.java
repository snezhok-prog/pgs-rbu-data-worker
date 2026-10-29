package ru.evolenta.pgs.worker;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class AdvertisingAuthorityTest {
    private AdvertisingAcceptanceFaultTest.Fake fixture() throws Exception {
        try (InputStream stream=getClass().getResourceAsStream("/acceptance.json")) {
            return new AdvertisingAcceptanceFaultTest.Fake(map(Json.parse(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))));
        }
    }
    private Context context(PgsApi api,AdvertisingAcceptanceFaultTest.Fake fake) throws Exception {
        return new Context(AdvertisingAcceptanceFaultTest.command(fake,false,Command.Operation.PARSE_VALIDATE),api);
    }
    @Test void rejectsCoordinatorOutsideAuthoritativeRegion() throws Exception {
        var fake=fixture();fake.organization.put("regions",obj("code","77"));
        assertThrows(IllegalArgumentException.class,()->new AdvertisingGuard(context(fake,fake),false));
        assertEquals(0,fake.created);
    }
    @Test void acceptsCurrentOwnerAndChecksEveryReferenceLifeState() throws Exception {
        var fake=fixture();assertDoesNotThrow(()->new AdvertisingGuard(context(fake,fake),true));
        for (String collection:List.of("organizations","rbuRegionMunicipalAdvert","rbuRegionPassportsMarketing","rbuRegionMunicipalitiesMarketing")) {
            PgsApi decorated=new PgsApi(){
                public Map<String,Object> find(String c,String id)throws Exception {var value=fake.find(c,id);if(c.equals(collection))value.put("isDeleted",true);return value;}
                public List<Map<String,Object>> search(String c,List<Object> filters,int page,int size)throws Exception {var rows=fake.search(c,filters,page,size);if(c.equals(collection))for(var row:rows)row.put("isDeleted",true);return rows;}
                public void update(String c,String id,String guid,Map<String,Object> value){fail("guard must not write");}
                public void updateSub(String c,String id,String guid,String parent,Map<String,Object> value){fail("guard must not write");}
                public Map<String,Object> create(String c,Map<String,Object> value){throw new AssertionError("guard must not write");}
                public void delete(String c,String id,String guid){fail("guard must not write");}
                public byte[] download(String id,long max){throw new AssertionError("guard must not download");}
            };
            assertThrows(IllegalArgumentException.class,()->new AdvertisingGuard(context(decorated,fake),false),collection);
        }
    }
}
