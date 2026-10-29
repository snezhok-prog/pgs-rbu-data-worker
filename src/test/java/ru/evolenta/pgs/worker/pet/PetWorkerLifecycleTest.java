package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.evolenta.pgs.worker.*;

class PetWorkerLifecycleTest {
    @TempDir Path directory;
    static Command command(String appeal,String operation,String profile) {
        Map<String,Object> variables=obj();
        for(var entry:obj("mainId",appeal,"collectionName","rbuModulesAppeals.subservices","camundaBusinessInfoId",appeal+"-info","rbuDomain","PET","rbuProfile",profile,"rbuOperation",operation,"rbuContractVersion","1").entrySet())variables.put(entry.getKey(),obj("value",entry.getValue()));
        return new Command(obj("id",operation+"-task","processInstanceId",appeal+"-process","activityId",operation,"variables",variables));
    }
    static Context context(Fake api,String appeal,String operation) throws Exception {return new Context(command(appeal,operation,api.profile),api);}
    @Test void validatesImportsRepeatsAndRollsBackOnlyCompletedOwnSource() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");
            assertEquals(true,operations.execute(context(api,"appeal","PARSE_VALIDATE")).get("rbuCanApply"));api.confirm();
            assertEquals(false,operations.execute(context(api,"appeal","IMPORT_APPLY")).get("rbuApplyHasMoreBatches"));
            assertEquals(1,api.created);operations.execute(context(api,"appeal","IMPORT_APPLY"));assertEquals(1,api.created);
            operations.execute(context(api,"appeal","IMPORT_FINALIZE"));
            api.rollback(false);assertThrows(IllegalArgumentException.class,()->operations.execute(context(api,"rollback","ROLLBACK_PREPARE")));
            api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));api.confirmRollback();
            assertEquals(false,operations.execute(context(api,"rollback","ROLLBACK_EXECUTE")).get("rbuRollbackHasMore"));
            operations.execute(context(api,"rollback","ROLLBACK_FINALIZE"));assertEquals(1,api.deleted);
            assertEquals("ROLLED_BACK",at(api.cases.get("rollback"),"rollbackResultStatus"));
        }
    }
    @Test void resumeAfterCommittedCreateAndRefreshCreatesNoDuplicate() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();
            api.loseCreate=true;assertThrows(IOException.class,()->operations.execute(context(api,"appeal","IMPORT_APPLY")));assertEquals(1,api.created);
            assertThrows(IllegalArgumentException.class,()->operations.execute(context(api,"appeal","IMPORT_CANCEL")));
        }
        try(var journal=new Journal(directory)){new PetOperations(journal,()->{},true,"https://test.example").execute(context(api,"appeal","IMPORT_APPLY"));assertEquals(1,api.created);}
    }
    @Test void stopsAtFiftyPhysicalWritesAndRejectsChangedConfirmationAndInput() throws Exception {
        var api=new Fake();api.orders(51);try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();
            put(api.sub("appeal"),"xsdData.rbuImportConfirmation.confirmationFingerprint","f".repeat(64));assertThrows(IllegalArgumentException.class,()->operations.execute(context(api,"appeal","IMPORT_APPLY")));assertEquals(0,api.created);api.confirm();
            assertEquals(true,operations.execute(context(api,"appeal","IMPORT_APPLY")).get("rbuApplyHasMoreBatches"));assertEquals(50,api.created);
            assertEquals(false,operations.execute(context(api,"appeal","IMPORT_APPLY")).get("rbuApplyHasMoreBatches"));assertEquals(51,api.created);
        }
    }
    @Test void validationPermissionAndAuthorityChecksRemainSeparate() throws Exception {
        var api=new Fake();api.profile="OMSU";api.permission=false;try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");assertEquals(true,operations.execute(context(api,"appeal","PARSE_VALIDATE")).get("rbuCanApply"));api.confirm();
            assertThrows(IllegalArgumentException.class,()->operations.execute(context(api,"appeal","IMPORT_APPLY")));assertEquals(0,api.created);
            api.regionCode="77";assertThrows(IllegalArgumentException.class,()->new PetGuard(context(api,"appeal","PARSE_VALIDATE"),false));
            api.regionCode="61";api.cardGuid="foreign";assertThrows(IllegalArgumentException.class,()->new PetGuard(context(api,"appeal","PARSE_VALIDATE"),false));
        }
    }
    @Test void cancellationPersistsWithoutRegistryWrites() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));operations.execute(context(api,"appeal","IMPORT_CANCEL"));
            assertEquals("CANCELLED",at(api.cases.get("appeal"),"importDraft.status"));api.confirm();assertThrows(IllegalArgumentException.class,()->operations.execute(context(api,"appeal","IMPORT_APPLY")));assertEquals(0,api.created);
        }
    }
    @Test void allSixCollectionsAndLinkedPatchAreImportedThenSafelyRestoredAndDeleted() throws Exception {
        var api=new Fake();api.bytes=PetFixtureBuilder.defaults().full("Полный");try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");assertEquals(true,operations.execute(context(api,"appeal","PARSE_VALIDATE")).get("rbuCanApply"));api.confirm();
            operations.execute(context(api,"appeal","IMPORT_APPLY"));assertEquals(6,api.created);assertEquals(1,api.updated);
            assertEquals(6,api.records.keySet().size());assertEquals(1,list(api.sub("appeal").get("migrationPatchedRecords")).size());
            api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));api.confirmRollback();operations.execute(context(api,"rollback","ROLLBACK_EXECUTE"));operations.execute(context(api,"rollback","ROLLBACK_FINALIZE"));
            assertEquals(6,api.deleted);assertEquals(2,api.updated);assertEquals(1,integer(at(api.sub("rollback"),"rbuPetRollbackReport.restoredPatches")));assertEquals(7,integer(at(api.sub("rollback"),"rbuPetRollbackReport.physicalWrites")));
        }
    }
    @Test void createAndLinkedPatchShareOneFiftyWriteBudget() throws Exception {
        var api=new Fake();api.bytes=PetFixtureBuilder.defaults().full("Пакет",45);try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();
            assertEquals(true,operations.execute(context(api,"appeal","IMPORT_APPLY")).get("rbuApplyHasMoreBatches"));assertEquals(50,api.created);assertEquals(0,api.updated);
            assertEquals(false,operations.execute(context(api,"appeal","IMPORT_APPLY")).get("rbuApplyHasMoreBatches"));assertEquals(50,api.created);assertEquals(1,api.updated);
        }
    }
    @Test void rollbackPreparationRefreshRestoresItsConfirmationFromTheDurablePlan() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();operations.execute(context(api,"appeal","IMPORT_APPLY"));api.rollback(true);
            operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));map(api.sub("rollback").get("xsdData")).remove("rbuPetRollbackConfirmation");operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));
            assertEquals(at(api.sub("rollback"),"rbuPetRollbackPlan.journalFingerprint"),at(api.sub("rollback"),"xsdData.rbuPetRollbackConfirmation.journalFingerprint"));
        }
    }
    @Test void distinguishesAlreadyAbsentBlockedAndTechnicalErrorsThenResumesDeleteAfterLostResponse() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();operations.execute(context(api,"appeal","IMPORT_APPLY"));api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));api.confirmRollback();
            api.loseDelete=true;api.loseDeleteVerification=true;assertThrows(IOException.class,()->operations.execute(context(api,"rollback","ROLLBACK_EXECUTE")));
            var plan=map(api.sub("rollback").get("rbuPetRollbackPlan"));assertEquals(1,integer(plan.get("technicalErrors")));assertEquals(0,integer(plan.get("blockedRecords")));assertEquals(1,integer(plan.get("notDeletedRecords")));assertTrue(clean(plan.get("checkpointTimeDisplay")).contains("Europe/Moscow"));
            operations.execute(context(api,"rollback","ROLLBACK_EXECUTE"));operations.execute(context(api,"rollback","ROLLBACK_FINALIZE"));assertEquals(1,api.deleted);assertEquals(1,integer(at(api.sub("rollback"),"rbuPetRollbackReport.deletedRecords")));assertEquals(0,integer(at(api.sub("rollback"),"rbuPetRollbackReport.alreadyDeletedRecords")));assertEquals(1,integer(at(api.sub("rollback"),"rbuPetRollbackReport.physicalWrites")));
        }
    }
    @Test void initiallyAbsentRecordIsSeparateFromWorkerDeletion() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();operations.execute(context(api,"appeal","IMPORT_APPLY"));api.records.get("animalCatchOrderRegistryCollection").clear();api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));
            var plan=map(api.sub("rollback").get("rbuPetRollbackPlan"));assertEquals(1,integer(plan.get("alreadyDeletedRecords")));assertEquals(0,integer(plan.get("deletedRecords")));assertEquals(0,integer(plan.get("blockedRecords")));assertEquals("Уже удалена",at(plan,"rollbackItems.0.status"));api.confirmRollback();operations.execute(context(api,"rollback","ROLLBACK_EXECUTE"));operations.execute(context(api,"rollback","ROLLBACK_FINALIZE"));assertEquals(0,api.deleted);
        }
    }
    @Test void blocksARecordReferencedByAnActiveCaseCreatedAfterPreparation() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();operations.execute(context(api,"appeal","IMPORT_APPLY"));api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));
            var record=api.records.get("animalCatchOrderRegistryCollection").values().iterator().next();api.records.put("myPetAppeals",new LinkedHashMap<>(Map.of("active",obj("_id","active","number","Ж-29","status",obj("code","process"),"record",obj("guid",record.get("guid"))))));
            api.confirmRollback();operations.execute(context(api,"rollback","ROLLBACK_EXECUTE"));operations.execute(context(api,"rollback","ROLLBACK_FINALIZE"));var report=map(api.sub("rollback").get("rbuPetRollbackReport"));assertEquals("PARTIAL",report.get("status"));assertEquals(0,integer(report.get("initialBlockedRecords")));assertEquals(1,integer(report.get("blockedRecords")));assertEquals(1,integer(report.get("notDeletedRecords")));assertEquals(0,api.deleted);
        }
    }
    @Test void terminalRootStatusCannotHideAnUnfinishedReferencedSubservice() throws Exception {
        var api=new Fake();try(var journal=new Journal(directory)){
            var operations=new PetOperations(journal,()->{},true,"https://test.example");operations.execute(context(api,"appeal","PARSE_VALIDATE"));api.confirm();operations.execute(context(api,"appeal","IMPORT_APPLY"));
            var record=api.records.get("animalCatchOrderRegistryCollection").values().iterator().next();
            api.records.put("myPetAppeals",new LinkedHashMap<>(Map.of("active",obj("_id","active","status",obj("code","complete"),"subservices",List.of(obj("status",obj("code","process"),"camundaProcessInfo",obj("status","ACTIVE"),"recordId",id(record)))))));
            api.rollback(true);operations.execute(context(api,"rollback","ROLLBACK_PREPARE"));api.confirmRollback();operations.execute(context(api,"rollback","ROLLBACK_EXECUTE"));assertEquals(0,api.deleted);assertEquals(1,integer(at(api.sub("rollback"),"rbuPetRollbackPlan.blockedRecords")));
        }
    }
    static final class Fake implements PgsApi {
        final Map<String,Map<String,Object>> cases=new LinkedHashMap<>();
        final Map<String,Map<String,Map<String,Object>>> records=new LinkedHashMap<>();
        byte[] bytes;String profile="ROIV",regionCode="61",cardGuid="8cd09210-4b66-45f6-b422-36ee01df6e4a",deletedIdentity="";boolean permission=true,loseCreate,loseDelete,loseDeleteVerification;int created,deleted,updated,readFailures;
        Fake() throws Exception {bytes=PetXlsxIntegrationTest.workbook(false);cases.put("appeal",obj("_id","appeal","guid","appeal-guid","number","PET-TEST","unitId","owner","unit",obj("id","owner"),"region",obj("_id","region"),"municipality",obj("_id","municipality"),"organization",obj("_id","card"),"importFile",obj("_id","file","originalName","pet.xlsx"),"subservices",new ArrayList<>(List.of(sub("appeal","sub")))));}
        static Map<String,Object> sub(String appeal,String guid){return obj("guid",guid,"parentEntries","rbuModulesAppeals.subservices","camundaProcessInfo",obj("id",appeal+"-process","camundaBusinessInfoId",appeal+"-info"),"xsdData",obj());}
        Map<String,Object> sub(String id){return maps(cases.get(id).get("subservices")).getFirst();}
        void confirm(){put(sub("appeal"),"xsdData.rbuImportConfirmation",obj("confirmApply",true,"confirmationFingerprint",at(cases.get("appeal"),"importDraft.confirmationFingerprint")));}
        void rollback(boolean completed){if(completed)put(cases.get("appeal"),"status.code","complete");cases.put("rollback",obj("_id","rollback","guid","rollback-guid","number","RB-PET","unitId","owner","unit",obj("id","owner"),"region",obj("_id","region"),"municipality",obj("_id","municipality"),"organization",obj("_id","card"),"migrationCase",obj("_id","appeal"),"subservices",new ArrayList<>(List.of(sub("rollback","rollback-sub")))));}
        void confirmRollback(){Map<String,Object> plan=map(sub("rollback").get("rbuPetRollbackPlan"));cases.get("rollback").put("rbuRollbackDecision",obj("confirmed",true,"targetAppealId","appeal","journalFingerprint",plan.get("journalFingerprint")));}
        void orders(int count) throws Exception {try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes));var output=new ByteArrayOutputStream()){var sheet=workbook.getSheet(PetXlsxParser.SHEETS.get("sheet2"));var first=sheet.getRow(5);for(int row=1;row<count;row++){var next=sheet.createRow(5+row);for(var cell:first)next.createCell(cell.getColumnIndex()).setCellValue(cell.getStringCellValue());next.getCell(9).setCellValue("ORDER-"+(row+1));}workbook.write(output);bytes=output.toByteArray();}}
        public Map<String,Object> find(String collection,String identifier) throws Exception {
            if((collection+"/"+identifier).equals(deletedIdentity)&&readFailures>0){readFailures--;throw new IOException("DELETE verification response lost");}
            if(collection.equals("rbuModulesAppeals"))return copy(cases.getOrDefault(identifier,obj()));
            if(collection.equals("organizations")&&identifier.equals("owner"))return obj("_id","owner","guid","8cd09210-4b66-45f6-b422-36ee01df6e4a","name","Тестовая организация","regions",List.of(obj("code","61")));
            if(collection.equals("rbuPetRegionPassports")&&identifier.equals("region"))return obj("_id","region","regionCode",regionCode,"regionName","Тестовый регион","status","active");
            if(collection.equals("rbuPetRegionMunicipalities")&&identifier.equals("municipality"))return obj("_id","municipality","municipalityId","municipality-id","passportId","region","regionCode","61","municipalityName","Тестовое МО","status","active");
            if(collection.equals("rbuPetRegionOrganizations")&&identifier.equals("card"))return obj("_id","card","organizationId","owner","organizationGuid",cardGuid,"organizationName","Тестовая организация","ogrn","1234567890123","regionCode","61","passportId","region","municipalities",List.of(obj("municipalityId","municipality-id")),"accessStatus","active","serviceCode","673151","serviceDomain","MY_PET","isMigration",permission);
            return copy(records.getOrDefault(collection,Map.of()).getOrDefault(identifier,obj()));
        }
        public List<Map<String,Object>> search(String collection,List<Object> filters,int page,int size) throws Exception {
            List<Map<String,Object>> found=new ArrayList<>();
            if(collection.equals("organizations")){var owner=find(collection,"owner");if(matches(owner,filters))found.add(owner);}
            for(var row:records.getOrDefault(collection,Map.of()).values())if(matches(row,filters))found.add(copy(row));
            int offset=Math.min(page*size,found.size());return found.subList(offset,Math.min(offset+size,found.size()));
        }
        static boolean matches(Map<String,Object> row,List<Object> filters){for(Object value:filters){var condition=map(value);if(!clean(at(row,clean(condition.get("field")))).equals(clean(condition.get("value"))))return false;}return true;}
        public void update(String collection,String identifier,String guid,Map<String,Object> patch){if(collection.equals("rbuModulesAppeals"))cases.get(identifier).putAll(copy(patch));else{assertEquals(records.get(collection).get(identifier).get("guid"),guid);records.get(collection).get(identifier).putAll(copy(patch));updated++;}}
        public void updateSub(String collection,String identifier,String guid,String parent,Map<String,Object> patch){for(var sub:maps(cases.get(identifier).get("subservices")))if(guid.equals(sub.get("guid")))sub.putAll(copy(patch));}
        public Map<String,Object> create(String collection,Map<String,Object> payload) throws Exception {var record=copy(payload);record.put("_id",String.format("%024x",++created));records.computeIfAbsent(collection,key->new LinkedHashMap<>()).put(id(record),record);if(loseCreate){loseCreate=false;throw new IOException("Committed create response lost");}return copy(record);}
        public void delete(String collection,String identifier,String guid) throws Exception {var record=records.getOrDefault(collection,Map.of()).get(identifier);if(record!=null){assertEquals(guid,record.get("guid"));records.get(collection).remove(identifier);deleted++;if(loseDelete){loseDelete=false;deletedIdentity=collection+"/"+identifier;if(loseDeleteVerification)readFailures=1;throw new IOException("Committed DELETE response lost");}}}
        public byte[] download(String identifier,long maximum){assertEquals("file",identifier);return bytes;}
    }
}
