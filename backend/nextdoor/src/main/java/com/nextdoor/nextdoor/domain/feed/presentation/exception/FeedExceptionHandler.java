package com.nextdoor.nextdoor.domain.feed.presentation.exception;

import com.nextdoor.nextdoor.domain.feed.domain.exception.DomainException;
import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.nextdoor.nextdoor.domain.feed")
public class FeedExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<FeedErrorResponse> handleDomain(DomainException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "FEED_DOMAIN_ERROR", e.getMessage(), e, request);
    }

    @ExceptionHandler(InfraException.class)
    public ResponseEntity<FeedErrorResponse> handleInfra(InfraException e, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "FEED_INFRA_ERROR", "피드 시스템이 일시적으로 불안정합니다.", e, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<FeedErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "FEED_INVALID_REQUEST", e.getMessage(), e, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FeedErrorResponse> handleUnknown(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "FEED_UNKNOWN", "알 수 없는 에러", e, request);
    }

    private ResponseEntity<FeedErrorResponse> respond(HttpStatus status, String code, String message,
                                                      Exception e, HttpServletRequest request) {
        log.error("[Feed Exception] code={}, path={}", code, request.getRequestURI(), e);
        return ResponseEntity.status(status).body(new FeedErrorResponse(code, message, request.getRequestURI()));
    }
}
