package com.nextdoor.nextdoor.domain.feed.application.exception;

public class ApplicationException extends RuntimeException {
    public ApplicationException(String message, Throwable cause) { super(message, cause); }
    public ApplicationException(String message) { super(message); }
}
