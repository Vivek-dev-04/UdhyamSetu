package com.shopbilling.model;

import java.util.Objects;

/**
 * Domain record representing an authenticated user account (PRD Section 5, 7.10, FR-41 - FR-43).
 */
public record User(
        Long id,
        String username,
        String passwordHash,
        UserRole role,
        String fullName,
        boolean isActive,
        String createdAt
) {
    public User {
        Objects.requireNonNull(username, "Username cannot be null");
        Objects.requireNonNull(role, "UserRole cannot be null");
        Objects.requireNonNull(fullName, "Full name cannot be null");
    }

    public boolean isOwner() {
        return role == UserRole.OWNER;
    }

    public boolean isStaff() {
        return role == UserRole.STAFF;
    }
}
