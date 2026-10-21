package com.kuros.kurosbackend.shared.exception;

public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("resource-not-found code is required");
        }
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
