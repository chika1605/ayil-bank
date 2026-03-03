package kg.ayil_bank.service.impl;

import kg.ayil_bank.dto.TransferRequest;
import kg.ayil_bank.dto.TransferResponse;
import kg.ayil_bank.entity.Account;
import kg.ayil_bank.entity.Transaction;
import kg.ayil_bank.enums.AccountStatus;
import kg.ayil_bank.enums.TransactionStatus;
import kg.ayil_bank.exception.*;
import kg.ayil_bank.mapper.TransferMapper;
import kg.ayil_bank.repository.AccountRepository;
import kg.ayil_bank.repository.TransactionRepository;
import kg.ayil_bank.service.TransactionService;
import kg.ayil_bank.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferServiceImpl implements TransferService {
    
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final TransferMapper transferMapper;
    
    @Override
    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        log.info("Начало перевода с {} на {} сумма {}", 
                request.getFromAccountNumber(), request.getToAccountNumber(), request.getAmount());
        
        Optional<TransferResponse> cachedResponse = checkIdempotency(idempotencyKey);
        if (cachedResponse.isPresent()) {
            return cachedResponse.get();
        }
        
        Transaction transaction = createTransaction(request, idempotencyKey);
        
        try {
            validateSameAccount(request);
            AccountPair accounts = lockAccounts(request);
            validateAccounts(accounts.from(), accounts.to(), request.getAmount());
            
            transaction.setFromAccountId(accounts.from().getId());
            transaction.setToAccountId(accounts.to().getId());
            
            executeTransfer(accounts.from(), accounts.to(), request.getAmount());
            saveSuccessTransaction(transaction);
            
            log.info("Перевод успешно завершен. ID транзакции: {}", transaction.getId());
            return transferMapper.toSuccessResponse(request, transaction);
            
        } catch (Exception e) {
            log.error("Перевод не выполнен: {}", e.getMessage());
            transactionService.saveFailedTransaction(transaction, e.getMessage());
            throw e;
        }
    }
    
    private Optional<TransferResponse> checkIdempotency(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(transaction -> {
                    log.info("Обнаружен дублирующий запрос с ключом идемпотентности: {}", idempotencyKey);
                    Account fromAccount = accountRepository.findById(transaction.getFromAccountId()).orElse(null);
                    Account toAccount = accountRepository.findById(transaction.getToAccountId()).orElse(null);
                    return transferMapper.toResponse(transaction, fromAccount, toAccount);
                });
    }
    
    private Transaction createTransaction(TransferRequest request, String idempotencyKey) {
        Transaction transaction = new Transaction();
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setAmount(request.getAmount());
        return transaction;
    }
    
    private void validateSameAccount(TransferRequest request) {
        if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
            throw new InvalidTransferException("Нельзя переводить средства самому себе");
        }
    }
    
    private AccountPair lockAccounts(TransferRequest request) {
        Account fromAccount = accountRepository.findByAccountNumberWithLock(request.getFromAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Счет отправителя не найден: " + request.getFromAccountNumber()));
        
        Account toAccount = accountRepository.findByAccountNumberWithLock(request.getToAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Счет получателя не найден: " + request.getToAccountNumber()));
        
        return new AccountPair(fromAccount, toAccount);
    }
    
    private void validateAccounts(Account fromAccount, Account toAccount, BigDecimal amount) {
        if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Счет отправителя не активен");
        }
        
        if (toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Счет получателя не активен");
        }
        
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Недостаточно средств на счете отправителя");
        }
    }
    
    private void executeTransfer(Account fromAccount, Account toAccount, BigDecimal amount) {
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
    }
    
    private void saveSuccessTransaction(Transaction transaction) {
        transaction.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(transaction);
    }
    
    private record AccountPair(Account from, Account to) {}
}
