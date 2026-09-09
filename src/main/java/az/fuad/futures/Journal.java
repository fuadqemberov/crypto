package az.fuad.futures;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;
import static az.fuad.futures.Models.*;

/** A forced append-only event journal is the source of truth, including account snapshots. */
public final class Journal implements AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();
    private final FileChannel channel,lockChannel;
    private final FileLock lock;
    private final Path path;
    public Journal(Path directory) throws IOException {
        Files.createDirectories(directory);
        lockChannel=FileChannel.open(directory.resolve("account.lock"),CREATE,WRITE);
        FileLock acquired;
        try { acquired=lockChannel.tryLock(); } catch(RuntimeException e) { lockChannel.close(); throw e; }
        if(acquired==null) { lockChannel.close(); throw new IOException("Another bot owns this data directory"); }
        lock=acquired;
        path=directory.resolve("order_history.txt");
        channel=FileChannel.open(path,CREATE,READ,WRITE);
    }
    public Account load(double initialBalance) throws IOException {
        Account current=new Account(); current.cash=initialBalance;
        long validEnd=0;
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")) {
            while(file.getFilePointer()<file.length()) {
                long start=file.getFilePointer(); String line=file.readLine(); long end=file.getFilePointer();
                file.seek(end-1); boolean complete=file.read()==10; file.seek(end);
                if(!complete) {
                    // Preserve a crash-torn tail for inspection before discarding only that tail.
                    file.seek(start); byte[] tail=new byte[(int)(file.length()-start)]; file.readFully(tail);
                    Files.write(path.resolveSibling("torn-tail-"+System.currentTimeMillis()+".bin"),tail,CREATE_NEW);
                    channel.truncate(validEnd); channel.force(true); break;
                }
                try {
                    Event event=json.readValue(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8),Event.class);
                    if(event.sequence()!=current.sequence+1 || event.account()==null || event.account().sequence!=event.sequence()
                            || !Double.isFinite(event.account().cash) || event.account().positions==null)
                        throw new IOException("Invalid event sequence/account");
                    current=event.account(); validEnd=end;
                } catch(Exception ex) { throw new IOException("Corrupt journal at byte "+start+"; refusing to reset balance",ex); }
            }
        }
        channel.position(channel.size()); return current;
    }
    public void append(String type,String detail,Account account) throws IOException {
        byte[] bytes=(json.writeValueAsString(new Event(account.sequence,System.currentTimeMillis(),type,detail,account))+"\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
    }
    public Account copy(Account a) { return json.convertValue(a,Account.class); }
    @Override public void close() throws IOException { channel.close(); lock.release(); lockChannel.close(); }
}
