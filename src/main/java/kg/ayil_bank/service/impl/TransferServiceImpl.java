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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

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
                maskAccountNumber(request.getFromAccountNumber()), 
                maskAccountNumber(request.getToAccountNumber()), 
                request.getAmount());
        
        UUID uuid = idempotencyKey != null ? UUID.fromString(idempotencyKey) : null;
        
        Optional<TransferResponse> cachedResponse = checkIdempotency(uuid, request);
        if (cachedResponse.isPresent()) {
            return cachedResponse.get();
        }
        
        Transaction transaction = createTransaction(request, uuid);
        
        try {
            validateSameAccount(request);
            AccountPair accounts = lockAccountsInOrder(request);
            validateAccounts(accounts.from(), accounts.to(), request.getAmount());
            
            transaction.setFromAccountId(accounts.from().getId());
            transaction.setToAccountId(accounts.to().getId());
            
            executeTransfer(accounts.from(), accounts.to(), request.getAmount());
            saveSuccessTransaction(transaction);
            
            log.info("Перевод успешно завершен. ID транзакции: {}", transaction.getId());
            return transferMapper.toSuccessResponse(request, transaction);
            
        } catch (AccountNotFoundException | InsufficientBalanceException | 
                 AccountNotActiveException | InvalidTransferException | IdempotencyViolationException e) {
            log.error("Перевод не выполнен: {}", e.getMessage());
            transactionService.saveFailedTransaction(transaction, e.getMessage());
            throw e;
        } catch (ObjectOptimisticLockingFailureException e) {
            log.error("Конфликт версий при обновлении счета");
            throw new InvalidTransferException("Конфликт при обновлении счета, повторите попытку");
        } catch (DataIntegrityViolationException e) {
            log.error("Нарушение целостности данных");
            throw new InvalidTransferException("Ошибка целостности данных");
        }
    }
    
    private Optional<TransferResponse> checkIdempotency(UUID idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(transaction -> {
                    Account fromAccount = transaction.getFromAccountId() != null 
                        ? accountRepository.findById(transaction.getFromAccountId()).orElse(null) 
                        : null;
                    Account toAccount = transaction.getToAccountId() != null 
                        ? accountRepository.findById(transaction.getToAccountId()).orElse(null) 
                        : null;
                    
                    if (!transaction.getAmount().equals(request.getAmount()) ||
                        (fromAccount != null && !fromAccount.getAccountNumber().equals(request.getFromAccountNumber())) ||
                        (toAccount != null && !toAccount.getAccountNumber().equals(request.getToAccountNumber()))) {
                        throw new IdempotencyViolationException(
                            "Параметры запроса не совпадают с оригинальным запросом для данного ключа идемпотентности");
                    }
                    
                    log.info("Обнаружен дублирующий запрос с ключом идемпотентности: {}", idempotencyKey);
                    return transferMapper.toResponse(transaction, fromAccount, toAccount);
                });
    }
    
    private Transaction createTransaction(TransferRequest request, UUID idempotencyKey) {
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
    
    private AccountPair lockAccountsInOrder(TransferRequest request) {
        String fromAccountNumber = request.getFromAccountNumber();
        String toAccountNumber = request.getToAccountNumber();
        
        String firstAccountNumber;
        String secondAccountNumber;
        
        if (fromAccountNumber.compareTo(toAccountNumber) < 0) {
            firstAccountNumber = fromAccountNumber;
            secondAccountNumber = toAccountNumber;
        } else {
            firstAccountNumber = toAccountNumber;
            secondAccountNumber = fromAccountNumber;
        }
        
        final String finalFirstAccountNumber = firstAccountNumber;
        final String finalSecondAccountNumber = secondAccountNumber;
        
        Account firstAccount = accountRepository.findByAccountNumberWithLock(finalFirstAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Счет не найден: " + maskAccountNumber(finalFirstAccountNumber)));
        
        Account secondAccount = accountRepository.findByAccountNumberWithLock(finalSecondAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Счет не найден: " + maskAccountNumber(finalSecondAccountNumber)));
        
        Account fromAccount = firstAccount.getAccountNumber().equals(fromAccountNumber) 
                ? firstAccount : secondAccount;
        Account toAccount = firstAccount.getAccountNumber().equals(toAccountNumber) 
                ? firstAccount : secondAccount;
        
        return new AccountPair(fromAccount, toAccount);
    }
    
    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
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
