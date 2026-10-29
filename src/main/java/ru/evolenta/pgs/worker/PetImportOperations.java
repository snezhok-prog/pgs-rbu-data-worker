package ru.evolenta.pgs.worker;

import static ru.evolenta.pgs.worker.Util.*;
import static ru.evolenta.pgs.worker.pet.PetRecordHash.businessHash;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ru.evolenta.pgs.worker.pet.*;
import ru.evolenta.pgs.worker.trade.core.EntityReference;

/** PET import with a shared durable journal and at most 50 CREATE/PATCH writes per delivery. */
public final class PetImportOperations {
    public static final String WORKER_CONTRACT="pgs-pet-external-v1";
    private final CheckpointStore journal;
    private final Operations.Lease lease;
    private final boolean writes;
    private final String ui;
    public PetImportOperations(CheckpointStore journal,Operations.Lease lease,boolean writes,String ui){this.journal=journal;this.lease=lease;this.writes=writes;this.ui=ui.replaceAll("/+$","");}

    public static String inputHash(Map<String,Object> draft) {
        return hash(obj("sheets",draft.get("sheets"),"file",draft.get("file"),"context",draft.get("context"),"validationVersion",draft.get("validationVersion"),
                "parserVersion",draft.get("parserVersion"),"dataType",draft.get("dataType"),"validatedFileSha256",draft.get("validatedFileSha256"),
                "totalRows",draft.get("totalRows"),"validRows",draft.get("validRows"),"invalidRows",draft.get("invalidRows"),"validationBlocked",draft.get("validationBlocked"),
                "workerValidationKey",draft.get("workerValidationKey"),"confirmationFingerprint",draft.get("confirmationFingerprint")));
    }
    public static String runKey(Context worker,String fileHash) {return hash(obj("process",worker.command.processId,"mainId",worker.command.mainId,"file",fileHash,"operation","PET_IMPORT"));}

