package ru.evolenta.pgs.worker;

import java.util.Map;
import static ru.evolenta.pgs.worker.Util.*;

/** A process-level start marker survives replacement of the public file/draft fields. */
public final class ImportLifecycle {
    private ImportLifecycle() { }
    private static String key(Context context) {
        return hash(obj("process",context.command.processId,"appeal",context.command.mainId,"operation","IMPORT_STARTED"));
    }
    public static void markStarted(Context context, CheckpointStore journal, Operations.Lease lease) throws Exception {
        String key=key(context);Map<String,Object> prior=journal.read(key);
        if (!prior.isEmpty()) return;
        lease.check();
        journal.save(key,obj("status","STARTED","processId",context.command.processId,
                "appealId",context.command.mainId,"domain",context.command.domain.name(),"startedAt",now()));
    }
    public static boolean hasStarted(Context context, CheckpointStore journal) throws Exception {
        return !journal.read(key(context)).isEmpty();
    }
}
