package kg.ayil_bank.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import kg.ayil_bank.enums.TransactionStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponse {
    private Long transactionId;

    @Getter(AccessLevel.NONE)
    private String fromAccountNumber;

    @Getter(AccessLevel.NONE)
    private String toAccountNumber;

    private BigDecimal amount;
    private TransactionStatus status;
    private String message;
    private LocalDateTime timestamp;

    @JsonProperty("fromAccountNumber")
    public String getFromAccountNumber() {
        return maskAccountNumber(fromAccountNumber);
    }

    @JsonProperty("fromAccountNumber")
    public String getToAccountNumber() {
        return maskAccountNumber(toAccountNumber);
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
