package dev.flashflow.shared.web;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        Map<String, String> details = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException validation) {
            for (FieldError error : validation.getBindingResult().getFieldErrors()) {
                details.put(error.getField(), error.getDefaultMessage());
            }
        }
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Request validation failed", Instant.now(), Map.copyOf(details)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "INTERNAL_ERROR", "The request could not be completed", Instant.now(), Map.of()));
    }
}

