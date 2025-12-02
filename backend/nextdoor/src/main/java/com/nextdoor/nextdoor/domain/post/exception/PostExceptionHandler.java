package com.nextdoor.nextdoor.domain.post.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.nextdoor.nextdoor.domain.post")
public class PostExceptionHandler {

    @ExceptionHandler({
            NoSuchPostException.class,
            PostImageUploadException.class,
            PostLikeException.class,
            PostSearchException.class,
            PostIndexException.class,
            HttpFileReadException.class,
    })
    public ResponseEntity<ErrorResponse> handleBadRequestException(BaseCustomException e, HttpServletRequest request) {
        return logAndHandleException(HttpStatus.BAD_REQUEST, e, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException e, HttpServletRequest request) {
        return logAndHandleException(HttpStatus.BAD_REQUEST, e, "ILLEGAL_REQUEST", "잘못된 요청입니다.", request);
    }

    @ExceptionHandler({
            Exception.class
    })
    public ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        return logAndHandleException(HttpStatus.INTERNAL_SERVER_ERROR, e, "UNKNOWN", "알 수 없는 에러", request);
    }

    private ResponseEntity<ErrorResponse> logAndHandleException(
            HttpStatus httpStatus,
            BaseCustomException e,
            HttpServletRequest request
    ) {
        return logAndHandleException(httpStatus, e, e.getErrorCode(), e.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> logAndHandleException(
            HttpStatus httpStatus,
            Exception e,
            String errorCode,
            String message,
            HttpServletRequest request
    ) {
        log.error(e.getMessage(), e);
        return ResponseEntity.status(httpStatus).body(new ErrorResponse(
                errorCode,
                message,
                request.getRequestURI()
        ));
    }
}
