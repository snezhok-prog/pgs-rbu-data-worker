package ru.evolenta.pgs.worker;

import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvertisingCrossLinkLifecycleTest {
    @TempDir Path directory;

    @Test void tenPlaceRowsShareOneFiftyWriteBudgetAcrossCreatesAndCrossLinks() throws Exception {
        var api=api(10);try(var journal=new Journal(directory)){
            validated(api,journal);var operations=new ImportOperations(journal,()->{},true,"https://test.example");
            int before=api.physicalWrites();var first=operations.apply(context(api));
            assertTrue(api.physicalWrites()-before<=50,"First delivery performed "+(api.physicalWrites()-before)+" physical writes");
            assertTrue(bool(first.get("rbuApplyHasMoreBatches")));assertEquals(50,api.delegate.created);assertEquals(0,api.patches);
            before=api.physicalWrites();var last=operations.apply(context(api));
            assertFalse(bool(last.get("rbuApplyHasMoreBatches")));assertTrue(api.physicalWrites()-before<=50);
            assertEquals(50,api.delegate.created);assertEquals(40,api.patches);assertEquals(90,api.physicalWrites());
            operations.finalizeImport(context(api));before=api.physicalWrites();operations.apply(context(api));assertEquals(before,api.physicalWrites());
            assertEquals(50,list(source(api).get("migrationCreatedRecords")).size());
        }
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4})
    void committedPutResponseLossResumesWithoutDuplicatingOrRepeatingThePut(int lostPut) throws Exception {
        var api=api(1);try(var journal=new Journal(directory)){
            validated(api,journal);api.losePut=true;api.losePutAt=lostPut;
            assertThrows(IOException.class,()->new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)));
            assertEquals(5,api.delegate.created);assertEquals(lostPut,api.patches);
        }
        try(var journal=new Journal(directory)){
            var operations=new ImportOperations(journal,()->{},true,"https://test.example");
            assertFalse(bool(operations.apply(context(api)).get("rbuApplyHasMoreBatches")));
            assertEquals(5,api.delegate.created);assertEquals(4,api.patches);
            for(var receipt:maps(source(api).get("migrationCreatedRecords"))){var record=api.find(clean(receipt.get("collection")),id(receipt));assertEquals(ImportOperations.businessHash(record),receipt.get("businessHash"));}
            int writes=api.physicalWrites();operations.apply(context(api));assertEquals(writes,api.physicalWrites());
        }
    }

    @Test void retriesAnUncommittedPutOnlyWhenItsSavedBeforeHashStillMatches() throws Exception {
        var api=api(1);try(var journal=new Journal(directory)){
            validated(api,journal);api.failBeforePut=true;
            assertThrows(IOException.class,()->new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)));
            assertEquals(0,api.patches);
        }
        try(var journal=new Journal(directory)){
            assertFalse(bool(new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)).get("rbuApplyHasMoreBatches")));
            assertEquals(5,api.delegate.created);assertEquals(4,api.patches);
        }
    }

    @Test void persistsBeforeAndAfterHashesBeforeSendingTheFirstPut() throws Exception {
        var api=api(1);try(var journal=new Journal(directory)){
            validated(api,journal);api.losePut=true;assertThrows(IOException.class,()->new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)));
            List<Map<String,Object>> pending=new ArrayList<>();try(var files=Files.list(directory)){for(Path path:files.filter(file->file.getFileName().toString().endsWith(".json")).toList()){var state=map(Json.parse(Files.readString(path)));if("PATCH_PENDING".equals(state.get("status")))pending.add(state);}}
            assertEquals(1,pending.size(),"A committed PUT must retain its durable intent until verified");var intent=pending.getFirst();
            assertTrue(clean(intent.get("beforeHash")).matches("[0-9a-f]{64}"));assertTrue(clean(intent.get("afterHash")).matches("[0-9a-f]{64}"));assertFalse(clean(intent.get("guid")).isEmpty());assertFalse(id(intent).isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"guid","owner","source","data"})
    void pendingPutNeverOverwritesAnExternalChange(String change) throws Exception {
        var api=api(1);try(var journal=new Journal(directory)){
            validated(api,journal);api.losePut=true;assertThrows(IOException.class,()->new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)));
            var record=api.delegate.records.get("placeReestrRKCollection").values().iterator().next();
            switch(change){case "guid"->record.put("guid","external-guid");case "owner"->put(record,"unit.id","external-owner");case "source"->put(record,"migrationSource.appealId","external-appeal");default->record.put("externalChange",true);}
        }
        try(var journal=new Journal(directory)){int writes=api.physicalWrites();assertThrows(IllegalStateException.class,()->new ImportOperations(journal,()->{},true,"https://test.example").apply(context(api)));assertEquals(writes,api.physicalWrites());}
    }

    private static CountingApi api(int rows) throws Exception {
        Map<String,Object> fixture;try(var input=AdvertisingCrossLinkLifecycleTest.class.getResourceAsStream("/acceptance.json")){fixture=map(Json.read(input));}
        for(var sheet:maps(at(fixture,"importDraft.sheets"))){List<Object> expanded=new ArrayList<>();if("sheet2".equals(sheet.get("code"))){var original=maps(sheet.get("rows")).getFirst();for(int index=0;index<rows;index++){var row=copy(original);row.put("rowNumber",index+6);for(var cell:maps(row.get("cells")))if("5".equals(clean(cell.get("attribute"))))cell.put("value","РК-291026-"+(index+1));expanded.add(row);}}sheet.put("rows",expanded);}
        map(fixture.get("importDraft")).putAll(obj("validRows",rows,"totalRows",rows,"invalidRows",0));
        return new CountingApi(new AdvertisingAcceptanceFaultTest.Fake(fixture));
    }
    private static void validated(CountingApi api,Journal journal) throws Exception {
        var draft=map(api.delegate.cases.get(api.delegate.mainId).get("importDraft"));String key=hash("cross-link-validation");draft.put("workerValidationKey",key);var compact=Operations.compactDraft(draft);api.delegate.cases.get(api.delegate.mainId).put("importDraft",compact);
        journal.save(key,obj("processId","process","appealId",api.delegate.mainId,"inputHash",ImportOperations.inputHash(compact),"draft",draft));
    }
    private static Context context(CountingApi api) throws Exception {return new Context(AdvertisingAcceptanceFaultTest.command(api.delegate,false,Command.Operation.IMPORT_APPLY),api);}
    private static Map<String,Object> source(CountingApi api){return maps(api.delegate.cases.get(api.delegate.mainId).get("subservices")).getFirst();}

    private static final class CountingApi implements PgsApi {
        final AdvertisingAcceptanceFaultTest.Fake delegate;int patches,losePutAt=1;boolean losePut,failBeforePut;
        CountingApi(AdvertisingAcceptanceFaultTest.Fake delegate){this.delegate=delegate;}
        int physicalWrites(){return delegate.created+patches;}
        public Map<String,Object> find(String collection,String id) throws Exception{return delegate.find(collection,id);}
        public List<Map<String,Object>> search(String collection,List<Object> filters,int page,int size) throws Exception{return delegate.search(collection,filters,page,size);}
        public Map<String,Object> create(String collection,Map<String,Object> payload) throws Exception{return delegate.create(collection,payload);}
        public void update(String collection,String id,String guid,Map<String,Object> patch) throws Exception{if(!collection.equals("rbuModulesAppeals")&&failBeforePut){failBeforePut=false;throw new IOException("PUT failed before commit");}delegate.update(collection,id,guid,patch);if(!collection.equals("rbuModulesAppeals")){patches++;if(losePut&&patches==losePutAt){losePut=false;throw new IOException("Committed PUT response lost");}}}
        public void updateSub(String collection,String id,String guid,String parent,Map<String,Object> patch) throws Exception{delegate.updateSub(collection,id,guid,parent,patch);}
        public void delete(String collection,String id,String guid) throws Exception{delegate.delete(collection,id,guid);}
        public byte[] download(String id,long max) throws Exception{return delegate.download(id,max);}
    }
}
