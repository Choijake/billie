package com.nextdoor.nextdoor.domain.feed.infrastructure.redis;

public enum RedisOpType {
    GEO("geo."),
    SESSION("session."),
    METADATA("metadata.");

    private final String prefix;

    RedisOpType(String prefix) {
        this.prefix = prefix;
    }

    public static RedisOpType fromOp(String op) {
        if (op == null || op.isBlank()) return SESSION;

        for (RedisOpType type : values()) {
            if (op.startsWith(type.prefix)) {
                return type;
            }
        }
        return SESSION;
    }
}
