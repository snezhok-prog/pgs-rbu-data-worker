package ru.evolenta.pgs.worker.integration;

import java.util.Map;
import java.util.LinkedHashMap;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.server.dto.ValueObjectDTO;
import ru.evolenta.server.dto.request.CompleteExternalTaskReqDTO;
import ru.evolenta.server.dto.response.ExternalTaskFetchAndLockRespDTO;

/** Copy into the supplied Spring project alongside ru.evolenta.pgs.worker.*. */
public final class RepositoryTaskAdapter {
    private final PgsApi api;
    private final CheckpointStore journal;
    private final boolean writes;
    private final String uiBaseUrl;
    public RepositoryTaskAdapter(PgsApi api, CheckpointStore journal, boolean writes, String uiBaseUrl) {
        this.api=api; this.journal=journal; this.writes=writes; this.uiBaseUrl=uiBaseUrl;
    }
    public CompleteExternalTaskReqDTO execute(ExternalTaskFetchAndLockRespDTO task,
                                              Operations.Lease lease) throws Exception {
        Map<String,Object> variables=new LinkedHashMap<String,Object>();
        for(Map.Entry<String,ValueObjectDTO> e:task.getVariables().entrySet()) {
            variables.put(e.getKey(),Util.obj("type",e.getValue().getType(),"value",e.getValue().getValue()));
        }
        Command command=new Command(Util.obj("id",task.getId(),"processInstanceId",task.getProcessInstanceId(),
                "activityId",task.getActivityId(),"variables",variables));
        Context context=new Context(command,api);context.setLease(lease);
        Map<String,Object> result=new Operations(journal,lease,writes,uiBaseUrl).execute(context);
        lease.check();
        Map<String,ValueObjectDTO> output=new LinkedHashMap<String,ValueObjectDTO>();
        for(Map.Entry<String,Object> e:result.entrySet()) {
            Object value=e.getValue();
            String type=value instanceof Boolean?"Boolean":value instanceof Integer?"Integer":value instanceof Number?"Double":"String";
            output.put(e.getKey(),new ValueObjectDTO(type,value,null));
        }
        CompleteExternalTaskReqDTO complete=new CompleteExternalTaskReqDTO();
        complete.setWorkerId(task.getWorkerId());
        complete.setVariables(output);
        return complete;
    }
}
