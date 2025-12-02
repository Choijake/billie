package com.nextdoor.nextdoor.domain.post.exception;

import lombok.Getter;

@Getter
public class HttpFileReadException extends BaseCustomException {

    private final String errorCode = "HTTP_FILE_READ_ERROR";

    public HttpFileReadException() {
        super("요청 내 파일 읽기에 실패했습니다.");
    }

    public HttpFileReadException(String message) {
        super(message);
    }

    public HttpFileReadException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpFileReadException(Throwable cause) {
        super(cause);
    }
}