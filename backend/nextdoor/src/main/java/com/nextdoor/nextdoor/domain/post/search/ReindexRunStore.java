package com.nextdoor.nextdoor.domain.post.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReindexRunStore {
    private static final String STATE_KEY = "REINDEX:STATE";
    private static final String FENCING_SEQ_KEY = "REINDEX:FENCING_SEQ";
    private static final long TTL_SECONDS = 1800;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper om;

    private final DefaultRedisScript<Long> BEGIN_OR_RESUME = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'RUNNING' then
              return 0
            else
              local tok = redis.call('INCR', KEYS[2])
              redis.call('HSET', KEYS[1],
                'runId', ARGV[1],
                'cutoff', ARGV[2],
                'newIndex', '',         -- 나중에 attach
                'lastId', '0',
                'processed', '0',
                'fencingToken', tostring(tok),
                'status', 'RUNNING',
                'startedAt', ARGV[3],
                'updatedAt', ARGV[3]
              )
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
              return tok
            end
            """, Long.class
    );

    private final DefaultRedisScript<Long> ATTACH_NEW_INDEX_IF_EMPTY = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if status ~= 'RUNNING' then return 0 end
            local cur = redis.call('HGET', KEYS[1], 'newIndex')
            if not cur or cur == '' then
              redis.call('HSET', KEYS[1], 'newIndex', ARGV[1], 'updatedAt', ARGV[2])
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
              return 1
            else
              return 0
            end
            """, Long.class
    );

    private final DefaultRedisScript<Long> CHECKPOINT_ADVANCE = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if status ~= 'RUNNING' then return 0 end
            local cur = tonumber(redis.call('HGET', KEYS[1], 'lastId') or '0')
            local nxt = tonumber(ARGV[1])
            if nxt > cur then
              redis.call('HSET', KEYS[1],
                'lastId', ARGV[1],
                'processed', ARGV[2],
                'updatedAt', ARGV[3]
              )
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
              return 1
            else
              return 0
            end
            """, Long.class
    );

    private final DefaultRedisScript<Long> MARK_INDEXED = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'RUNNING' then
              redis.call('HSET', KEYS[1], 'status', 'INDEXED', 'updatedAt', ARGV[1])
              return 1
            else
              return 0
            end
            """, Long.class
    );

    private final DefaultRedisScript<Long> COMPLETE_IF_TOKEN = new DefaultRedisScript<>(
            """
            local tok = tonumber(redis.call('HGET', KEYS[1], 'fencingToken') or '-1')
            if tok == tonumber(ARGV[1]) then
              redis.call('HSET', KEYS[1], 'status', 'SUCCEEDED', 'updatedAt', ARGV[2])
              return 1
            else
              return 0
            end
            """, Long.class
    );

    public ReindexRunState beginOrResume(Instant cutoff) {
        String runId = UUID.randomUUID().toString();
        String now = String.valueOf(Instant.now().toEpochMilli());
        redisTemplate.execute(BEGIN_OR_RESUME, List.of(STATE_KEY, FENCING_SEQ_KEY),
                runId, String.valueOf(cutoff.toEpochMilli()), now, String.valueOf(TTL_SECONDS));
        return get().orElseThrow();
    }

    public boolean attachNewIndexIfEmpty(String newIndex) {
        String now = String.valueOf(Instant.now().toEpochMilli());
        Long r = redisTemplate.execute(ATTACH_NEW_INDEX_IF_EMPTY, List.of(STATE_KEY),
                newIndex, now, String.valueOf(TTL_SECONDS));
        return r != null && r == 1L;
    }

    public boolean checkpointAdvance(long lastId, long processed) {
        String now = String.valueOf(Instant.now().toEpochMilli());
        Long r = redisTemplate.execute(CHECKPOINT_ADVANCE, List.of(STATE_KEY),
                String.valueOf(lastId), String.valueOf(processed), now, String.valueOf(TTL_SECONDS));
        return r != null && r == 1L;
    }

    public void markIndexed() {
        String now = String.valueOf(Instant.now().toEpochMilli());
        redisTemplate.execute(MARK_INDEXED, List.of(STATE_KEY), now);
    }

    public boolean completeIfToken(long token) {
        String now = String.valueOf(Instant.now().toEpochMilli());
        Long r = redisTemplate.execute(COMPLETE_IF_TOKEN, List.of(STATE_KEY),
                String.valueOf(token), now);
        return r != null && r == 1L;
    }

    public Optional<ReindexRunState> get() {
        Map<Object, Object> m = redisTemplate.opsForHash().entries(STATE_KEY);
        if (m == null || m.isEmpty()) return Optional.empty();
        ReindexRunState s = new ReindexRunState();
        s.setRunId((String) m.get("runId"));
        s.setCutoff(Optional.ofNullable((String) m.get("cutoff"))
                .map(Long::parseLong).map(Instant::ofEpochMilli).orElse(null));
        s.setNewIndex((String) m.get("newIndex"));
        s.setLastId(parseLong(m.get("lastId")));
        s.setProcessed(parseLong(m.get("processed")));
        s.setFencingToken(parseLong(m.get("fencingToken")));
        s.setStatus(Optional.ofNullable((String) m.get("status"))
                .map(ReindexRunState.Status::valueOf).orElse(null));
        s.setStartedAt(Optional.ofNullable((String) m.get("startedAt"))
                .map(Long::parseLong).map(Instant::ofEpochMilli).orElse(null));
        s.setUpdatedAt(Optional.ofNullable((String) m.get("updatedAt"))
                .map(Long::parseLong).map(Instant::ofEpochMilli).orElse(null));
        s.setAbortReason((String) m.get("abortReason"));
        return Optional.of(s);
    }

    public void abort(String reason) {
        redisTemplate.opsForHash().put(STATE_KEY, "status", "ABORTED");
        redisTemplate.opsForHash().put(STATE_KEY, "abortReason", reason);
        redisTemplate.expire(STATE_KEY, TTL_SECONDS, TimeUnit.SECONDS);
    }

    public void clear() {
        redisTemplate.delete(STATE_KEY);
    }

    private long parseLong(Object v) {
        try { return v == null ? 0L : Long.parseLong(String.valueOf(v)); }
        catch (Exception e) { return 0L; }
    }
}