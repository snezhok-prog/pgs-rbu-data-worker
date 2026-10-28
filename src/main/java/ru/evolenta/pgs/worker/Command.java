package ru.evolenta.pgs.worker;
import java.util.*;
import static ru.evolenta.pgs.worker.Util.*;
public final class Command {
    public enum Domain {ADVERTISING,TRADE,PET}
    public enum Profile {ROIV,OMSU}
    public enum Operation {PARSE_VALIDATE,IMPORT_APPLY,IMPORT_FINALIZE,IMPORT_CANCEL,ROLLBACK_PREPARE,ROLLBACK_EXECUTE,ROLLBACK_FINALIZE,SINGLE_VALIDATE,SINGLE_APPLY,SINGLE_SYNC_REGISTRY}
    public final Domain domain;public final Profile profile;public final Operation operation;
    public final String taskId,processId,mainId,collection,businessInfoId,activityId;
    public Command(Map<String,Object> task){Map<String,Object> v=new LinkedHashMap<String,Object>();for(Map.Entry<String,Object> e:map(task.get("variables")).entrySet())v.put(e.getKey(),map(e.getValue()).get("value"));
        if(!"1".equals(require(v,"rbuContractVersion")))throw new IllegalArgumentException("Версия контракта не поддержана");
        domain=Domain.valueOf(require(v,"rbuDomain"));profile=Profile.valueOf(require(v,"rbuProfile"));operation=Operation.valueOf(require(v,"rbuOperation"));
        if(domain==Domain.PET)throw new IllegalArgumentException("Направление PET зарезервировано; обработчик питомца ещё не включён");
        taskId=require(task,"id");processId=require(task,"processInstanceId");activityId=require(task,"activityId");mainId=require(v,"mainId");businessInfoId=require(v,"camundaBusinessInfoId");collection=require(v,"collectionName").split("\\.")[0];
        if(!collection.equals("rbuModulesAppeals"))throw new IllegalArgumentException("Недопустимая коллекция дела");
    }
    public String key(String revision){return hash(obj("process",processId,"appeal",mainId,"activity",activityId,"operation",operation.name(),"revision",revision));}
}
