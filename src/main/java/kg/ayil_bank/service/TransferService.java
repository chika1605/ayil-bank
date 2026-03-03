package kg.ayil_bank.service;

import kg.ayil_bank.dto.TransferRequest;
import kg.ayil_bank.dto.TransferResponse;

public interface TransferService {
    TransferResponse transfer(TransferRequest request, String idempotencyKey);
}
