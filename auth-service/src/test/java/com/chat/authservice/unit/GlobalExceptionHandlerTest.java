package com.chat.authservice.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import com.chat.authservice.exception.ErrorResponse;
import com.chat.authservice.exception.GlobalExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        handler     = new GlobalExceptionHandler();
        httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getMethod()).thenReturn("POST");
        when(httpRequest.getRequestURI()).thenReturn("/auth/login");
    }

    // ------------------------------------------------------------------ //
    //  handleResponseStatus                                                 //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("handleResponseStatus()")
    class HandleResponseStatus {

        @Test
        @DisplayName("maps the HTTP status code from the exception")
        void mapsStatusCode() {
            ResponseStatusException ex =
                    new ResponseStatusException(HttpStatus.CONFLICT, "Registration could not be completed");

            ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(ex, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
        }

        @Test
        @DisplayName("sets the reason phrase as the message")
        void setsReasonAsMessage() {
            ResponseStatusException ex =
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");

            ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(ex, httpRequest);

            assertThat(response.getBody().message()).isEqualTo("Invalid credentials");
        }

        @Test
        @DisplayName("handles 404 Not Found correctly")
        void handles404() {
            ResponseStatusException ex =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");

            ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(ex, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().status()).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------ //
    //  handleValidation                                                     //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("handleValidation()")
    class HandleValidation {

        @Test
        @DisplayName("returns 400 status")
        void returns400() {
            MethodArgumentNotValidException ex = buildValidationException("username", "must not be blank");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().status()).isEqualTo(400);
        }

        @Test
        @DisplayName("concatenates all field errors in the message")
        void concatenatesFieldErrors() {
            MethodArgumentNotValidException ex = buildValidationException("email", "must be a valid email");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

            assertThat(response.getBody().message()).contains("email").contains("must be a valid email");
        }

        private MethodArgumentNotValidException buildValidationException(String field, String message) {
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("registerRequest", field, message);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            return ex;
        }
    }

    // ------------------------------------------------------------------ //
    //  handleAll (catch-all)                                                //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("handleAll()")
    class HandleAll {

        @Test
        @DisplayName("returns 500 for unexpected exceptions")
        void returns500ForUnexpected() {
            RuntimeException ex = new RuntimeException("Unexpected database failure");

            ResponseEntity<ErrorResponse> response = handler.handleAll(ex, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().status()).isEqualTo(500);
        }

        @Test
        @DisplayName("does not leak exception details in the response body")
        void doesNotLeakExceptionDetails() {
            RuntimeException ex = new RuntimeException("Sensitive internal detail: DB password=secret");

            ResponseEntity<ErrorResponse> response = handler.handleAll(ex, httpRequest);

            assertThat(response.getBody().message()).doesNotContain("secret");
            assertThat(response.getBody().message()).doesNotContain("DB password");
        }
    }
}
