package com.nextdoor.nextdoor.domain.feed.domain.Exception;

public class FeedRedisUnavailableException extends RuntimeException {
    public FeedRedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