    public Map<String,Object> apply(Context c) throws Exception {
        if(!writes)throw new IllegalStateException("Реестровые изменения отключены worker.writesEnabled");
        if(ui.isEmpty()||!ui.startsWith("https://")||ui.contains("REPLACE"))throw new IllegalArgumentException("Настройте HTTPS worker.uiBaseUrl до применения PET");
        PetGuard guard=new PetGuard(c,true);
        Map<String,Object> draft=copy(map(first(c.appeal.get("importDraft"),c.sub.get("rbuImportDraft"))));
        String fileId=clean(at(draft,"file.id")),fileHash=clean(at(draft,"file.sha256"));
        if(!fileId.equals(clean(first(at(c.appeal,"importFile._id"),at(c.appeal,"importFile.id"),at(c.appeal,"importFile.fileId")))))throw new IllegalArgumentException("В деле выбран другой файл; повторите проверку");
        if(!Set.of("VALIDATED","APPLYING","APPLIED").contains(clean(draft.get("status")))||bool(draft.get("validationBlocked"))||integer(draft.get("validRows"))<=0||integer(draft.get("invalidRows"))!=0
                ||!PetDomain.VALIDATION_VERSION.equals(draft.get("validationVersion"))||!"rbu-pet-1.0".equals(draft.get("parserVersion"))||!"Мой питомец".equals(draft.get("dataType"))
                ||!fileHash.matches("[0-9a-f]{64}")||!fileHash.equals(draft.get("validatedFileSha256")))throw new IllegalArgumentException("Нет актуального допустимого результата валидации PET");
        if(!bool(at(c.xsd,"rbuImportConfirmation.confirmApply"))||!clean(draft.get("confirmationFingerprint")).matches("[0-9a-f]{64}")
                ||!draft.get("confirmationFingerprint").equals(at(c.xsd,"rbuImportConfirmation.confirmationFingerprint")))throw new IllegalArgumentException("Подтверждение относится к другой проверке файла");
        if(!hash(guard.context()).equals(hash(draft.get("context"))))throw new IllegalArgumentException("Организация или территория изменились после проверки");
        String validationKey=clean(draft.get("workerValidationKey"));
        if(!validationKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Отсутствует серверный ключ валидации PET");
        Map<String,Object> validated=journal.read(validationKey);
        if(!c.command.processId.equals(validated.get("processId"))||!c.command.mainId.equals(validated.get("appealId"))||!inputHash(draft).equals(validated.get("inputHash")))throw new IllegalArgumentException("Серверный снимок валидации отсутствует или изменён");
        draft=copy(map(validated.get("draft")));
        if(draft.isEmpty()||bool(draft.get("validationBlocked"))||!fileHash.equals(at(draft,"file.sha256")))throw new IllegalArgumentException("Серверная валидация не допускает применение");
        lease.check();if(!fileHash.equals(sha(c.download(fileId,XlsxReader.MAX_FILE))))throw new IllegalArgumentException("Содержимое XLSX изменилось после проверки");
        c.reload();if(!hash(guard.context()).equals(hash(new PetGuard(c,true).context()))||!fileId.equals(clean(first(at(c.appeal,"importFile._id"),at(c.appeal,"importFile.id"),at(c.appeal,"importFile.fileId")))))throw new IllegalArgumentException("Входные данные изменились перед импортом");
        ImportLifecycle.markStarted(c,journal,lease);
        String runKey=runKey(c,fileHash);Map<String,Object> state=journal.read(runKey);
        if(state.isEmpty()){state=obj("status","APPLYING","createdAt",now(),"cursor",0,"processId",c.command.processId,"appealId",c.command.mainId,"workerContract",WORKER_CONTRACT,"organizationId",id(guard.organization),"cabinetId",guard.cabinetId);journal.save(runKey,state);}
        if(!c.command.mainId.equals(state.get("appealId"))||!c.command.processId.equals(state.get("processId"))||!id(guard.organization).equals(state.get("organizationId"))||!WORKER_CONTRACT.equals(state.get("workerContract"))||!guard.cabinetId.equals(state.get("cabinetId")))throw new IllegalStateException("Контрольная точка импорта принадлежит другому контексту");
        if(Set.of("RESULT_READY","APPLIED").contains(clean(state.get("status"))))return persist(c,runKey,state);
        List<Map<String,Object>> plan=plan(c,draft,clean(state.get("createdAt")));
        String planHash=hash(plan);if(present(state.get("planHash"))&&!planHash.equals(state.get("planHash")))throw new IllegalStateException("План импорта изменился; продолжение запрещено");
        state.put("planHash",planHash);journal.save(runKey,state);
        int cursor=integer(state.get("cursor"));if(cursor<0||cursor>plan.size())throw new IllegalStateException("Повреждён cursor импорта PET");
        PetMutationStore mutations=new PetMutationStore(c.api,journal,lease,c.command.mainId,id(guard.organization));
        Map<String,Map<String,Object>> created=new LinkedHashMap<>();
        for(int index=0;index<cursor;index++)if("CREATE".equals(plan.get(index).get("kind"))){var step=plan.get(index);var receipt=journal.read(clean(step.get("operationKey")));verify(c,step,receipt,plan);created.put(clean(step.get("ref")),receipt);}
        int end=Math.min(plan.size(),cursor+50);
        for(int index=cursor;index<end;index++){
            lease.check();var step=plan.get(index);String key=clean(step.get("operationKey"));
            if("CREATE".equals(step.get("kind"))){var receipt=mutations.create(clean(step.get("collection")),key,map(resolve(step.get("payload"),created)));created.put(clean(step.get("ref")),receipt);}
            else {var target=created.get(clean(step.get("targetRef")));if(target==null)throw new IllegalStateException("Не создана зависимость PATCH");mutations.patch(key,target,map(resolve(step.get("payload"),created)));created.put(clean(step.get("targetRef")),journal.read(clean(target.get("operationKey"))));}
            state.put("cursor",index+1);journal.save(runKey,state);
        }
        boolean more=end<plan.size();List<Object> records=new ArrayList<>(),patches=new ArrayList<>();Set<String> rows=new HashSet<>();Map<String,Object> byCollection=obj();
        for(int index=0;index<end;index++){
            var step=plan.get(index);var receipt=journal.read(clean(step.get("operationKey")));
            if("PATCH".equals(step.get("kind"))){if(!"PATCHED".equals(receipt.get("status")))throw new IllegalStateException("PATCH не подтверждён");patches.add(receipt);continue;}
            var record=c.api.find(clean(step.get("collection")),id(receipt));verify(c,step,receipt,plan);
            records.add(obj("collection",step.get("collection"),"_id",id(receipt),"guid",receipt.get("guid"),"operationKey",step.get("operationKey"),"businessHash",businessHash(record),"unitId",id(guard.organization),
                    "sheetCode",step.get("sheetCode"),"sheetName",step.get("sheetName"),"rowNumber",step.get("rowNumber"),"recordPart",step.get("recordPart"),"createdAt",state.get("createdAt")));
            rows.add(step.get("sheetCode")+":"+step.get("rowNumber"));String collection=clean(step.get("collection"));byCollection.put(collection,integer(byCollection.get(collection))+1);
        }
        draft.putAll(obj("status",more?"APPLYING":"APPLIED","batchProgress",obj("status",more?"PROCESSING":"COMPLETED","nextOffset",end,"totalRecords",plan.size(),"fileSha256",fileHash,"updatedAt",now())));
        Map<String,Object> result=copy(map(c.sub.get("rbuImportResult")));result.remove("rbuImportConfirmation");result.putAll(obj("processingStatus",more?"Миграция выполняется":"Миграция завершена","applicationStatus",draft.get("status"),"createdRows",rows.size(),"createdRecords",records.size(),"patchedRecords",patches.size(),"reusedRows",0,"skippedRows",0,"processedAt",now()));
        Map<String,Object> summary=obj("workerContract",WORKER_CONTRACT,"workerRunKey",runKey,"status",draft.get("status"),"dataType","Мой питомец","recordCount",records.size(),"patchCount",patches.size(),"createdRows",rows.size(),"appliedRows",rows.size(),"validRows",draft.get("validRows"),"recordsByCollection",byCollection,"sourceFileSha256",fileHash,"updatedAt",now());
        if(!more){state.put("completedAt",now());summary.put("completedAt",state.get("completedAt"));}
        Map<String,Object> variables=obj("rbuApplicationStatus",draft.get("status"),"rbuCreatedRows",rows.size(),"rbuCreatedRecords",records.size(),"rbuPatchedRecords",patches.size(),"rbuApplyHasMoreBatches",more,"rbuApplyRowCursor",end,"rbuApplyRejected",false,"rbuApplyError","");
        state.putAll(obj("status",more?"APPLYING":"RESULT_READY","recordJournal",records,"patchJournal",patches,"readyDraft",PetOperations.compactDraft(draft),"readyResult",result,"readySummary",summary,"resultVariables",variables));journal.save(runKey,state);
        return persist(c,runKey,state);
    }

    private List<Map<String,Object>> plan(Context c,Map<String,Object> draft,String time) {
        PetDomain domain=new PetDomain(c);List<Map<String,Object>> result=new ArrayList<>();
        var entities=domain.plan(draft,time);if(entities.isEmpty()||entities.size()>2500)throw new IllegalArgumentException("Импорт PET должен создавать от 1 до 2500 записей");
        for(var entity:entities){Map<String,Object> payload=new LinkedHashMap<>(entity.data()),source=new LinkedHashMap<>(map(payload.get("migrationSource")));String operationKey=hash(obj("process",c.command.processId,"appeal",c.command.mainId,"file",at(draft,"file.sha256"),"collection",entity.collection(),"ref",entity.key(),"kind","CREATE"));source.putAll(obj("workerContract",WORKER_CONTRACT,"operationKey",operationKey));payload.put("migrationSource",source);payload.put("guid",UUID.nameUUIDFromBytes(operationKey.getBytes(StandardCharsets.UTF_8)).toString());result.add(obj("kind","CREATE","ref",entity.key(),"operationKey",operationKey,"collection",entity.collection(),"payload",payload,"sheetCode",source.get("sheetCode"),"sheetName",source.get("sheetName"),"rowNumber",source.get("rowNumber"),"recordPart",source.get("recordPart")));}
        for(var patch:domain.patches(draft,time)){String operationKey=hash(obj("process",c.command.processId,"appeal",c.command.mainId,"file",at(draft,"file.sha256"),"ref",patch.key(),"kind","PATCH"));result.add(obj("kind","PATCH","ref",patch.key(),"targetRef",patch.target().key(),"operationKey",operationKey,"collection",patch.collection(),"payload",patch.patch()));}
        return result;
    }

    private Object resolve(Object value,Map<String,Map<String,Object>> records) {
        if(value instanceof EntityReference reference){Map<String,Object> record=records.get(reference.key());if(record==null)throw new IllegalStateException("Не создана зависимость: "+reference.key());return record.get(reference.field());}
        if(value instanceof ReferenceTemplate template)return template.prefix()+resolve(template.reference(),records)+template.suffix();
        if(value instanceof Map){Map<String,Object> result=obj();map(value).forEach((name,item)->result.put(name,resolve(item,records)));return result;}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object item:list(value))result.add(resolve(item,records));return result;}
        return value;
    }

    private void verify(Context c,Map<String,Object> step,Map<String,Object> receipt,List<Map<String,Object>> plan) throws Exception {
        if(!"COMPLETED".equals(receipt.get("status")))throw new IllegalStateException("Потерян CREATE receipt");
        var record=c.api.find(clean(step.get("collection")),id(receipt));String expected=clean(receipt.get("businessHash"));
        for(var possible:plan)if("PATCH".equals(possible.get("kind"))&&step.get("ref").equals(possible.get("targetRef"))){var patch=journal.read(clean(possible.get("operationKey")));if(Set.of("PATCH_PENDING","PATCHED").contains(clean(patch.get("status")))&&businessHash(record).equals(patch.get("afterHash")))expected=clean(patch.get("afterHash"));}
        if(record.isEmpty()||!clean(receipt.get("guid")).equals(clean(record.get("guid")))||!clean(receipt.get("unitId")).equals(clean(at(record,"unit.id")))
                ||!c.command.mainId.equals(at(record,"migrationSource.appealId"))||!step.get("operationKey").equals(at(record,"migrationSource.operationKey"))||!expected.equals(businessHash(record)))throw new IllegalStateException("Ранее созданная запись PET изменилась; продолжение запрещено");
    }

    private Map<String,Object> persist(Context c,String key,Map<String,Object> state) throws Exception {
        lease.check();Map<String,Object> draft=map(state.get("readyDraft")),result=map(state.get("readyResult"));
        c.saveAppeal(obj("importDraft",draft,"applicationStatus",draft.get("status"),"migrationLog",result.get("migrationLog")));c.saveResult(draft,result);
        c.saveSub(obj("migrationCreatedRecords",state.get("recordJournal"),"migrationPatchedRecords",state.get("patchJournal"),"petMigrationSummary",state.get("readySummary")));
        if("RESULT_READY".equals(state.get("status"))){state.put("status","APPLIED");journal.save(key,state);}return map(state.get("resultVariables"));
    }
    public Map<String,Object> finalizeImport(Context c) throws Exception {
        Map<String,Object> summary=map(c.sub.get("petMigrationSummary")),state=journal.read(runKey(c,clean(at(c.appeal,"importDraft.file.sha256"))));
        if(!"APPLIED".equals(state.get("status"))||!"APPLIED".equals(summary.get("status"))||!hash(c.sub.get("migrationCreatedRecords")).equals(hash(state.get("recordJournal")))||!hash(c.sub.get("migrationPatchedRecords")).equals(hash(state.get("patchJournal"))))throw new IllegalStateException("Нет подтверждённого завершённого импорта PET");
        return obj("rbuApplicationStatus","APPLIED","rbuApplyHasMoreBatches",false,"rbuCreatedRows",summary.get("createdRows"));
    }
}
