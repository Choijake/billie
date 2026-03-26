package com.nextdoor.nextdoor.domain.feed.infrastructure.exception;

public class InfraException extends RuntimeException {
    public InfraException(String message, Throwable cause) { super(message, cause); }
    public InfraException(String message) { super(message); }
}