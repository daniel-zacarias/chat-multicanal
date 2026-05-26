package com.chat.presenceservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatus(
            ResponseStatusException ex, ServerWebExchange exchange) {
        log.warn("Request failed [{} {}]: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                ex.getReason());
        return Mono.just(ResponseEntity
                .status(ex.getStatusCode())
                .body(new ErrorResponse(ex.getStatusCode().value(), ex.getReason())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAll(
            Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled exception [{} {}]",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(), ex);
        return Mono.just(ResponseEntity.internalServerError()
                .body(new ErrorResponse(500, "An internal error occurred")));
    }
}
