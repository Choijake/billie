package com.nextdoor.nextdoor.domain.feed.presentation.exception;

public record FeedErrorResponse(
        String code,
        String message,
        String path
) {
}
