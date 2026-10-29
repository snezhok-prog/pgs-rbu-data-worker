package ru.evolenta.pgs.worker;

import static ru.evolenta.pgs.worker.Util.*;
import static ru.evolenta.pgs.worker.pet.PetRecordHash.businessHash;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ru.evolenta.pgs.worker.pet.*;

/** Server-side PET rollback, resumed from the durable checkpoint after every physical write. */
public final class PetRollbackOperations {
    private static final Set<String> TERMINAL=Set.of("complete","completeSigned","completeSign","completeNegative","completeTrapping","completeTrappingPartially","completeTrappingNegative","cancelled","canceled","closed","archived");
    private static final Set<String> ABSENT=Set.of("DELETED","ALREADY_DELETED");
    private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX '['VV']'").withZone(ZoneId.of("Europe/Moscow"));
    private final CheckpointStore journal;
    private final Operations.Lease lease;
    private final boolean writes;
    public PetRollbackOperations(CheckpointStore journal,Operations.Lease lease,boolean writes){this.journal=journal;this.lease=lease;this.writes=writes;}

    private String targetId(Context c) {
        Object selected=first(c.appeal.get("migrationCase"),c.xsd.get("migrationCase"),at(c.xsd,"mainElement.migrationCase"));
        if(selected instanceof List)selected=list(selected).size()==1?list(selected).getFirst():null;
        String selectedId=selected instanceof Map?id(selected):clean(selected);
        if(selectedId.isEmpty()||selectedId.equals(c.command.mainId))throw new IllegalArgumentException("Выберите одно исходное дело миграции PET");return selectedId;
    }
    private Map<String,Object> target(Context c,String targetId) throws Exception {
        Map<String,Object> target=c.api.find(c.command.collection,targetId);
        if(!targetId.equals(id(target))||!PetGuard.owner(c.appeal).equals(PetGuard.owner(target)))throw new IllegalArgumentException("Можно выбрать только миграцию своего кабинета");
        if(!"complete".equals(first(at(target,"status.code"),at(target,"appealStatus.code"))))throw new IllegalArgumentException("Откат PET доступен только для завершённого исходного дела");
        return target;
    }
    private Map<String,Object> source(Context c,Map<String,Object> target,String guid) throws Exception {
        List<Map<String,Object>> found=new ArrayList<>();
        for(var sub:maps(target.get("subservices")))if((guid.isEmpty()||guid.equals(clean(sub.get("guid"))))&&PetImportOperations.WORKER_CONTRACT.equals(at(sub,"petMigrationSummary.workerContract"))&&"APPLIED".equals(at(sub,"petMigrationSummary.status")))found.add(sub);
        if(found.size()!=1)throw new IllegalArgumentException("Не найден однозначный журнал завершённого импорта PET external-worker");
        Map<String,Object> sub=found.getFirst();String key=clean(at(sub,"petMigrationSummary.workerRunKey"));
        if(!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Нет серверной контрольной точки исходного импорта");
        Map<String,Object> run=journal.read(key);PetGuard guard=new PetGuard(c,false);
        if(!"APPLIED".equals(run.get("status"))||!PetImportOperations.WORKER_CONTRACT.equals(run.get("workerContract"))||!id(target).equals(run.get("appealId"))
                ||!guard.cabinetId.equals(run.get("cabinetId"))||!id(guard.organization).equals(run.get("organizationId"))
                ||!clean(first(at(sub,"camundaProcessInfo.id"),at(sub,"camundaProcessInfo.instanceId"))).equals(run.get("processId"))
                ||!hash(entries(sub)).equals(hash(run.get("recordJournal")))||!hash(sub.get("migrationPatchedRecords")).equals(hash(run.get("patchJournal"))))throw new IllegalArgumentException("Исходный журнал PET не подтверждён доверенным состоянием worker");
        return sub;
    }
    private List<Map<String,Object>> entries(Map<String,Object> sub) {
        List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var entry:maps(sub.get("migrationCreatedRecords"))){
            String collection=clean(entry.get("collection"));
            if(!PetModule.COLLECTIONS.contains(collection)||id(entry).isEmpty()||clean(entry.get("guid")).isEmpty()||!clean(entry.get("businessHash")).matches("[0-9a-f]{64}")||!clean(entry.get("operationKey")).matches("[0-9a-f]{64}")||!seen.add(collection+"/"+id(entry)))throw new IllegalArgumentException("Повреждён журнал записей PET");
            result.add(copy(entry));
        }
        if(result.isEmpty()||result.size()>2500)throw new IllegalArgumentException("Журнал PET должен содержать от 1 до 2500 уникальных записей");return result;
    }
    private String identityHash(Map<String,Object> plan) {
        List<Object> identities=new ArrayList<>();for(var item:maps(plan.get("items")))identities.add(obj("collection",item.get("collection"),"_id",item.get("_id"),"guid",item.get("guid"),"businessHash",item.get("businessHash"),"unitId",item.get("unitId"),"sourceKey",item.get("sourceKey"),"operationKey",item.get("operationKey")));
        return hash(obj("rollbackAppealId",plan.get("rollbackAppealId"),"targetAppealId",plan.get("targetAppealId"),"targetSubserviceGuid",plan.get("targetSubserviceGuid"),"journalFingerprint",plan.get("journalFingerprint"),"items",identities,"patches",plan.get("patches")));
    }

