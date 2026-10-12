package com.assessment.agentic.url;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UrlExceptionHandler {

        @ExceptionHandler(UrlNotFoundException.class)
        public ResponseEntity<Map<String, String>> handleUrlNotFound(
                        UrlNotFoundException ex) {
                return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        @ExceptionHandler(NoSuchElementException.class)
        public ResponseEntity<Map<String, String>> handleNotFound(
                        NoSuchElementException ex) {
                return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, String>> handleInvalidState(
                        IllegalStateException ex) {
                return error(HttpStatus.CONFLICT, ex.getMessage());
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> handleBadRequest(
                        IllegalArgumentException ex) {
                return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String, String>> handleValidation(
                        MethodArgumentNotValidException ex) {
                String message = ex.getBindingResult()
                                .getFieldErrors()
                                .stream()
                                .findFirst()
                                .map(error -> error.getField() + ": "
                                                + error.getDefaultMessage())
                                .orElse("Request validation failed");

                return error(HttpStatus.BAD_REQUEST, message);
        }

        private ResponseEntity<Map<String, String>> error(
                        HttpStatus status, String message) {
                return ResponseEntity.status(status)
                                .body(Map.of(
                                                "status", status.name(),
                                                "message", message == null
                                                                ? status.getReasonPhrase()
                                                                : message));
        }
}