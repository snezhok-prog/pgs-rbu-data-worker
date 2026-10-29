package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.Util.*;
import static ru.evolenta.pgs.worker.pet.PetRecordHash.businessHash;
import java.util.*;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.trade.core.Data;

/** Durable, identity-checked CREATE and reversible PATCH receipts for the PET import. */
public final class PetMutationStore {
    private final PgsApi api;
    private final CheckpointStore journal;
    private final Operations.Lease lease;
    private final String appealId, ownerId;

    public PetMutationStore(PgsApi api, CheckpointStore journal, Operations.Lease lease, String appealId, String ownerId) {
        this.api=api;this.journal=journal;this.lease=lease;this.appealId=appealId;this.ownerId=ownerId;
    }

    public Map<String,Object> create(String collection,String operationKey,Map<String,Object> payload) throws Exception {
        allowed(collection); String expectedHash=businessHash(payload); Map<String,Object> saved=journal.read(operationKey);
        if (!saved.isEmpty() && !expectedHash.equals(clean(first(saved.get("createdHash"),saved.get("businessHash")))))
            throw new IllegalStateException("CREATE intent относится к другому содержимому записи");
        lease.check(); List<Map<String,Object>> matches=api.search(collection,List.of(Context.filter("migrationSource.operationKey",operationKey)),0,2);
        if (matches.size()>1) throw new IllegalStateException("Найдено несколько записей operationKey; требуется разбор");
        Map<String,Object> record;
        if (matches.size()==1) {
            if(saved.isEmpty()) throw new IllegalStateException("Найдена запись без доверенного CREATE intent");
            record=matches.getFirst();
        } else {
            if(!saved.isEmpty()) throw new IllegalStateException("Результат CREATE не определён; повторный CREATE запрещён до сверки");
            journal.save(operationKey,obj("status","CREATE_PENDING","collection",collection,"guid",payload.get("guid"),"operationKey",operationKey,"createdHash",expectedHash,"businessHash",expectedHash,"startedAt",now()));
            lease.check(); Map<String,Object> created=api.create(collection,payload);
            if(id(created).isEmpty())throw new IllegalStateException("CREATE не вернул ID; intent сохранён");
            record=api.find(collection,id(created));
        }
        String currentExpected=clean(first(saved.get("businessHash"),expectedHash));
        if(record.isEmpty()||!clean(payload.get("guid")).equals(clean(record.get("guid")))
                ||!operationKey.equals(clean(at(record,"migrationSource.operationKey")))
                ||!clean(at(payload,"migrationSource.sourceFileSha256")).equals(clean(at(record,"migrationSource.sourceFileSha256")))
                ||!currentExpected.equals(businessHash(record)))throw new IllegalStateException("Конфликт GUID, источника или содержимого CREATE");
        identity(record);
        Map<String,Object> receipt=obj("status","COMPLETED","collection",collection,"_id",id(record),"guid",record.get("guid"),"unitId",ownerId,
                "operationKey",operationKey,"createdHash",expectedHash,"businessHash",currentExpected);
        journal.save(operationKey,receipt);return receipt;
    }

    public Map<String,Object> patch(String operationKey,Map<String,Object> target,Map<String,Object> patch) throws Exception {
        String collection=require(target,"collection");allowed(collection);lease.check();
        Map<String,Object> saved=journal.read(operationKey),record=api.find(collection,id(target));
        identity(record);requireGuid(target,record);
        if(saved.isEmpty()) {
            if(!clean(target.get("businessHash")).equals(businessHash(record)))throw new IllegalStateException("Запись изменилась перед PATCH");
            for(String field:patch.keySet())if(!record.containsKey(field))throw new IllegalArgumentException("Невозможно гарантировать восстановление отсутствующего поля PATCH: "+field);
            Map<String,Object> after=Data.merge(record,patch),beforeFields=obj(),afterFields=obj();
            for(String field:patch.keySet()){beforeFields.put(field,record.get(field));afterFields.put(field,after.get(field));}
            saved=obj("status","PATCH_PENDING","collection",collection,"_id",id(record),"guid",record.get("guid"),"unitId",ownerId,"operationKey",operationKey,
                    "targetCreateKey",target.get("operationKey"),"patchHash",hash(patch),"beforeHash",businessHash(record),"afterHash",businessHash(after),"beforeFields",beforeFields,"afterFields",afterFields,"startedAt",now());
            journal.save(operationKey,saved);
        }
        if(!hash(patch).equals(saved.get("patchHash"))||!id(target).equals(id(saved))||!collection.equals(saved.get("collection")))throw new IllegalStateException("PATCH intent изменён");
        return writePatch(operationKey,saved,false);
    }

