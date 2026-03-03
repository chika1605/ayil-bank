package kg.ayil_bank.exception;

public class IdempotencyViolationException extends RuntimeException {
    public IdempotencyViolationException(String message) {
        super(message);
    }
}
