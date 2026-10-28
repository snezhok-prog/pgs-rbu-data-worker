package ru.evolenta.pgs.worker;
import java.util.*;
public interface PgsApi {
    Map<String,Object> find(String collection,String id)throws Exception;
    List<Map<String,Object>> search(String collection,List<Object> filters,int page,int size)throws Exception;
    void update(String collection,String id,String guid,Map<String,Object> patch)throws Exception;
    void updateSub(String collection,String id,String guid,String parentEntries,Map<String,Object> patch)throws Exception;
    Map<String,Object> create(String collection,Map<String,Object> value)throws Exception;
    void delete(String collection,String id,String guid)throws Exception;
    byte[] download(String fileId,long maxBytes)throws Exception;
}
