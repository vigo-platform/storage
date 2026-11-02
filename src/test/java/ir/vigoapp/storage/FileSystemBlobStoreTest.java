package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FileSystemBlobStoreTest {
    @TempDir Path root;
    @Test void roundTripIsImmutableAndCountsActualBytes() throws Exception {
        var store=new FileSystemBlobStore(root.toString());byte[] bytes="برنامه exercise".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var staged=store.put(new ByteArrayInputStream(bytes));assertThat(staged.size()).isEqualTo(bytes.length);
        assertThat(staged.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(store.exists(staged.key())).isFalse();store.promote(staged);
        assertThat(store.exists(staged.key())).isTrue();assertThat(store.size(staged.key())).isEqualTo(bytes.length);
        try(var in=store.open(staged.key())){assertThat(in.readAllBytes()).isEqualTo(bytes);}
        assertThatThrownBy(()->store.promote(staged)).isInstanceOf(IOException.class);
    }
    @Test void traversalAndForeignKeysAreRejected() throws Exception {
        var store=new FileSystemBlobStore(root.toString());
        for(String key:new String[]{"../secret","/etc/passwd","a\\b","file.txt","","A".repeat(32)})assertThatThrownBy(()->store.open(key)).isInstanceOf(IOException.class);
    }
    @Test void actualStreamingLimitRejectsOversizeAndCleansOnlyStage() throws Exception {
        var store=new FileSystemBlobStore(root.toString());
        InputStream oversized=new InputStream(){long n;public int read(){return n++<=FileSystemBlobStore.MAX_BYTES?65:-1;}};
        assertThatThrownBy(()->store.put(oversized)).isInstanceOfSatisfying(Api.Fault.class,e->assertThat(e.code).isEqualTo("FILE_TOO_LARGE"));
        try(var paths=Files.list(root.resolve("staged"))){assertThat(paths.count()).isZero();}
    }
    @Test void emptyContentRejected() throws Exception {
        var store=new FileSystemBlobStore(root.toString());assertThatThrownBy(()->store.put(InputStream.nullInputStream())).isInstanceOf(Api.Fault.class);
    }
    @Test void symbolicLinkRootIsRejectedWhenSupported() throws Exception {
        Path target=Files.createDirectory(root.resolve("target")),link=root.resolve("link");
        try{Files.createSymbolicLink(link,target);}catch(IOException|UnsupportedOperationException e){Assumptions.abort("Symlink privilege unavailable");}
        assertThatThrownBy(()->new FileSystemBlobStore(link.toString())).isInstanceOf(IOException.class);
    }
    @Test void compensationCannotTouchForeignStage()throws Exception{
        var store=new FileSystemBlobStore(root.toString());var staged=store.put(new ByteArrayInputStream(new byte[]{1}));
        var forged=new BlobStore.Staged(staged.key(),root.resolve("foreign"),1,"");
        assertThatThrownBy(()->store.discardStage(forged)).isInstanceOf(IOException.class);assertThat(Files.exists(staged.path())).isTrue();
    }
}
