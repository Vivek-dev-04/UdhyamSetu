package com.shopbilling.service;

/**
 * Thrown when user authentication fails due to invalid credentials or inactive account (PRD FR-41, FR-42).
 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
