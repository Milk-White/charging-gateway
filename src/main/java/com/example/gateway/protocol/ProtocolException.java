package com.example.gateway.protocol;

public final class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
