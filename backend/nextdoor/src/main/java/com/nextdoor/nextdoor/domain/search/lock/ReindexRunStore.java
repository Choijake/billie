package com.nextdoor.nextdoor.domain.search.lock;

import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexRunStore {

    private static final String STATE_KEY       = "REINDEX:STATE";
    private static final String FENCING_SEQ_KEY = "REINDEX:FENCING_SEQ";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper om;
    private final SearchProperties props;

    private DefaultRedisScript<Long> beginOrResume;
    private DefaultRedisScript<Long> attachNewIndexIfEmpty;
    private DefaultRedisScript<Long> checkpointAdvance;
    private DefaultRedisScript<Long> markIndexed;
    private DefaultRedisScript<Long> completeIfToken;

    @PostConstruct
    public void loadScripts() {
        beginOrResume        = script("lua/begin_or_resume.lua");
        attachNewIndexIfEmpty = script("lua/attach_new_index_if_empty.lua");
        checkpointAdvance    = script("lua/checkpoint_advance.lua");
        markIndexed          = script("lua/mark_indexed.lua");
        completeIfToken      = script("lua/complete_if_token.lua");
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    public ReindexRunState beginOrResume(Instant cutoff) {
        String runId = UUID.randomUUID().toString();
        String now = ms();
        String ttl = ttl();
        redisTemplate.execute(beginOrResume, List.of(STATE_KEY, FENCING_SEQ_KEY),
                runId, String.valueOf(cutoff.toEpochMilli()), now, ttl);
        return get().orElseThrow();
    }

    public boolean attachNewIndexIfEmpty(String newIndex) {
        Long r = redisTemplate.execute(attachNewIndexIfEmpty, List.of(STATE_KEY),
                newIndex, ms(), ttl());
        return r != null && r == 1L;
    }

    public boolean checkpointAdvance(long lastId, long processed) {
        Long r = redisTemplate.execute(checkpointAdvance, List.of(STATE_KEY),
                String.valueOf(lastId), String.valueOf(processed), ms(), ttl());
        return r != null && r == 1L;
    }

    public void markIndexed() {
        redisTemplate.execute(markIndexed, List.of(STATE_KEY), ms());
    }

    public boolean completeIfToken(long token) {
        Long r = redisTemplate.execute(completeIfToken, List.of(STATE_KEY),
                String.valueOf(token), ms());
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
        redisTemplate.expire(STATE_KEY, props.getReindex().getStateTtlSec(), TimeUnit.SECONDS);
    }

    public void clear() {
        redisTemplate.delete(STATE_KEY);
    }

    // ──── Helpers ─────────────────────────────────────────────────────────────

    private DefaultRedisScript<Long> script(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            String text = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(text, Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Lua 스크립트 로딩 실패: " + path, e);
        }
    }

    private String ms()  { return String.valueOf(Instant.now().toEpochMilli()); }
    private String ttl() { return String.valueOf(props.getReindex().getStateTtlSec()); }

    private long parseLong(Object v) {
        try { return v == null ? 0L : Long.parseLong(String.valueOf(v)); }
        catch (Exception e) { return 0L; }
    }
}
