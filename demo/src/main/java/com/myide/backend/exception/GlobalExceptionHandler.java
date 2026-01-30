package com.myide.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리기
 * 시스템 전반에서 발생하는 에러를 포착하여 규격화된 JSON으로 응답합니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. 유효성 검사 실패 (@Valid 에러)
     * DTO의 @NotBlank 조건 등을 만족하지 못했을 때 발생
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        Map<String, String> errors = new HashMap<>();

        // 어떤 필드가 왜 틀렸는지 맵에 담음 (예: projectName -> "필수입니다")
        bindingResult.getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("유효성 검사 실패: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * 2. 보안 예외 처리 (해킹 의심 등)
     * PathSecurityUtils에서 던진 SecurityException을 잡음
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> handleSecurityException(SecurityException ex) {
        log.warn("🚨 보안 경고: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근이 거부되었습니다: " + ex.getMessage());
    }

    /**
     * 3. 비즈니스 로직 예외 (파일 중복, 디스크 오류 등)
     * RuntimeException을 잡아서 처리
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException ex) {
        log.error("서버 내부 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("요청 처리 중 오류가 발생했습니다: " + ex.getMessage());
    }

    /**
     * 4. 기타 알 수 없는 예외
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        log.error("알 수 없는 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("시스템 오류가 발생했습니다.");
    }
}