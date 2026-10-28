package ru.evolenta.pgs.worker.trade;

import java.io.ByteArrayInputStream;
import java.util.*;
import ru.evolenta.pgs.worker.Context;
import ru.evolenta.pgs.worker.Util;
import ru.evolenta.pgs.worker.trade.core.*;

/** Adapter between the shared worker contract and the audited trade domain. */
public final class TradeDomain {
    public static final String VALIDATION_VERSION="rbu-trade-validation-4.3";
    private final Context worker;
    private final RegistryAccess registry;
    public TradeDomain(Context worker){this.worker=worker;this.registry=new RegistryAccess(){
        public Optional<Map<String,Object>> findById(String collection,String id){try{return Optional.ofNullable(worker.api.find(collection,id)).filter(x->!x.isEmpty());}catch(Exception e){throw new IllegalStateException("Не удалось прочитать "+collection+"/"+id,e);}}
        public List<Map<String,Object>> search(String collection,Map<String,Object> filter,int limit){
            if(limit<1||limit>100000)throw new IllegalArgumentException("Некорректный предел поиска");
            List<Map<String,Object>> all=new ArrayList<>();Set<String> seen=new HashSet<>();
            try{for(int page=0;page<1000;page++){
                List<Map<String,Object>> rows=worker.api.search(collection,Util.list(Util.at(filter,"search.search")),page,Math.min(100,limit));
                if(rows.isEmpty())return all;
                for(Map<String,Object> row:rows){String id=Util.id(row);if(id.isEmpty()||!seen.add(id))throw new IllegalStateException("Поиск вернул запись без ID или повтор страницы: "+collection);all.add(row);if(all.size()==limit)return all;}
            }}catch(Exception e){throw new IllegalStateException("Не удалось полностью проверить "+collection,e);}
            throw new IllegalStateException("Превышен предел страниц поиска: "+collection);
        }
    };}
    public Map<String,Object> trustedContext(){return trustedContext(false);}
    public Map<String,Object> trustedContext(boolean requireMigration){TradeContext t=TradeContext.load(operationContext(Util.now(),requireMigration),registry);return Util.obj("organization",t.organization(),"organizationCard",t.organizationCard(),"regionRecord",t.passport(),"municipalityRecord",t.municipality(),"region",t.regionName(),"municipality",t.municipalityName(),"organizationCardId",Util.id(t.organizationCard()),"regionId",Util.id(t.passport()),"municipalityId",Util.id(t.municipality()),"dataType",selectedType());}
    public String selectedType(){Object selected=Util.first(worker.xsd.get("dataType"),worker.appeal.get("dataType"),Util.at(worker.sub,"mainElement.xsdData.dataType"));String value=selected instanceof Map?Util.clean(Util.first(Util.map(selected).get("name"),Util.map(selected).get("code"),Util.map(selected).get("value"))):Util.clean(selected);return switch(Util.key(value)){case "нто","нестационарные торговые объекты"->"НТО";case "рынок","рынки","розничные рынки"->"Рынки";case "ярмарка","ярмарки"->"Ярмарки";default->throw new IllegalArgumentException("Неподдерживаемый вид данных: "+value);};}
    private OperationContext operationContext(String time,boolean requireMigration){
        String owner=Util.clean(Util.first(worker.appeal.get("unitId"),Util.at(worker.appeal,"unit.id")));String nestedOwner=Util.clean(Util.at(worker.appeal,"unit.id"));
        if(owner.isEmpty()||!nestedOwner.isEmpty()&&!owner.equals(nestedOwner))throw new IllegalArgumentException("Не определён однозначный владелец дела");
        Map<String,Object> coordinator=live("organizations",owner),card=live("rbuRegionOrganizations",Util.id(worker.selection("organization")));
        Map<String,Object> passport=live("rbuRegionPassports",Util.id(worker.selection("region"))),municipality=live("rbuRegionMunicipalities",Util.id(worker.selection("municipality")));
        String orgId=Util.clean(card.get("organizationId"));if(orgId.isEmpty())throw new IllegalArgumentException("Карточка торговли не связана с кабинетом ПГС");Map<String,Object> organization=live("organizations",orgId);
        String scope=card.get("registryScope") instanceof Map?Util.clean(Util.first(Util.at(card,"registryScope.code"),Util.at(card,"registryScope.value"))):Util.clean(card.get("registryScope"));
        if(!"TRADE".equals(scope)||!Util.id(municipality).equals(Util.clean(card.get("municipalityRecordId")))||!Util.id(passport).equals(Util.clean(municipality.get("passportId")))||Util.clean(municipality.get("municipalityId")).isEmpty()||!Util.clean(municipality.get("municipalityId")).equals(Util.clean(card.get("municipalityId"))))throw new IllegalArgumentException("Нарушены связи субъекта, МО и организации торговли");
        String guid=Util.clean(card.get("organizationGuid"));if(guid.isEmpty()||!guid.equals(Util.clean(organization.get("guid"))))throw new IllegalArgumentException("GUID карточки не совпадает с актуальным кабинетом организации");
        String region=Util.clean(passport.get("regionCode"));Set<String> allowed=new HashSet<>();for(Object value:Arrays.asList(coordinator.get("regions"),coordinator.get("region"))){if(value instanceof List)for(Map<String,Object> item:Util.maps(value))allowed.add(Util.clean(item.get("code")));else if(value instanceof Map)allowed.add(Util.clean(Util.map(value).get("code")));else if(value!=null)throw new IllegalArgumentException("Некорректный формат региона организации владельца дела");}allowed.add(Util.clean(coordinator.get("regionCode")));allowed.remove("");
        if(region.isEmpty()||!allowed.contains(region))throw new IllegalArgumentException("Субъект РФ не входит в область полномочий организации владельца дела");
        if(worker.command.profile==ru.evolenta.pgs.worker.Command.Profile.OMSU){if(!owner.equals(orgId))throw new IllegalArgumentException("ОМСУ может выполнять операцию только для своей организации");if(requireMigration&&!Boolean.TRUE.equals(card.get("isMigration")))throw new IllegalArgumentException("Организации не предоставлено право самостоятельного импорта");}
        List<Map<String,Object>> files=new ArrayList<>();for(Map<String,Object> group:Util.maps(worker.appeal.get("documents"))){String name=Util.key(group.get("groupName"));if(!name.equals("файлы для загрузки в реестр")&&!name.equals("файлы миграции")&&!"296c71e2-580e-4ead-a7e9-b789f3aa4aeb".equals(group.get("groupGuid")))continue;files.addAll(Util.maps(group.get("files")));for(Map<String,Object> doc:Util.maps(group.get("documents")))files.addAll(Util.maps(doc.get("files")));}
        Map<String,Object> attrs=Util.obj("organization",card,"region",passport,"municipality",municipality,"documentFiles",files,"coordinatorUnitId",owner,"validationOnly",!requireMigration,"appealNumber",worker.appeal.get("number"),"operationTime",time);
        String actor=Util.clean(Util.first(Util.at(worker.appeal,"userLastModification._id"),Util.at(worker.appeal,"userCreation._id"),Util.at(worker.appeal,"userCreation.id"),"external-worker"));
        return new OperationContext(worker.command.processId,worker.command.mainId,orgId,actor,worker.command.profile.name(),attrs);
    }
    private Map<String,Object> live(String collection,String id){if(id==null||id.isEmpty())throw new IllegalArgumentException("Не заполнен идентификатор "+collection);return TradeContext.requireLive(registry.findById(collection,id).orElseThrow(()->new IllegalArgumentException("Не найдена запись "+collection+"/"+id)),id);}
    public Map<String,Object> parse(byte[] bytes,Map<String,Object> file)throws Exception{
        String type=selectedType();
        Map<String,Object> metadata=new LinkedHashMap<>(file);metadata.put("id",Util.first(file.get("_id"),file.get("id"),file.get("fileId")));
        WorkbookDraft typed;try{typed=new TradeXlsxParser().read(new ByteArrayInputStream(bytes),type,metadata);}catch(java.io.IOException e){throw new IllegalArgumentException("Не удалось прочитать XLSX: "+e.getMessage(),e);}
        Map<String,Object> draft=Util.copy(typed.toMap());draft.put("context",trustedContext());
        for(Map<String,Object> sheet:Util.maps(draft.get("sheets"))){sheet.put("sheetName",Util.at(draft,"sheetNames."+sheet.get("code")));for(Map<String,Object> row:Util.maps(sheet.get("rows")))row.put("rowNumber",row.get("excelRow"));}
        return draft;
    }
    public Map<String,Object> validate(Map<String,Object> draft){
        WorkbookDraft typed=WorkbookDraft.from(draft);ValidationReport report=new TradeModule().validate(typed,operationContext(Util.now(),false),registry);
        List<Object> errors=new ArrayList<>();Set<String> badRows=new HashSet<>();boolean structural=false;
        for(ValidationIssue issue:report.issues()){
            if(errors.size()>=30000)throw new IllegalArgumentException("Слишком много ошибок; разделите файл");
            String field="";for(var sheet:typed.sheets())if(sheet.code().equals(issue.sheet()))for(var column:sheet.columns())if(column.column().equals(issue.column()))field=Util.clean(column.metadata().get("fieldName"));
            String category=category(issue.code());errors.add(Util.obj("errorId",UUID.randomUUID().toString(),"sheetCode",issue.sheet(),"sheetName",Util.at(draft,"sheetNames."+issue.sheet()),"rowNumber",issue.row(),"column",issue.column(),"fieldName",field,"errorCode",issue.code(),"errorType",category,"errorTypeCode",category,"message",issue.message()));
            if(issue.row()<6||issue.sheet().equals("title"))structural=true;else badRows.add(issue.sheet()+":"+issue.row());
        }
        int total=0,valid=0;List<Object> summary=new ArrayList<>();
        for(Map<String,Object> sheet:Util.maps(draft.get("sheets"))){int count=0;for(Map<String,Object> row:Util.maps(sheet.get("rows"))){total++;boolean ok=!structural&&!badRows.contains(sheet.get("code")+":"+row.get("rowNumber"));row.put("validationStatus",ok?"VALID":"INVALID");if(ok){valid++;count++;}}int n=Util.maps(sheet.get("rows")).size();summary.add(Util.obj("sheetName",sheet.get("sheetName"),"totalRows",n,"validRows",count,"invalidRows",n-count));}
        String time=Util.now();long records=new TradeModule().recordCount(typed);Map<String,Object> counts=Util.obj("format",0,"logical",0,"required",0,"duplicate",0,"reference",0);
        for(Object e:errors){String name=Util.clean(Util.map(e).get("errorType"));counts.put(name,Util.integer(counts.get(name))+1);}
        draft.putAll(Util.obj("status","VALIDATED","validationVersion",VALIDATION_VERSION,"validationBlocked",!report.valid(),"validatedFileSha256",typed.fileSha256(),"validatedAt",time,"totalRows",total,"validRows",valid,"invalidRows",total-valid,"estimatedRecords",records,"maxImportRecords",2500));
        return Util.obj("processingStatus",report.valid()?"Валидация завершена":"Валидация завершена с ошибками","processedAt",time,"fileName",Util.at(draft,"file.originalName"),"totalRows",total,"validRows",valid,"invalidRows",total-valid,"errors",errors,"validationErrors",errors,"totalErrors",errors.size(),"validationBlocked",!report.valid(),"validationVersion",VALIDATION_VERSION,"estimatedRecords",records,"maxImportRecords",2500,"validationSummary",summary,"errorSummary",counts,"confirmApply",false,"migrationLog",List.of(Util.obj("time",time,"stage","VALIDATION_END","level",report.valid()?"INFO":"WARN","message","Всего="+total+", корректных="+valid+", ошибок="+errors.size())));
    }
    public List<PlannedEntity> plan(Map<String,Object> draft,String stableTime){return new TradeModule().plan(WorkbookDraft.from(draft),operationContext(stableTime,true),registry);}
    private static String category(String code){if(code.contains("REQUIRED"))return "required";if(code.contains("EXISTING")||code.contains("DUPLICATE")||code.contains("UNIQUE"))return "duplicate";if(code.contains("REFERENCE"))return "reference";if(code.startsWith("IS_")||code.startsWith("PHOTO_")||code.contains("STRUCTURE")||code.contains("COLUMN"))return "format";return "logical";}
}
