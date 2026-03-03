package kg.ayil_bank.service;

import kg.ayil_bank.entity.Transaction;

public interface TransactionService {
    void saveFailedTransaction(Transaction transaction, String errorMessage);
}
