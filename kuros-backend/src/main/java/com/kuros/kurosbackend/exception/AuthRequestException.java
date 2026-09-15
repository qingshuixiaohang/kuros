package com.kuros.kurosbackend.exception;

public class AuthRequestException extends RuntimeException {

    private final String code;

    public AuthRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
