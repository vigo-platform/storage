package ir.vigoapp.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

interface BlobStore {
    Staged put(InputStream input) throws IOException;
    InputStream open(String key) throws IOException;
    boolean exists(String key) throws IOException;
    long size(String key) throws IOException;
    record Staged(String key,Path path,long size,String sha256) {}
}

@Component
final class FileSystemBlobStore implements BlobStore {
    static final long MAX_BYTES=20L*1024*1024;
    private final Path root,stage,objects;
    FileSystemBlobStore(@Value("${storage.root}") String configured) throws IOException {
        root=Path.of(configured).toAbsolutePath().normalize();
        checkAncestors(root);
        Files.createDirectories(root); checkAncestors(root);
        directory(root);
        stage=directory(root.resolve("staged")); objects=directory(root.resolve("objects"));
        if(!Files.isWritable(root)) throw new IOException("Storage root must be writable by the service user");
    }
    private static Path directory(Path path) throws IOException {
        Files.createDirectories(path); checkAncestors(path);
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------")); }
        catch(UnsupportedOperationException ignored) { /* Windows ACLs are an operator responsibility. */ }
        return path;
    }
    static void checkAncestors(Path path) throws IOException {
        for(Path p=path;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p)) throw new IOException("Symbolic link in storage path");
    }
    private Path resolve(Path dir,String key) throws IOException {
        if(key==null || !key.matches("[a-f0-9]{32}")) throw new IOException("Invalid opaque blob key");
        checkAncestors(dir); Path path=dir.resolve(key).normalize();
        if(!path.getParent().equals(dir)||!path.startsWith(root)||Files.isSymbolicLink(path)) throw new IOException("Unsafe blob path");
        return path;
    }
    public Staged put(InputStream input) throws IOException {
        String key=UUID.randomUUID().toString().replace("-",""); Path path=resolve(stage,key);
        long size=0; MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try(FileChannel out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[16384]; int count;
            while((count=input.read(buffer))!=-1) {
                size+=count; if(size>MAX_BYTES)throw Api.fail(413,"FILE_TOO_LARGE");
                digest.update(buffer,0,count); ByteBuffer bytes=ByteBuffer.wrap(buffer,0,count);
                while(bytes.hasRemaining())out.write(bytes);
            }
            out.force(true);
        } catch(IOException|RuntimeException e) { Files.deleteIfExists(path); throw e; }
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); }
        catch(UnsupportedOperationException ignored) {}
        if(size==0){Files.deleteIfExists(path);throw Api.fail(400,"FILE_CONTENT_MISMATCH");}
        return new Staged(key,path,size,HexFormat.of().formatHex(digest.digest()));
    }
    void promote(Staged staged) throws IOException {
        Path source=resolve(stage,staged.key()),target=resolve(objects,staged.key());
        if(!source.equals(staged.path()))throw new IOException("Foreign stage");
        // Atomic no-clobber publication on one filesystem. ATOMIC_MOVE alone may replace on Unix.
        Files.createLink(target,source);
        Files.delete(source);
        try(FileChannel dir=FileChannel.open(objects,StandardOpenOption.READ)){dir.force(true);}
        catch(java.nio.file.AccessDeniedException|UnsupportedOperationException ignored){ /* Directory fsync unavailable on Windows. */ }
    }
    void discardStage(Staged staged) throws IOException {
        Path path=resolve(stage,staged.key()); if(!path.equals(staged.path()))throw new IOException("Foreign stage"); Files.deleteIfExists(path);
    }
    // Package-private compensation only. Caller must prove this operation's new blob has no DB reference.
    void discardUnreferenced(Staged staged) throws IOException { Files.deleteIfExists(resolve(objects,staged.key())); }
    public InputStream open(String key) throws IOException { return Files.newInputStream(resolve(objects,key),LinkOption.NOFOLLOW_LINKS); }
    public boolean exists(String key) throws IOException { return Files.isRegularFile(resolve(objects,key),LinkOption.NOFOLLOW_LINKS); }
    public long size(String key) throws IOException {
        Path path=resolve(objects,key); if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException(); return Files.size(path);
    }
}
