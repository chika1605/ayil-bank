package kg.ayil_bank.controller;

import jakarta.validation.Valid;
import kg.ayil_bank.dto.TransferRequest;
import kg.ayil_bank.dto.TransferResponse;
import kg.ayil_bank.exception.InvalidTransferException;
import kg.ayil_bank.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@Slf4j
public class TransferController {
    
    private final TransferService transferService;
    
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        
        validateUUID(idempotencyKey);
        
        log.info("Получен запрос на перевод: {}", request);
        TransferResponse response = transferService.transfer(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    private void validateUUID(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new InvalidTransferException("Ключ идемпотентности должен быть в формате UUID");
        }
    }
}