    public Map<String,Object> prepare(Context c) throws Exception {
        String targetId=targetId(c);Map<String,Object> target=target(c,targetId),sub=source(c,target,"");
        if(present(at(target,"petMigrationRollback.completedAt")))throw new IllegalArgumentException("Миграция PET уже полностью откачена");
        List<Map<String,Object>> entries=entries(sub);String fingerprint=hash(obj("records",entries,"patches",sub.get("migrationPatchedRecords")));
        String key=hash(obj("process",c.command.processId,"appeal",c.command.mainId,"target",targetId,"journal",fingerprint));
        Map<String,Object> saved=journal.read(key);
        if(!saved.isEmpty()){Map<String,Object> existing=map(saved.get("plan"));if(!c.command.processId.equals(saved.get("processId"))||!identityHash(existing).equals(saved.get("planHash")))throw new IllegalArgumentException("Повреждена контрольная точка отката PET");ensureConfirmation(c,existing);persist(c,key,saved);return obj("rbuRollbackTargetId",targetId,"rbuRollbackPlanFingerprint",fingerprint);}
        List<Map<String,Object>> items=new ArrayList<>();
        for(var entry:entries){
            Map<String,Object> item=copy(entry);item.putAll(obj("state","PENDING","reason","Ожидает выполнения удаления","activeCases",new ArrayList<>(),"sourceKey",entry.get("sheetCode")+":"+entry.get("rowNumber"),"recordName",entry.get("recordPart")+" / "+entry.get("sheetCode")+" / "+entry.get("rowNumber")));
            lease.check();try{assess(c,targetId,item,c.api.find(clean(item.get("collection")),id(item)),null);}catch(Exception error){item.putAll(obj("state","ERROR","reason","Не удалось проверить запись: "+error.getClass().getSimpleName()));}items.add(item);
        }
        items.sort(Comparator.comparingInt(item->priority(clean(item.get("collection")))));
        markActive(c,items);propagate(items);
        Map<String,Object> plan=obj("version","rbu-pet-rollback-external-1.0","status","PREPARED","createdAt",now(),"startedAt",now(),"rollbackAppealId",c.command.mainId,"rollbackAppealNumber",c.appeal.get("number"),"targetAppealId",targetId,"targetAppealNumber",target.get("number"),"targetSubserviceGuid",sub.get("guid"),"sourceFileName",first(at(target,"importDraft.file.originalName"),at(sub,"rbuImportDraft.file.originalName")),"journalFingerprint",fingerprint,"items",items,"patches",copy(obj("items",sub.get("migrationPatchedRecords"))).get("items"),"workerPlanKey",key,"resumeCount",0,"lastCompletedBatch",0,"physicalWrites",0,"isCompleted",false);
        counts(plan);plan.putAll(obj("initialBlockedRecords",plan.get("blockedRecords"),"initialAlreadyDeletedRecords",plan.get("alreadyDeletedRecords"),"initialDeletableRecords",plan.get("pendingRecords"),"initialTechnicalErrors",plan.get("technicalErrors")));
        saved=obj("processId",c.command.processId,"planHash",identityHash(plan),"plan",plan);journal.save(key,saved);
        ensureConfirmation(c,plan);persist(c,key,saved);
        return obj("rbuRollbackTargetId",targetId,"rbuRollbackPlanFingerprint",fingerprint);
    }

