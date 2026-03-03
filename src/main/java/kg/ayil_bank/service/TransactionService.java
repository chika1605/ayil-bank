package kg.ayil_bank.service;

import kg.ayil_bank.entity.Transaction;
import kg.ayil_bank.enums.TransactionStatus;
import kg.ayil_bank.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionService {
    
    private final TransactionRepository transactionRepository;
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailedTransaction(Transaction transaction, String errorMessage) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setErrorMessage(errorMessage);
        transactionRepository.save(transaction);
    }
}
