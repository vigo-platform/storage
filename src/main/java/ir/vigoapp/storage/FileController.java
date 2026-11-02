package ir.vigoapp.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/files")
class FileController {
    private final FileService service;
    FileController(FileService service){this.service=service;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt,@RequestPart("file")MultipartFile file,
        @RequestParam("athlete_id")UUID athlete,@RequestParam("category")Policy.Category category,
        @RequestParam("title")String title,@RequestParam(value="description",required=false)String description) throws java.io.IOException {
        var data=service.upload(Policy.Actor.from(jwt),athlete,category,title,description,file);
        return ResponseEntity.created(URI.create("/api/v1/files/"+data.get("file_id"))).body(Api.envelope(true,"CREATED",data));
    }
    @GetMapping
    Object list(@AuthenticationPrincipal Jwt jwt,@RequestParam(value="athlete_id",required=false)UUID athlete,
        @RequestParam(value="coach_id",required=false)UUID coach,@RequestParam(value="category",required=false)Policy.Category category,
        @RequestParam(value="page",defaultValue="0")int page,@RequestParam(value="size",defaultValue="20")int size) {
        return Api.envelope(true,"OK",service.list(Policy.Actor.from(jwt),coach,athlete,category,page,size));
    }
    @GetMapping("/{file_id}")
    Object detail(@AuthenticationPrincipal Jwt jwt,@PathVariable("file_id")UUID id){return Api.envelope(true,"OK",service.detail(Policy.Actor.from(jwt),id));}
    @PatchMapping(value="/{file_id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object patch(@AuthenticationPrincipal Jwt jwt,@PathVariable("file_id")UUID id,@RequestBody Map<String,Object> fields){return Api.envelope(true,"OK",service.patch(Policy.Actor.from(jwt),id,fields));}
    @DeleteMapping("/{file_id}")
    Object delete(@AuthenticationPrincipal Jwt jwt,@PathVariable("file_id")UUID id){service.delete(Policy.Actor.from(jwt),id);return Api.envelope(true,"DELETED",null);}
    @PostMapping(value="/{file_id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> version(@AuthenticationPrincipal Jwt jwt,@PathVariable("file_id")UUID id,@RequestPart("file")MultipartFile file,
        @RequestParam(value="change_note",required=false)String note)throws java.io.IOException {
        return ResponseEntity.status(201).body(Api.envelope(true,"CREATED",service.add(Policy.Actor.from(jwt),id,file,note)));
    }
    @GetMapping("/{file_id}/download")
    ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal Jwt jwt,@PathVariable("file_id")UUID id,@RequestParam(value="version",required=false)Integer version){
        var file=service.download(Policy.Actor.from(jwt),id,version);
        StreamingResponseBody body=output->{try(var stream=file.stream()){stream.transferTo(output);}};
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime())).contentLength(file.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-File-Version",Integer.toString(file.version())).header("X-Content-Type-Options","nosniff")
            .cacheControl(CacheControl.noStore()).body(body);
    }
}
