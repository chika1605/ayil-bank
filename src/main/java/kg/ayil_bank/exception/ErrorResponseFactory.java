package kg.ayil_bank.exception;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ErrorResponseFactory {
    
    public GlobalExceptionHandler.ErrorResponse build(HttpStatus status, String message) {
        return new GlobalExceptionHandler.ErrorResponse(message, status.value(), LocalDateTime.now());
    }
}
