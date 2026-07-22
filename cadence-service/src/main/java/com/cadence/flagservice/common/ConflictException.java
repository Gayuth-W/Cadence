package com.cadence.flagservice.common;

/** 409: an optimistic-lock collision, a duplicate flag key, or an illegal state transition. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
