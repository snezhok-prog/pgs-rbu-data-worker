package ru.evolenta.pgs.worker;
import java.io.*;import java.nio.channels.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import static ru.evolenta.pgs.worker.Util.*;
/** Durable local checkpoints. Shared directory and OS lock enforce ONE active worker. */
public final class Journal implements CheckpointStore,AutoCloseable {
    private final Path root;private final FileChannel channel;private final FileLock lock;
    public Journal(Path root)throws Exception{this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);channel=FileChannel.open(this.root.resolve("worker.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=channel.tryLock();if(lock==null){channel.close();throw new IllegalStateException("Другой worker использует этот каталог состояния");}}
    private Path file(String key){if(!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid journal key");return root.resolve(key+".json");}
    public Map<String,Object> read(String key)throws Exception{Path p=file(key);return Files.exists(p)?map(Json.parse(new String(Files.readAllBytes(p),StandardCharsets.UTF_8))):obj();}
    public void save(String key,Map<String,Object> state)throws Exception{Path target=file(key),tmp=Files.createTempFile(root,"checkpoint-",".tmp");try{byte[] data=Json.write(state).getBytes(StandardCharsets.UTF_8);try(FileChannel out=FileChannel.open(tmp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(data);while(b.hasRemaining())out.write(b);out.force(true);}try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){throw new IOException("Journal requires atomic rename",e);}}finally{Files.deleteIfExists(tmp);}}
    public void close()throws Exception{lock.release();channel.close();}
}
