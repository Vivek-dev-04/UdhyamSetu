package com.shopbilling.service;

/**
 * Thrown when attempting to insert or update an entity that violates unique constraints.
 */
public class DuplicateEntityException extends RuntimeException {

    public DuplicateEntityException(String message) {
        super(message);
    }
}
