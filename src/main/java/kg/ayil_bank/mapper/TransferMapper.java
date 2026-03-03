package kg.ayil_bank.mapper;

import kg.ayil_bank.dto.TransferRequest;
import kg.ayil_bank.dto.TransferResponse;
import kg.ayil_bank.entity.Account;
import kg.ayil_bank.entity.Transaction;
import kg.ayil_bank.enums.TransactionStatus;
import org.springframework.stereotype.Component;

@Component
public class TransferMapper {
    
    public TransferResponse toSuccessResponse(TransferRequest request, Transaction transaction) {
        return TransferResponse.builder()
                .transactionId(transaction.getId())
                .fromAccountNumber(request.getFromAccountNumber())
                .toAccountNumber(request.getToAccountNumber())
                .amount(request.getAmount())
                .status(TransactionStatus.SUCCESS)
                .message("Перевод выполнен успешно")
                .timestamp(transaction.getCreatedAt())
                .build();
    }
    
    public TransferResponse toResponse(Transaction transaction, Account fromAccount, Account toAccount) {
        return TransferResponse.builder()
                .transactionId(transaction.getId())
                .fromAccountNumber(fromAccount != null ? fromAccount.getAccountNumber() : null)
                .toAccountNumber(toAccount != null ? toAccount.getAccountNumber() : null)
                .amount(transaction.getAmount())
                .status(transaction.getStatus())
                .message(transaction.getStatus() == TransactionStatus.SUCCESS ? 
                        "Перевод выполнен успешно" : transaction.getErrorMessage())
                .timestamp(transaction.getCreatedAt())
                .build();
    }
}