    public Map<String,Object> execute(Context c) throws Exception {
        if(!writes)throw new IllegalStateException("Реестровые изменения отключены worker.writesEnabled");
        Map<String,Object> publicPlan=map(c.sub.get("rbuPetRollbackPlan"));String key=require(publicPlan,"workerPlanKey");Map<String,Object> saved=journal.read(key);
        if(!c.command.processId.equals(saved.get("processId"))||!identityHash(publicPlan).equals(saved.get("planHash")))throw new IllegalArgumentException("План отката PET изменён или отсутствует серверный снимок");
        Map<String,Object> plan=map(saved.get("plan"));confirmed(c,plan);
        String targetId=clean(plan.get("targetAppealId"));Map<String,Object> sub=source(c,target(c,targetId),clean(plan.get("targetSubserviceGuid")));
        if(!hash(obj("records",entries(sub),"patches",sub.get("migrationPatchedRecords"))).equals(plan.get("journalFingerprint")))throw new IllegalArgumentException("Журнал исходной миграции изменился после подготовки");
        if("EXECUTED".equals(plan.get("status")))return variables(plan,false);
        if(!"PREPARED".equals(plan.get("status")))plan.put("resumeCount",integer(plan.get("resumeCount"))+1);
        plan.put("status","EXECUTING");List<Map<String,Object>> items=maps(plan.get("items"));
        for(var item:items){lease.check();String deleteKey=deleteKey(key,item);Map<String,Object> prior=journal.read(deleteKey);Map<String,Object> record=c.api.find(clean(item.get("collection")),id(item));
            if(record.isEmpty()&&Set.of("DELETE_PENDING","DELETED").contains(clean(prior.get("status")))){item.putAll(obj("state","DELETED","reason","Удаление подтверждено worker"));journal.save(deleteKey,obj("status","DELETED","deletedAt",now(),"collection",item.get("collection"),"_id",id(item)));continue;}
            if(!record.isEmpty()&&ABSENT.contains(clean(item.get("state")))){item.putAll(obj("state","BLOCKED","reason","Запись с ранее отсутствовавшим ID появилась повторно"));continue;}
            if(!ABSENT.contains(clean(item.get("state")))&&!"BLOCKED".equals(item.get("state")))assess(c,targetId,item,record,plan);
        }
        markActive(c,items);propagate(items);persist(c,key,saved);
        int writesNow=0;PetGuard guard=new PetGuard(c,false);PetMutationStore mutations=new PetMutationStore(c.api,journal,lease,targetId,id(guard.organization));
        for(var patch:maps(plan.get("patches"))){
            Map<String,Object> item=items.stream().filter(value->clean(value.get("collection")).equals(clean(patch.get("collection")))&&id(value).equals(id(patch))).findFirst().orElseThrow(()->new IllegalStateException("PATCH не связан с созданной записью"));
            if(!"PENDING".equals(item.get("state")))continue;
            String undoKey=undoKey(key,patch);if("RESTORED".equals(journal.read(undoKey).get("status"))){restored(plan,undoKey);continue;}
            if(writesNow==50)break;
            writesNow++;
            try{lease.check();mutations.undoPatch(undoKey,patch);restored(plan,undoKey);persist(c,key,saved);}
            catch(Exception error){item.putAll(obj("state","ERROR","reason","Ошибка восстановления связанного поля: "+error.getClass().getSimpleName()));plan.put("status","ERROR");persist(c,key,saved);throw error;}
        }
        boolean patchesPending=false;for(var patch:maps(plan.get("patches"))){var item=items.stream().filter(value->id(value).equals(id(patch))&&clean(value.get("collection")).equals(clean(patch.get("collection")))).findFirst().orElseThrow();if("PENDING".equals(item.get("state"))&&!"RESTORED".equals(journal.read(undoKey(key,patch)).get("status")))patchesPending=true;}
        if(!patchesPending)for(var item:items){
            if(writesNow==50)break;if(!"PENDING".equals(item.get("state")))continue;lease.check();
            String collection=clean(item.get("collection"));Map<String,Object> record=c.api.find(collection,id(item));assess(c,targetId,item,record,plan);if(!"PENDING".equals(item.get("state"))){propagate(items);continue;}
            String deleteKey=deleteKey(key,item);journal.save(deleteKey,obj("status","DELETE_PENDING","collection",collection,"_id",id(item),"guid",item.get("guid"),"expectedHash",businessHash(record),"planKey",key,"startedAt",now()));
            writesNow++;
            try{lease.check();c.api.delete(collection,id(item),clean(record.get("guid")));
                if(!c.api.find(collection,id(item)).isEmpty())throw new IllegalStateException("Запись осталась после DELETE");
                journal.save(deleteKey,obj("status","DELETED","collection",collection,"_id",id(item),"deletedAt",now()));item.putAll(obj("state","DELETED","reason","Удаление подтверждено worker"));persist(c,key,saved);
            }catch(Exception error){try{if(c.api.find(collection,id(item)).isEmpty()){journal.save(deleteKey,obj("status","DELETED","collection",collection,"_id",id(item),"deletedAt",now()));item.putAll(obj("state","DELETED","reason","Удаление подтверждено после потери ответа"));persist(c,key,saved);continue;}}catch(Exception verify){error.addSuppressed(verify);}item.putAll(obj("state","ERROR","reason","Техническая ошибка удаления: "+error.getClass().getSimpleName()));plan.put("status","ERROR");persist(c,key,saved);throw error;}
        }
        counts(plan);boolean more=integer(plan.get("pendingRecords"))+integer(plan.get("technicalErrors"))>0;
        plan.putAll(obj("status",more?"EXECUTING":"EXECUTED","isCompleted",!more,"lastCompletedBatch",integer(plan.get("lastCompletedBatch"))+1));if(!more)plan.put("executedAt",now());persist(c,key,saved);return variables(plan,more);
    }

