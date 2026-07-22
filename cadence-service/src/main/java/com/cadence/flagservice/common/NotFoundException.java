package com.cadence.flagservice.common;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException flag(Object id) {
        return new NotFoundException("Flag not found: " + id);
    }
}
