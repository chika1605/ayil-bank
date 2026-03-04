package kg.ayil_bank.service;

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
import kg.ayil_bank.service.impl.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private TransferMapper transferMapper;

    @InjectMocks
    private TransferServiceImpl transferService;

    private Account fromAccount;
    private Account toAccount;
    private TransferRequest request;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        fromAccount = new Account(1L, "ACC001", new BigDecimal("1000.00"), AccountStatus.ACTIVE, 1L);
        toAccount = new Account(2L, "ACC002", new BigDecimal("500.00"), AccountStatus.ACTIVE, 1L);
        request = new TransferRequest("ACC001", "ACC002", new BigDecimal("100.00"));
        idempotencyKey = "550e8400-e29b-41d4-a716-446655440001";
    }

    @Test
    void transfer_Success() {
        when(transactionRepository.findByIdempotencyKey(any(UUID.class))).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberWithLock("ACC001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberWithLock("ACC002")).thenReturn(Optional.of(toAccount));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(transferMapper.toSuccessResponse(any(), any())).thenReturn(
            new TransferResponse(1L, "ACC001", "ACC002", new BigDecimal("100.00"), 
                TransactionStatus.SUCCESS, "Transfer completed successfully", null)
        );

        TransferResponse response = transferService.transfer(request, idempotencyKey);

        assertNotNull(response);
        assertEquals(TransactionStatus.SUCCESS, response.getStatus());
        assertEquals(new BigDecimal("900.00"), fromAccount.getBalance());
        assertEquals(new BigDecimal("600.00"), toAccount.getBalance());
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    @Test
    void transfer_AccountNotFound() {
        when(transactionRepository.findByIdempotencyKey(any(UUID.class))).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberWithLock("ACC001")).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> transferService.transfer(request, idempotencyKey));
    }

    @Test
    void transfer_InsufficientBalance() {
        fromAccount.setBalance(new BigDecimal("50.00"));
        when(transactionRepository.findByIdempotencyKey(any(UUID.class))).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberWithLock("ACC001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberWithLock("ACC002")).thenReturn(Optional.of(toAccount));

        assertThrows(InsufficientBalanceException.class, () -> transferService.transfer(request, idempotencyKey));
    }

    @Test
    void transfer_AccountNotActive() {
        fromAccount.setStatus(AccountStatus.BLOCKED);
        when(transactionRepository.findByIdempotencyKey(any(UUID.class))).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberWithLock("ACC001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberWithLock("ACC002")).thenReturn(Optional.of(toAccount));

        assertThrows(AccountNotActiveException.class, () -> transferService.transfer(request, idempotencyKey));
    }

    @Test
    void transfer_SameAccount() {
        request.setToAccountNumber("ACC001");
        when(transactionRepository.findByIdempotencyKey(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(InvalidTransferException.class, () -> transferService.transfer(request, idempotencyKey));
    }

    @Test
    void transfer_Idempotency() {
        UUID uuid = UUID.fromString(idempotencyKey);
        Transaction existingTransaction = new Transaction();
        existingTransaction.setId(1L);
        existingTransaction.setFromAccountId(1L);
        existingTransaction.setToAccountId(2L);
        existingTransaction.setAmount(new BigDecimal("100.00"));
        existingTransaction.setStatus(TransactionStatus.SUCCESS);
        existingTransaction.setIdempotencyKey(uuid);

        when(transactionRepository.findByIdempotencyKey(uuid)).thenReturn(Optional.of(existingTransaction));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(toAccount));
        when(transferMapper.toResponse(any(), any(), any())).thenReturn(
            new TransferResponse(1L, "ACC001", "ACC002", new BigDecimal("100.00"), 
                TransactionStatus.SUCCESS, "Transfer completed successfully", null)
        );

        TransferResponse response = transferService.transfer(request, idempotencyKey);

        assertNotNull(response);
        assertEquals(TransactionStatus.SUCCESS, response.getStatus());
        verify(accountRepository, never()).save(any(Account.class));
    }
}