    private void confirmed(Context c,Map<String,Object> plan) {
        Map<String,Object> decision=map(c.appeal.get("rbuRollbackDecision"));if(decision.isEmpty())decision=map(c.xsd.get("rbuPetRollbackConfirmation"));
        if(!bool(first(decision.get("confirmed"),decision.get("confirmFinalRollback")))||!clean(plan.get("targetAppealId")).equals(clean(decision.get("targetAppealId")))||!clean(plan.get("journalFingerprint")).equals(clean(decision.get("journalFingerprint"))))throw new IllegalArgumentException("Подтвердите именно подготовленный план отката PET");
    }
    private void ensureConfirmation(Context c,Map<String,Object> plan) throws Exception {
        Map<String,Object> current=map(c.xsd.get("rbuPetRollbackConfirmation"));
        if(clean(plan.get("journalFingerprint")).equals(clean(current.get("journalFingerprint")))&&clean(plan.get("targetAppealId")).equals(clean(current.get("targetAppealId"))))return;
        Map<String,Object> confirmation=obj("header","Подтверждение удаления импортированных данных","migrationNumber",plan.get("targetAppealNumber"),"sourceFileName",plan.get("sourceFileName"),"totalRecords",plan.get("totalRecords"),"deletableRecords",plan.get("deletableRecords"),"blockedRecords",plan.get("blockedRecords"),"alreadyDeletedRecords",plan.get("alreadyDeletedRecords"),"targetAppealId",plan.get("targetAppealId"),"journalFingerprint",plan.get("journalFingerprint"),"warningText","Удаление выполняется серверным обработчиком. Подтвердите выбранную миграцию.","confirmFinalRollback",false);
        Map<String,Object> xsd=copy(c.xsd);xsd.put("rbuPetRollbackConfirmation",confirmation);c.saveSub(obj("xsdData",xsd));
    }
    private void assess(Context c,String targetId,Map<String,Object> item,Map<String,Object> record,Map<String,Object> plan) throws Exception {
        if(record.isEmpty()){item.putAll(obj("state","ALREADY_DELETED","reason","Запись отсутствовала до подтверждённого удаления worker"));return;}
        boolean matches=clean(item.get("businessHash")).equals(businessHash(record));
        if(plan!=null)for(var patch:maps(plan.get("patches")))if(id(item).equals(id(patch))&&clean(item.get("collection")).equals(clean(patch.get("collection")))){
            Map<String,Object> undo=journal.read(undoKey(clean(plan.get("workerPlanKey")),patch));
            if("RESTORED".equals(undo.get("status")))matches=clean(patch.get("beforeHash")).equals(businessHash(record));
            else if("RESTORE_PENDING".equals(undo.get("status")))matches=matches||clean(patch.get("beforeHash")).equals(businessHash(record));
        }
        if(!id(item).equals(id(record))||!clean(item.get("guid")).equals(clean(record.get("guid")))||!clean(item.get("unitId")).equals(clean(at(record,"unit.id")))||!targetId.equals(at(record,"migrationSource.appealId"))||!PetImportOperations.WORKER_CONTRACT.equals(at(record,"migrationSource.workerContract"))||!item.get("operationKey").equals(at(record,"migrationSource.operationKey"))||bool(record.get("isDeleted"))||!matches){item.putAll(obj("state","BLOCKED","reason","Изменены GUID, владелец, источник или содержимое записи"));return;}
        item.putAll(obj("state","PENDING","reason","Ожидает выполнения удаления"));
    }
    private void markActive(Context c,List<Map<String,Object>> items) {
        var active=new PetRegistryAccess(c).search("myPetAppeals",Map.of(),10001);if(active.size()>=10001)throw new IllegalStateException("Не удалось полностью проверить активные дела PET");
        Map<String,List<Map<String,Object>>> tokens=new HashMap<>();for(var item:items)if(!ABSENT.contains(clean(item.get("state")))&&!"BLOCKED".equals(item.get("state")))for(Object token:List.of(id(item),item.get("guid")))tokens.computeIfAbsent(clean(token),value->new ArrayList<>()).add(item);
        for(var appeal:active){String status=clean(first(at(appeal,"status.code"),at(appeal,"appealStatus.code"),appeal.get("status")));if(finished(appeal,status))continue;Set<String> values=new HashSet<>();tokens(appeal,values,0);
            for(String value:values)for(var item:tokens.getOrDefault(value,List.of())){item.putAll(obj("state","BLOCKED","reason","Запись участвует в активном деле PET"));List<Object> refs=list(item.get("activeCases"));refs.add(obj("collection","myPetAppeals","appealId",id(appeal),"number",appeal.get("number"),"status",status));item.put("activeCases",refs);}
        }
    }
    private static boolean finished(Map<String,Object> appeal,String status) {
        return CaseFinality.isFinished(appeal,TERMINAL);
    }
    private static void tokens(Object value,Set<String> result,int depth) {if(depth>80)throw new IllegalStateException("Слишком глубокие ссылки активного дела");if(value instanceof Map)for(Object child:map(value).values())tokens(child,result,depth+1);else if(value instanceof List)for(Object child:list(value))tokens(child,result,depth+1);else if(value instanceof String)result.add(clean(value));}
    private void propagate(List<Map<String,Object>> items) {Set<String> blocked=new HashSet<>();for(var item:items)if("BLOCKED".equals(item.get("state")))blocked.add(clean(item.get("sourceKey")));for(var item:items)if(blocked.contains(clean(item.get("sourceKey")))&&!ABSENT.contains(clean(item.get("state")))&&!"BLOCKED".equals(item.get("state")))item.putAll(obj("state","BLOCKED","reason","Связана с заблокированной записью той же строки XLSX"));}
    private static int priority(String collection){return switch(collection){case "animalReleaseActRegistryEntry","animalTransferActRegistryCollection"->0;case "animalCatchActRegistryCollection"->1;case "myPetAnimalCardReestr","animalsRecordsCollectionTwo"->2;default->3;};}
    private static String deleteKey(String plan,Map<String,Object> item){return hash(obj("plan",plan,"kind","DELETE","collection",item.get("collection"),"id",id(item)));}
    private static String undoKey(String plan,Map<String,Object> patch){return hash(obj("plan",plan,"kind","RESTORE","patch",patch.get("operationKey")));}
    public static void counts(Map<String,Object> plan) {
        Map<String,Integer> counts=new HashMap<>();for(var item:maps(plan.get("items")))counts.merge(clean(item.get("state")),1,Integer::sum);
        int total=maps(plan.get("items")).size(),deleted=counts.getOrDefault("DELETED",0),already=counts.getOrDefault("ALREADY_DELETED",0),pending=counts.getOrDefault("PENDING",0),blocked=counts.getOrDefault("BLOCKED",0),errors=counts.getOrDefault("ERROR",0);
        int restored=list(plan.get("restoredPatchKeys")).size();
        plan.putAll(obj("totalRecords",total,"deletedRecords",deleted,"alreadyDeletedRecords",already,"absentRecords",deleted+already,"pendingRecords",pending,"deletableRecords",pending,"blockedRecords",blocked,"technicalErrors",errors,"notDeletedRecords",total-deleted-already,"processedRecords",total-pending,"restoredPatches",restored,"physicalWrites",deleted+restored,"totalBatches",(total+maps(plan.get("patches")).size()+49)/50));
    }
    private static void restored(Map<String,Object> plan,String key){List<Object> keys=list(plan.get("restoredPatchKeys"));if(!keys.contains(key))keys.add(key);plan.put("restoredPatchKeys",keys);}
    private static Map<String,Object> variables(Map<String,Object> plan,boolean more){return obj("rbuRollbackStatus",plan.get("status"),"rbuRollbackHasMore",more,"rbuPetRollbackHasMore",more,"rbuDeletedRecords",plan.get("deletedRecords"),"rbuAlreadyDeletedRecords",plan.get("alreadyDeletedRecords"),"rbuBlockedRecords",plan.get("blockedRecords"),"rbuPendingRecords",plan.get("pendingRecords"),"rbuTechnicalErrors",plan.get("technicalErrors"));}
    private void persist(Context c,String key,Map<String,Object> saved) throws Exception {
        Map<String,Object> plan=map(saved.get("plan"));counts(plan);String checkpoint=now();plan.putAll(obj("checkpointAt",checkpoint,"checkpointTimeDisplay",DISPLAY.format(Instant.parse(checkpoint))));
        List<Object> rows=new ArrayList<>();for(var item:maps(plan.get("items")))rows.add(obj("collection",item.get("collection"),"recordName",item.get("recordName"),"recordId",id(item),"state",item.get("state"),"status",stateName(clean(item.get("state"))),"reason",item.get("reason"),"activeCases",item.get("activeCases")));
        plan.put("rollbackItems",rows);journal.save(key,saved);lease.check();c.saveSub(obj("rbuPetRollbackPlan",plan));c.saveAppeal(obj("rollbackPreparationProgress",obj("status",plan.get("status"),"processedRecords",plan.get("processedRecords"),"totalRecords",plan.get("totalRecords"),"updatedAt",checkpoint,"updatedAtDisplay",plan.get("checkpointTimeDisplay"))));
    }
    private static String stateName(String state){return switch(state){case "PENDING"->"Ожидает удаления";case "DELETED"->"Удалена обработчиком";case "ALREADY_DELETED"->"Уже удалена";case "BLOCKED"->"Удаление заблокировано";case "ERROR"->"Техническая ошибка";default->throw new IllegalArgumentException("Неизвестное состояние отката PET");};}

