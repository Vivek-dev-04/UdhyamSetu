package com.shopbilling.service;

/**
 * Thrown when an expected entity by ID or unique key is not found.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }
}
