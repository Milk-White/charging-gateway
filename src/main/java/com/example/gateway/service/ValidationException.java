package com.example.gateway.service;

public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
