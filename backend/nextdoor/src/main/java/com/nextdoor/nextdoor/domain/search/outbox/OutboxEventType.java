package com.nextdoor.nextdoor.domain.search.outbox;

/**
 * Outbox 이벤트 타입 enum. (OCP)
 * 기존에 "DELETE".equals(eventType) 같은 문자열 비교를 타입 안전하게 대체한다.
 */
public enum OutboxEventType {
    UPSERT, DELETE;

    public static OutboxEventType from(String value) {
        return valueOf(value.toUpperCase());
    }
}
