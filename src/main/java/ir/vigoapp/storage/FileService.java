package ir.vigoapp.storage;

import java.io.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class FileService {
    private final FileRepository files;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FileSystemBlobStore blobs;
    private final FileValidator validator;
    private final StorageOutbox outbox;
    FileService(FileRepository files,JdbcTemplate db,TransactionTemplate tx,FileSystemBlobStore blobs,FileValidator validator,StorageOutbox outbox) {
        this.files=files;this.db=db;this.tx=tx;this.blobs=blobs;this.validator=validator;this.outbox=outbox;
    }
    Map<String,Object> upload(Policy.Actor actor,UUID athlete,Policy.Category category,String title,String description,MultipartFile input) throws IOException {
        Policy.upload(actor,actor.id(),athlete); text(title,2,100);optional(description,500);
        // Reject unauthorized uploads before consuming/storing their bytes; recheck in the metadata transaction.
        active(actor.id(),athlete);
        return withBlob(input,(staged,valid)->tx.execute(status->{
            active(actor.id(),athlete);
            var f=new StoredFile(actor.id(),athlete,category.name(),title,description);
            files.saveAndFlush(f); addVersion(f,staged,valid,null,actor.id());
            Map<String,Object> metadata=metadata(f); outbox.append(f,"Uploaded",uploadEvent(metadata,version(f.id,1)),1);
            return metadata;
        }));
    }
    Map<String,Object> add(Policy.Actor actor,UUID id,MultipartFile input,String note) throws IOException {
        optional(note,300);
        tx.executeWithoutResult(status->{var f=resolve(actor,id,false);Policy.upload(actor,f.coach,f.athlete);active(f.coach,f.athlete);});
        return withBlob(input,(staged,valid)->tx.execute(status->{
            var f=resolve(actor,id,true);Policy.upload(actor,f.coach,f.athlete);active(f.coach,f.athlete);
            f.latestVersion=Math.addExact(f.latestVersion,1);f.updatedAt=Instant.now();
            files.flush();addVersion(f,staged,valid,note,actor.id());
            var version=version(id,f.latestVersion);
            outbox.append(f,"Uploaded",uploadEvent(metadata(f),version),f.latestVersion);return version;
        }));
    }
    private interface BlobAction<T> { T run(BlobStore.Staged stage,FileValidator.Validated valid); }
    private <T> T withBlob(MultipartFile input,BlobAction<T> action) throws IOException {
        BlobStore.Staged staged;
        try(InputStream stream=input.getInputStream()){staged=blobs.put(stream);}
        boolean promoted=false;
        try {
            var valid=validator.validate(staged,input.getOriginalFilename());
            blobs.promote(staged);promoted=true;
            return action.run(staged,valid);
        } catch(IOException|RuntimeException e) {
            // Translate only after the failed DB transaction has rolled back and the blob is compensated below.
            boolean conflict=e instanceof org.springframework.dao.DataIntegrityViolationException||e instanceof org.springframework.orm.ObjectOptimisticLockingFailureException;
            try {
                if(promoted) {
                    // A commit-outcome-unknown/network failure is not proof of rollback: check in a new DB transaction.
                    Boolean referenced=tx.execute(s->db.queryForObject("select exists(select 1 from file_versions where blob_key=?)",Boolean.class,staged.key()));
                    if(Boolean.FALSE.equals(referenced))blobs.discardUnreferenced(staged);
                } else blobs.discardStage(staged);
            } catch(Exception cleanup) {
                // Prefer a recoverable orphan to deleting a possibly committed blob when DB state is unknown.
                org.slf4j.LoggerFactory.getLogger(FileService.class).warn("Blob compensation deferred; reconciliation required ({})",cleanup.getClass().getSimpleName());
            }
            if(conflict)throw Api.fail(409,"FILE_ALREADY_EXISTS");
            throw e;
        }
    }
    private void addVersion(StoredFile f,BlobStore.Staged staged,FileValidator.Validated valid,String note,UUID actor) {
        db.update("insert into file_versions(file_id,version,blob_key,original_name,content_type,size_bytes,checksum_sha256,change_note,uploaded_by,uploaded_at) values(?,?,?,?,?,?,?,?,?,?)",
            f.id,f.latestVersion,staged.key(),valid.name(),valid.mime(),staged.size(),staged.sha256(),note,actor,Timestamp.from(f.updatedAt));
    }
    Map<String,Object> list(Policy.Actor actor,UUID coach,UUID athlete,Policy.Category category,int page,int size) {
        Policy.filter(actor,coach,athlete);if(page<0||size<1||size>100)throw Api.fail(400,"VALIDATION_FAILED");
        return tx.execute(status->{
            Page<StoredFile> results=files.scoped(actor.role()==Policy.Role.ADMIN,coachScope(actor),athleteScope(actor),coach,athlete,
                category==null?null:category.name(),PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"))));
            return Map.of("page",page,"size",size,"total_elements",results.getTotalElements(),"total_pages",results.getTotalPages(),"content",results.getContent().stream().map(this::metadata).toList());
        });
    }
    Map<String,Object> detail(Policy.Actor actor,UUID id) {
        return tx.execute(status->{var f=resolve(actor,id,false);var data=metadata(f);data.put("versions",versions(id));return data;});
    }
    Map<String,Object> patch(Policy.Actor actor,UUID id,Map<String,Object> patch) {
        if(patch.isEmpty()||!Set.of("title","description","category").containsAll(patch.keySet()))throw Api.fail(400,"VALIDATION_FAILED");
        return tx.execute(status->{
            var f=resolve(actor,id,true);Policy.edit(actor,f.coach);
            if(patch.containsKey("title")){f.title=string(patch.get("title"));text(f.title,2,100);}
            if(patch.containsKey("description")){f.description=patch.get("description")==null?null:string(patch.get("description"));optional(f.description,500);}
            if(patch.containsKey("category"))f.category=Policy.Category.valueOf(string(patch.get("category"))).name();
            f.updatedAt=Instant.now();files.flush();return metadata(f);
        });
    }
    void delete(Policy.Actor actor,UUID id) {
        tx.executeWithoutResult(status->{var f=resolve(actor,id,true);Policy.edit(actor,f.coach);
            f.deletedAt=Instant.now();f.updatedAt=f.deletedAt;files.flush();
            var deleted=metadata(f);deleted.put("deleted_by",actor.id().toString());
            outbox.append(f,"Deleted",deleted,((long)f.latestVersion)+1);
        });
    }
    record Download(InputStream stream,String name,String mime,long size,int version) {}
    Download download(Policy.Actor actor,UUID id,Integer requested) {
        if(requested!=null&&requested<1)throw Api.fail(400,"VALIDATION_FAILED");
        return tx.execute(status->{
            var f=resolve(actor,id,false);int number=requested==null?f.latestVersion:requested;
            var rows=db.queryForList("select blob_key,original_name,content_type,size_bytes from file_versions where file_id=? and version=?",id,number);
            if(rows.isEmpty())throw Api.fail(404,"NOT_FOUND");var row=rows.getFirst();String key=(String)row.get("blob_key");
            try {
                long expected=((Number)row.get("size_bytes")).longValue();
                if(!blobs.exists(key)||blobs.size(key)!=expected)throw new IOException("Committed blob missing or incorrect size");
                return new Download(blobs.open(key),(String)row.get("original_name"),(String)row.get("content_type"),expected,number);
            }catch(IOException e){throw new IllegalStateException("Blob integrity failure",e);}
        });
    }
    private StoredFile resolve(Policy.Actor actor,UUID id,boolean lock) {
        var found=lock?files.locked(id,actor.role()==Policy.Role.ADMIN,coachScope(actor),athleteScope(actor)):
            files.visible(id,actor.role()==Policy.Role.ADMIN,coachScope(actor),athleteScope(actor));
        if(found.isPresent())return found.get();
        // This existence-only probe deliberately preserves contract 404 (deleted) versus 403 (non-owner).
        if(files.existsByIdAndDeletedAtIsNull(id))throw Api.fail(403,"FORBIDDEN");throw Api.fail(404,"NOT_FOUND");
    }
    private static UUID coachScope(Policy.Actor actor){return actor.role()==Policy.Role.COACH?actor.id():null;}
    private static UUID athleteScope(Policy.Actor actor){return actor.role()==Policy.Role.ATHLETE?actor.id():null;}
    void active(UUID coach,UUID athlete) {
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from subscriptions where coach_id=? and athlete_id=? and status='ACTIVE' and ends_at>current_timestamp)",Boolean.class,coach,athlete)))throw Api.fail(403,"NO_ACTIVE_RELATION");
    }
    private Map<String,Object> metadata(StoredFile f) {
        var v=version(f.id,f.latestVersion);Map<String,Object> data=new LinkedHashMap<>();
        data.put("file_id",f.id.toString());data.put("coach_id",f.coach.toString());data.put("athlete_id",f.athlete.toString());data.put("category",f.category);
        data.put("title",f.title);data.put("description",f.description);data.put("latest_version",f.latestVersion);
        data.put("content_type",v.get("content_type"));data.put("size_bytes",v.get("size_bytes"));
        data.put("created_at",f.createdAt.toString());data.put("updated_at",f.updatedAt.toString());return data;
    }
    private List<Map<String,Object>> versions(UUID id) {
        return db.query("select version,original_name,content_type,size_bytes,checksum_sha256,change_note,uploaded_by,uploaded_at from file_versions where file_id=? order by version desc",(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("version",rs.getInt(1));row.put("original_name",rs.getString(2));row.put("content_type",rs.getString(3));
            row.put("size_bytes",rs.getLong(4));row.put("checksum_sha256",rs.getString(5));row.put("change_note",rs.getString(6));row.put("uploaded_by",rs.getObject(7).toString());
            row.put("uploaded_at",rs.getTimestamp(8).toInstant().toString());return row;
        },id);
    }
    private Map<String,Object> version(UUID id,int version) {
        return versions(id).stream().filter(v->((Integer)v.get("version"))==version).findFirst().orElseThrow(()->Api.fail(404,"NOT_FOUND"));
    }
    private static Map<String,Object> uploadEvent(Map<String,Object> metadata,Map<String,Object> version){var result=new LinkedHashMap<>(metadata);result.putAll(version);return result;}
    private static String string(Object value){if(!(value instanceof String s))throw Api.fail(400,"VALIDATION_FAILED");return s;}
    static void text(String text,int min,int max){if(text==null||text.isBlank()||text.length()<min||text.length()>max)throw Api.fail(400,"VALIDATION_FAILED");}
    static void optional(String text,int max){if(text!=null&&text.length()>max)throw Api.fail(400,"VALIDATION_FAILED");}
}
