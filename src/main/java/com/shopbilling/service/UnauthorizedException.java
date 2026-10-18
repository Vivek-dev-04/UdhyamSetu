package com.shopbilling.service;

/**
 * Thrown when an authenticated user attempts an operation prohibited by their role (PRD Section 5, FR-43).
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
