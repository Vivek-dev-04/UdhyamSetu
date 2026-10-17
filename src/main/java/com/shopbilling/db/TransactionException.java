package com.shopbilling.db;

/**
 * Typed exception thrown when a database transaction fails or is rolled back.
 */
public class TransactionException extends DatabaseException {

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
