package com.apprentice.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handle(ApiException e) {
    return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + ": " + f.getDefaultMessage())
        .findFirst()
        .orElse("Invalid request data");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", msg));
  }
  @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,org.springframework.orm.ObjectOptimisticLockingFailureException.class})
  public ResponseEntity<Map<String,Object>> conflict(Exception e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","This record was already used or changed. Refresh and retry."));}
  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,IllegalArgumentException.class})
  public ResponseEntity<Map<String,Object>> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Invalid request fields."));}
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String,Object>> processing(IllegalStateException e){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",e.getMessage()==null?"Processing interrupted; your received evidence is preserved.":e.getMessage()));}
}
