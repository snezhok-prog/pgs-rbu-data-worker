package ru.evolenta.pgs.worker;
import java.util.Map;
/** Durable private checkpoints. A shared implementation must provide atomic updates and operation ownership/fencing; ordinary read-then-update is insufficient. */
public interface CheckpointStore {
 Map<String,Object> read(String key)throws Exception;
 void save(String key,Map<String,Object> state)throws Exception;
}
