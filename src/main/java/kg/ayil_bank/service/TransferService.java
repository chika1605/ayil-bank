package kg.ayil_bank.service;

import kg.ayil_bank.dto.TransferRequest;
import kg.ayil_bank.dto.TransferResponse;
import kg.ayil_bank.entity.Account;
import kg.ayil_bank.entity.Transaction;
import kg.ayil_bank.enums.AccountStatus;
import kg.ayil_bank.enums.TransactionStatus;
import kg.ayil_bank.exception.*;
import kg.ayil_bank.repository.AccountRepository;
import kg.ayil_bank.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {
    
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    
    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        log.info("Начало перевода с {} на {} сумма {}", 
                request.getFromAccountNumber(), request.getToAccountNumber(), request.getAmount());
        
        if (idempotencyKey != null) {
            var existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existingTransaction.isPresent()) {
                log.info("Обнаружен дублирующий запрос с ключом идемпотентности: {}", idempotencyKey);
                return buildResponseFromTransaction(existingTransaction.get());
            }
        }
        
        Transaction transaction = new Transaction();
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setAmount(request.getAmount());
        
        try {
            if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
                throw new InvalidTransferException("Нельзя переводить средства самому себе");
            }
            
            Account fromAccount = accountRepository.findByAccountNumberWithLock(request.getFromAccountNumber())
                    .orElseThrow(() -> new AccountNotFoundException("Счет отправителя не найден: " + request.getFromAccountNumber()));
            
            Account toAccount = accountRepository.findByAccountNumberWithLock(request.getToAccountNumber())
                    .orElseThrow(() -> new AccountNotFoundException("Счет получателя не найден: " + request.getToAccountNumber()));
            
            transaction.setFromAccountId(fromAccount.getId());
            transaction.setToAccountId(toAccount.getId());
            
            if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountNotActiveException("Счет отправителя не активен");
            }
            
            if (toAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountNotActiveException("Счет получателя не активен");
            }
            
            if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
                throw new InsufficientBalanceException("Недостаточно средств на счете отправителя");
            }
            
            fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
            
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);
            
            transaction.setStatus(TransactionStatus.SUCCESS);
            transactionRepository.save(transaction);
            
            log.info("Перевод успешно завершен. ID транзакции: {}", transaction.getId());
            
            return TransferResponse.builder()
                    .transactionId(transaction.getId())
                    .fromAccountNumber(request.getFromAccountNumber())
                    .toAccountNumber(request.getToAccountNumber())
                    .amount(request.getAmount())
                    .status(TransactionStatus.SUCCESS)
                    .message("Перевод выполнен успешно")
                    .timestamp(transaction.getCreatedAt())
                    .build();
            
        } catch (Exception e) {
            log.error("Перевод не выполнен: {}", e.getMessage());
            transactionService.saveFailedTransaction(transaction, e.getMessage());
            throw e;
        }
    }
    
    private TransferResponse buildResponseFromTransaction(Transaction transaction) {
        Account fromAccount = accountRepository.findById(transaction.getFromAccountId()).orElse(null);
        Account toAccount = accountRepository.findById(transaction.getToAccountId()).orElse(null);
        
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
