package com.chat.authservice.exception;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.springframework.validation.FieldError;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        log.warn("Request failed [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(ex.getStatusCode().value(), ex.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpServletRequest request) {
        log.warn("Missing or malformed request body [{} {}]", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Missing or malformed request body"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Validation failed: " + fields));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception [{} {}]", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(500, "An internal error occurred"));
    }
}
