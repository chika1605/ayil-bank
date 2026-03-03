package kg.ayil_bank.dto;

import kg.ayil_bank.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponse {
    private Long transactionId;
    private String fromAccountNumber;
    private String toAccountNumber;
    private BigDecimal amount;
    private TransactionStatus status;
    private String message;
    private LocalDateTime timestamp;
    
    public Long getTransactionId() {
        return transactionId;
    }
    
    public String getFromAccountNumber() {
        return maskAccountNumber(fromAccountNumber);
    }
    
    public String getToAccountNumber() {
        return maskAccountNumber(toAccountNumber);
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public TransactionStatus getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
