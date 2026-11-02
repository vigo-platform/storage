package ir.vigoapp.storage;

import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

final class Api {
    private Api() {}
    static Map<String,Object> envelope(boolean success, String code, Object data) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("success",success); out.put("code",code);
        out.put("message",message(code)); out.put("message_fa",persian(code));
        out.put("data",data); out.put("timestamp",Instant.now().toString()); return out;
    }
    static String message(String code) { return switch(code) {
        case "OK" -> "Request completed"; case "CREATED" -> "File uploaded"; case "DELETED" -> "File deleted";
        case "UNAUTHORIZED" -> "Authentication required"; case "FORBIDDEN" -> "Access denied";
        case "NOT_FOUND" -> "File or version not found"; case "NO_ACTIVE_RELATION" -> "No active coach-athlete relation";
        case "FILE_ALREADY_EXISTS" -> "File already exists; upload a new version";
        case "FILE_TOO_LARGE" -> "File exceeds the 20 MB limit";
        case "UNSUPPORTED_MEDIA_TYPE" -> "Unsupported file type";
        case "FILE_CONTENT_MISMATCH" -> "File content does not match the extension or is malformed";
        case "VALIDATION_FAILED" -> "Invalid request"; default -> "Internal server error";
    }; }
    static String persian(String code) { return switch(code) {
        case "OK" -> "درخواست انجام شد"; case "CREATED" -> "فایل بارگذاری شد"; case "DELETED" -> "فایل حذف شد";
        case "UNAUTHORIZED" -> "احراز هویت الزامی است"; case "FORBIDDEN" -> "دسترسی مجاز نیست";
        case "NOT_FOUND" -> "فایل یا نسخه یافت نشد"; case "NO_ACTIVE_RELATION" -> "رابطه فعال مربی و ورزشکار وجود ندارد";
        case "FILE_ALREADY_EXISTS" -> "فایل موجود است؛ نسخه جدید بارگذاری کنید";
        case "FILE_TOO_LARGE" -> "حجم فایل بیشتر از ۲۰ مگابایت است";
        case "UNSUPPORTED_MEDIA_TYPE" -> "نوع فایل پشتیبانی نمی‌شود";
        case "FILE_CONTENT_MISMATCH" -> "محتوای فایل نامعتبر یا با پسوند ناسازگار است";
        case "VALIDATION_FAILED" -> "درخواست نامعتبر است"; default -> "خطای داخلی سرور";
    }; }
    static Fault fail(int status,String code) { return new Fault(status,code); }
    static final class Fault extends RuntimeException {
        final int status; final String code;
        Fault(int status,String code) { super(code); this.status=status; this.code=code; }
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(Api.Fault.class)
    ResponseEntity<?> fault(Api.Fault e) { return ResponseEntity.status(e.status).body(Api.envelope(false,e.code,null)); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception e) { return fault(Api.fail(413,"FILE_TOO_LARGE")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) { return fault(Api.fail(409,"FILE_ALREADY_EXISTS")); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,MissingServletRequestPartException.class,IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) { return fault(Api.fail(400,"VALIDATION_FAILED")); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> unsupported(Exception e) { return fault(Api.fail(415,"UNSUPPORTED_MEDIA_TYPE")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Storage request failed ({})",e.getClass().getSimpleName());
        return fault(Api.fail(500,"INTERNAL_SERVER_ERROR"));
    }
}
