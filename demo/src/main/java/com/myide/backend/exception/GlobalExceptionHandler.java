package com.myide.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리기
 * 시스템 전반에서 발생하는 에러를 포착하여 규격화된 응답으로 반환합니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. ResponseStatusException 처리
     *
     * 서비스 계층에서 아래처럼 던진 예외를 원래 상태 코드 그대로 내려줍니다.
     *
     * throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
     * throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
     * throw new ResponseStatusException(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다.");
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();

        String message = ex.getReason();
        if (message == null || message.isBlank()) {
            message = "요청을 처리할 수 없습니다.";
        }

        if (statusCode.is4xxClientError()) {
            log.warn("요청 처리 실패: status={}, message={}", statusCode.value(), message);
        } else {
            log.error("서버 상태 예외: status={}, message={}", statusCode.value(), message, ex);
        }

        return ResponseEntity.status(statusCode).body(message);
    }

    /**
     * 2. 유효성 검사 실패 (@Valid 에러)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        Map<String, String> errors = new HashMap<>();

        bindingResult.getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("유효성 검사 실패: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * 3. 데이터 중복 및 부적절한 인자 예외 처리
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("부적절한 요청 발생: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * 4. Spring Security 권한 예외 처리
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("접근 거부: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근이 거부되었습니다.");
    }

    /**
     * 5. 보안 예외 처리
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> handleSecurityException(SecurityException ex) {
        log.warn("보안 경고: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근이 거부되었습니다: " + ex.getMessage());
    }

    /**
     * 6. 비즈니스 로직 예외
     *
     * 주의:
     * ResponseStatusException은 위에서 먼저 처리해야 합니다.
     * 그렇지 않으면 401, 403, 404가 전부 500으로 바뀝니다.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException ex) {
        log.error("서버 내부 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("요청 처리 중 오류가 발생했습니다: " + ex.getMessage());
    }

    /**
     * 7. 기타 알 수 없는 예외
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        log.error("알 수 없는 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("시스템 오류가 발생했습니다.");
    }
}