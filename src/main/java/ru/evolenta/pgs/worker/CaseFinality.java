package ru.evolenta.pgs.worker;

import java.util.*;
import static ru.evolenta.pgs.worker.Util.*;

/** An unfinished child takes precedence over a stale final flag on the parent case. */
public final class CaseFinality {
    private CaseFinality() { }

    public static boolean isFinished(Map<String,Object> appeal, Set<String> terminalStatuses) {
        Map<String,Object> status = map(first(appeal.get("status"), appeal.get("appealStatus")));
        String code = clean(first(status.get("code"), appeal.get("status") instanceof String ? appeal.get("status") : null));
        boolean finalFlag = Boolean.TRUE.equals(status.get("isFinish"));
        if (!code.isEmpty() && !terminalStatuses.contains(code) || code.isEmpty() && !finalFlag) return false;
        List<Map<String,Object>> children = maps(appeal.get("subservices"));
        if (children.isEmpty()) return finalFlag;
        for (Map<String,Object> child : children) {
            Map<String,Object> childStatus = map(child.get("status"));
            String childCode = clean(childStatus.get("code"));
            if (!present(child.get("dateFinish")) || "ACTIVE".equals(at(child,"camundaProcessInfo.status"))) return false;
            if (!childCode.isEmpty() && !terminalStatuses.contains(childCode)) return false;
            if (childCode.isEmpty() && !Boolean.TRUE.equals(childStatus.get("isFinish"))) return false;
            if (Boolean.FALSE.equals(childStatus.get("isFinish"))) return false;
        }
        return true;
    }
}
