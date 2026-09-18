package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ErrorResponse;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.UnauthorizedException;
import com.kuros.kurosbackend.exception.ForbiddenException;
import com.kuros.kurosbackend.exception.FileStorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> resourceNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(exception.getCode(), exception.getMessage(), null));
    }

    @ExceptionHandler(AuthRequestException.class)
    public ResponseEntity<ErrorResponse> authRequest(AuthRequestException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(exception.getCode(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", exception.getMessage(), null));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", exception.getMessage(), null));
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ErrorResponse> fileStorage(FileStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("FILE_UPLOAD_FAILED", exception.getMessage(), null));
    }
}
