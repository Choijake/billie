package com.nextdoor.nextdoor.domain.search.outbox;

import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class RedisCoalescer implements EventCoalescer {

    private final RedisTemplate<String, String> redisTemplate;
    private final SqsPublisher sqsPublisher;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;
    private final SearchProperties props;

    private final ConcurrentMap<String, Long> trackedKeys = new ConcurrentHashMap<>();

    private Counter coalescerHit;
    private Counter coalescerMiss;
    private Counter coalescerFlushed;
    private DistributionSummary ttlAtFlush;

    @PostConstruct
    public void init() {
        this.coalescerHit = Counter.builder("coalescer.hit")
                .description("이미 존재하여 디바운스된 횟수").tag("entity", "post")
                .register(meterRegistry);
        this.coalescerMiss = Counter.builder("coalescer.miss")
                .description("새로 세팅된 횟수").tag("entity", "post")
                .register(meterRegistry);
        this.coalescerFlushed = Counter.builder("coalescer.flush")
                .description("TTL 임박 플러시 횟수").tag("entity", "post")
                .register(meterRegistry);
        this.ttlAtFlush = DistributionSummary.builder("coalescer.flush.ttl_seconds")
                .description("플러시 직전 남은 TTL(초)").baseUnit("seconds")
                .publishPercentileHistogram().register(meterRegistry);

        Gauge.builder("coalescer.tracked_keys.size", trackedKeys, Map::size)
                .description("로컬에서 추적 중인 키 개수").register(meterRegistry);
    }

    public void put(Long postId, Long version, String payload) {
        Timer.Sample totalTimer = Timer.start(meterRegistry);
        try {
            String key = buildKey(postId);
            long ttlSec = props.getCoalescer().getTtlSec();

            Timer.Sample getT = Timer.start(meterRegistry);
            String existing = redisTemplate.opsForValue().get(key);
            getT.stop(timer("redis.coalescer.get", "Redis GET 연산 시간"));

            if (existing != null) {
                if (version <= jsons.readVersion(existing)) {
                    Timer.Sample expT = Timer.start(meterRegistry);
                    redisTemplate.expire(key, ttlSec, TimeUnit.SECONDS);
                    expT.stop(timer("redis.coalescer.expire", "Redis EXPIRE 연산 시간"));
                    coalescerHit.increment();
                    return;
                }
            }

            Timer.Sample setT = Timer.start(meterRegistry);
            redisTemplate.opsForValue().set(key, jsons.wrap(version, payload), ttlSec, TimeUnit.SECONDS);
            setT.stop(timer("redis.coalescer.set", "Redis SET 연산 시간"));

            trackedKeys.put(key, System.currentTimeMillis() + (ttlSec * 1000));
            coalescerMiss.increment();

        } finally {
            totalTimer.stop(timer("redis.coalescer.put.total", "Redis Coalescer PUT 전체 시간"));
        }
    }

    @Scheduled(fixedDelayString = "${search.coalescer.flush-delay-ms:30000}")
    public void flushNearExpiry() {
        Timer.Sample totalTimer = Timer.start(meterRegistry);
        int flushed = 0;

        try {
            List<String> dueKeys = collectDueKeys();
            if (dueKeys.isEmpty()) return;

            List<Object> values = fetchRedisValues(dueKeys);
            flushed = sendAndDeleteBatch(dueKeys, values);

            log.info("플러시 배치 완료 - 후보:{}, 전송:{}", dueKeys.size(), flushed);
        } catch (Exception e) {
            meterRegistry.counter("coalescer.flush.batch.errors").increment();
            log.warn("플러시 배치 중 오류", e);
        } finally {
            meterRegistry.counter("coalescer.flush.batch.flushed").increment(flushed);
            totalTimer.stop(timer("redis.coalescer.flush.batch.total", "플러시 배치 전체 시간"));
        }
    }

    private List<String> collectDueKeys() {
        long threshold = props.getCoalescer().getFlushThresholdMs();
        long now = System.currentTimeMillis();
        List<String> dueKeys = new ArrayList<>();

        var it = trackedKeys.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now >= entry.getValue() - threshold) {
                dueKeys.add(entry.getKey());
                it.remove();
            }
        }
        return dueKeys;
    }

    private List<Object> fetchRedisValues(List<String> keys) {
        Timer.Sample t = Timer.start(meterRegistry);
        try {
            return redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                for (String k : keys) {
                    conn.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        } finally {
            t.stop(timer("redis.coalescer.get_batch", "Redis GET 배치(파이프라인) 시간"));
        }
    }

    private int sendAndDeleteBatch(List<String> dueKeys, List<Object> values) {
        List<String> payloads = new ArrayList<>(dueKeys.size());
        List<String> keysToDelete = new ArrayList<>(dueKeys.size());

        for (int i = 0; i < dueKeys.size(); i++) {
            String val = (String) values.get(i);
            if (val == null) continue;
            payloads.add(jsons.readPayload(val));
            keysToDelete.add(dueKeys.get(i));
        }

        if (!payloads.isEmpty()) {
            Timer.Sample sqsT = Timer.start(meterRegistry);
            try {
                sqsPublisher.sendUpsertBatch(payloads).join();
                coalescerFlushed.increment(payloads.size());
            } finally {
                sqsT.stop(timer("redis.coalescer.sqs_send_batch", "SQS 업서트 배치 전송 시간"));
            }
        }

        if (!keysToDelete.isEmpty()) {
            Timer.Sample delT = Timer.start(meterRegistry);
            try {
                redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                    for (String k : keysToDelete) {
                        conn.keyCommands().del(k.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
            } finally {
                delT.stop(timer("redis.coalescer.delete_batch", "Redis DELETE 배치(파이프라인) 시간"));
            }
        }

        return payloads.size();
    }

    private String buildKey(Long id) {
        return String.format(props.getCoalescer().getKeyFormat(), id);
    }

    private Timer timer(String name, String desc) {
        return Timer.builder(name)
                .description(desc)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
