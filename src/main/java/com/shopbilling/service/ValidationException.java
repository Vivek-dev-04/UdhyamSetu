package com.shopbilling.service;

/**
 * Thrown when business validation fails for user input or entity fields.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