    public Map<String,Object> finish(Context c) throws Exception {
        Map<String,Object> publicPlan=map(c.sub.get("rbuPetRollbackPlan"));String key=require(publicPlan,"workerPlanKey");Map<String,Object> saved=journal.read(key),plan=map(saved.get("plan"));
        if(!c.command.processId.equals(saved.get("processId"))||!identityHash(publicPlan).equals(saved.get("planHash"))||!"EXECUTED".equals(plan.get("status")))throw new IllegalStateException("Нет исполненного серверного плана отката PET");
        Map<String,Object> target=target(c,clean(plan.get("targetAppealId"))),sub=source(c,target,clean(plan.get("targetSubserviceGuid")));
        for(var item:maps(plan.get("items")))if(ABSENT.contains(clean(item.get("state")))&&!c.api.find(clean(item.get("collection")),id(item)).isEmpty())item.putAll(obj("state","BLOCKED","reason","Запись появилась повторно после исполнения плана"));
        persist(c,key,saved);counts(plan);
        String status=integer(plan.get("notDeletedRecords"))==0?"ROLLED_BACK":"PARTIAL",completed=now();List<Object> remaining=new ArrayList<>(),deletedKeys=new ArrayList<>(),alreadyDeletedKeys=new ArrayList<>();
        for(var item:maps(plan.get("items")))if("DELETED".equals(item.get("state")))deletedKeys.add(item.get("collection")+"/"+id(item));else if("ALREADY_DELETED".equals(item.get("state")))alreadyDeletedKeys.add(item.get("collection")+"/"+id(item));else remaining.add(obj("collection",item.get("collection"),"recordId",id(item),"state",item.get("state"),"reason",item.get("reason"),"activeCases",item.get("activeCases")));
        Map<String,Object> report=copy(plan);report.remove("patches");report.putAll(obj("version","rbu-pet-rollback-report-external-1.0","status",status,"completedAt",completed,"completedAtDisplay",DISPLAY.format(Instant.parse(completed)),"notDeletedItems",remaining));
        Map<String,Object> xsd=copy(c.xsd);xsd.put("rbuPetRollbackResult",report);c.saveSub(obj("rbuPetRollbackReport",report,"xsdData",xsd));
        Map<String,Object> marker=obj("status",status,"rollbackAppealId",c.command.mainId,"rollbackAppealNumber",c.appeal.get("number"),"attemptedAt",completed,"totalRecords",plan.get("totalRecords"),"deletedRecords",plan.get("deletedRecords"),"alreadyDeletedRecords",plan.get("alreadyDeletedRecords"),"notDeletedRecords",plan.get("notDeletedRecords"),"blockedRecords",plan.get("blockedRecords"),"technicalErrors",plan.get("technicalErrors"),"deletedRecordKeys",deletedKeys,"alreadyDeletedRecordKeys",alreadyDeletedKeys);if(status.equals("ROLLED_BACK"))marker.put("completedAt",completed);
        lease.check();c.api.updateSub(c.command.collection,id(target),clean(sub.get("guid")),clean(first(sub.get("parentEntries"),"rbuModulesAppeals.subservices")),obj("petMigrationRollback",marker));
        lease.check();c.api.update(c.command.collection,id(target),clean(target.get("guid")),obj("petMigrationRollback",marker,"rollbackStatusDisplay",status.equals("ROLLED_BACK")?"Миграция полностью откачена":"Миграция откачена частично","rollbackAppealNumber",c.appeal.get("number"),"rollbackDateDisplay",report.get("completedAtDisplay")));
        c.saveAppeal(obj("rollbackResultStatus",status,"rollbackTargetAppealId",id(target),"rollbackTargetAppealNumber",target.get("number")));return obj("rbuRollbackStatus",status);
    }
}