    public Map<String,Object> undoPatch(String operationKey,Map<String,Object> patchReceipt) throws Exception {
        if(!"PATCHED".equals(patchReceipt.get("status")))throw new IllegalArgumentException("Нет подтверждённого PATCH для восстановления");
        Map<String,Object> saved=journal.read(operationKey);
        if(saved.isEmpty()) {
            saved=copy(patchReceipt);saved.putAll(obj("status","RESTORE_PENDING","originalPatchKey",patchReceipt.get("operationKey"),"operationKey",operationKey));
            journal.save(operationKey,saved);
        } else if(!clean(patchReceipt.get("operationKey")).equals(clean(saved.get("originalPatchKey")))||!clean(patchReceipt.get("beforeHash")).equals(saved.get("beforeHash"))||!clean(patchReceipt.get("afterHash")).equals(saved.get("afterHash")))
            throw new IllegalStateException("Снимок обратного PATCH изменён");
        return writePatch(operationKey,saved,true);
    }

    private Map<String,Object> writePatch(String operationKey,Map<String,Object> saved,boolean undo) throws Exception {
        String collection=require(saved,"collection");allowed(collection);lease.check();
        Map<String,Object> record=api.find(collection,id(saved));identity(record);requireGuid(saved,record);
        String before=clean(saved.get(undo?"afterHash":"beforeHash")),after=clean(saved.get(undo?"beforeHash":"afterHash"));
        String current=businessHash(record),complete=undo?"RESTORED":"PATCHED";
        if(!after.equals(current)) {
            if(!before.equals(current)||complete.equals(saved.get("status")))throw new IllegalStateException("Запись изменена после PATCH; автоматическое восстановление запрещено");
            lease.check();api.update(collection,id(record),clean(record.get("guid")),map(saved.get(undo?"beforeFields":"afterFields")));
            lease.check();record=api.find(collection,id(record));identity(record);requireGuid(saved,record);
            if(!after.equals(businessHash(record)))throw new IllegalStateException("Результат PATCH не подтверждён; intent сохранён");
        }
        saved.putAll(obj("status",complete,"completedAt",now()));journal.save(operationKey,saved);
        String targetKey=clean(saved.get("targetCreateKey"));Map<String,Object> target=journal.read(targetKey);
        if(!"COMPLETED".equals(target.get("status"))||!id(saved).equals(id(target)))throw new IllegalStateException("Потерян CREATE receipt изменяемой записи");
        target.put("businessHash",after);journal.save(targetKey,target);return saved;
    }

    private void identity(Map<String,Object> record) {
        if(id(record).isEmpty()||!ownerId.equals(clean(at(record,"unit.id")))||!appealId.equals(clean(at(record,"migrationSource.appealId")))
                ||!"pgs-pet-external-v1".equals(at(record,"migrationSource.workerContract")))throw new IllegalStateException("Запись PET не принадлежит выбранной миграции и организации");
    }
    private static void requireGuid(Map<String,Object> expected,Map<String,Object> record) {
        if(clean(expected.get("guid")).isEmpty()||!clean(expected.get("guid")).equals(clean(record.get("guid"))))throw new IllegalStateException("GUID записи изменён");
    }
    private static void allowed(String collection) { if(!PetModule.COLLECTIONS.contains(collection))throw new IllegalArgumentException("Недопустимая коллекция PET"); }
}
