package com.nextdoor.nextdoor.domain.post.search.outbox;

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
public class RedisCoalescer {
    private static final String KEY_FMT = "idx:post:%d";
    private static final long   TTL_SEC = 180;
    private static final long   FLUSH_THRESHOLD_MS = 30_000L;

    private final RedisTemplate<String, String> redisTemplate;
    private final SqsPublisher sqsPublisher;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, Long> trackedKeys = new ConcurrentHashMap<>();

    private Counter coalescerHit;
    private Counter coalescerMiss;
    private Counter coalescerFlushed;
    private DistributionSummary ttlAtFlush;

    @PostConstruct
    public void init() {
        this.coalescerHit = Counter.builder("coalescer.hit")
                .description("이미 존재하여 디바운스된 횟수").tag("entity","post")
                .register(meterRegistry);

        this.coalescerMiss = Counter.builder("coalescer.miss")
                .description("새로 세팅된 횟수").tag("entity","post")
                .register(meterRegistry);

        this.coalescerFlushed = Counter.builder("coalescer.flush")
                .description("TTL 임박 플러시 횟수").tag("entity","post")
                .register(meterRegistry);

        this.ttlAtFlush = DistributionSummary.builder("coalescer.flush.ttl_seconds")
                .description("플러시 직전 남은 TTL(초)")
                .baseUnit("seconds")
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder("coalescer.tracked_keys.size", trackedKeys, Map::size)
                .description("로컬에서 추적 중인 키 개수")
                .register(meterRegistry);
    }

    public void put(Long postId, Long version, String payload) {
        Timer.Sample totalTimer = Timer.start(meterRegistry);
        try {
            String key = KEY(postId);

            Timer.Sample getT = Timer.start(meterRegistry);
            String existing = redisTemplate.opsForValue().get(key);
            getT.stop(Timer.builder("redis.coalescer.get")
                    .description("Redis GET 연산 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));

            if (existing != null) {
                long curVer = jsons.readVersion(existing);
                if (version <= curVer) {
                    Timer.Sample expT = Timer.start(meterRegistry);
                    redisTemplate.expire(key, TTL_SEC, TimeUnit.SECONDS);
                    expT.stop(Timer.builder("redis.coalescer.expire")
                            .description("Redis EXPIRE 연산 시간")
                            .publishPercentileHistogram()
                            .register(meterRegistry));

                    coalescerHit.increment();
                    return;
                }
            }

            String store = jsons.wrap(version, payload);
            Timer.Sample setT = Timer.start(meterRegistry);
            redisTemplate.opsForValue().set(key, store, TTL_SEC, TimeUnit.SECONDS);
            setT.stop(Timer.builder("redis.coalescer.set")
                    .description("Redis SET 연산 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));

            trackedKeys.put(key, System.currentTimeMillis() + (TTL_SEC * 1000));
            coalescerMiss.increment();

        } finally {
            totalTimer.stop(Timer.builder("redis.coalescer.put.total")
                    .description("Redis Coalescer PUT 전체 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private String KEY(Long id) { return String.format(KEY_FMT, id); }

    @Scheduled(fixedDelay = 30_000)
    public void flushNearExpiry() {
        Timer.Sample totalTimer = Timer.start(meterRegistry);

        int processedCount = 0;
        int flushedCount = 0;
        int errorCount = 0;

        try {
            final long now = System.currentTimeMillis();

            List<String> dueKeys = new ArrayList<>();
            var it = trackedKeys.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                processedCount++;
                if (now >= e.getValue() - FLUSH_THRESHOLD_MS) {
                    dueKeys.add(e.getKey());
                    it.remove();
                }
            }
            if (dueKeys.isEmpty()) {
                meterRegistry.counter("coalescer.flush.batch.processed").increment(processedCount);
                meterRegistry.counter("coalescer.flush.batch.flushed").increment(flushedCount);
                meterRegistry.counter("coalescer.flush.batch.errors").increment(errorCount);

                totalTimer.stop(Timer.builder("redis.coalescer.flush.batch.total")
                        .description("플러시 배치 전체 시간")
                        .publishPercentileHistogram()
                        .register(meterRegistry));
                return;
            }

            Timer.Sample getBatchT = Timer.start(meterRegistry);
            List<Object> values = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                for (String k : dueKeys) {
                    conn.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            getBatchT.stop(Timer.builder("redis.coalescer.get_batch")
                    .description("Redis GET 배치(파이프라인) 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));

            List<String> sqsPayloadBatch = new ArrayList<>(10);
            List<String> keysToDelete = new ArrayList<>();

            for (int i = 0; i < dueKeys.size(); i++) {
                String val = (String) values.get(i);
                if (val == null) continue;

                String payload = jsons.readPayload(val);
                sqsPayloadBatch.add(payload);
                keysToDelete.add(dueKeys.get(i));

                if (sqsPayloadBatch.size() == 10) {
                    Timer.Sample sqsT = Timer.start(meterRegistry);
                    sqsPublisher.sendUpsertBatch(sqsPayloadBatch).join();
                    sqsT.stop(Timer.builder("redis.coalescer.sqs_send_batch")
                            .description("SQS 업서트 배치 전송 시간")
                            .publishPercentileHistogram()
                            .register(meterRegistry));
                    coalescerFlushed.increment(sqsPayloadBatch.size());
                    flushedCount += sqsPayloadBatch.size();
                    sqsPayloadBatch.clear();
                }
            }
            if (!sqsPayloadBatch.isEmpty()) {
                Timer.Sample sqsT = Timer.start(meterRegistry);
                sqsPublisher.sendUpsertBatch(sqsPayloadBatch).join();
                sqsT.stop(Timer.builder("redis.coalescer.sqs_send_batch")
                        .description("SQS 업서트 배치 전송 시간")
                        .publishPercentileHistogram()
                        .register(meterRegistry));
                coalescerFlushed.increment(sqsPayloadBatch.size());
                flushedCount += sqsPayloadBatch.size();
            }

            if (!keysToDelete.isEmpty()) {
                Timer.Sample delBatchT = Timer.start(meterRegistry);
                redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                    for (String k : keysToDelete) {
                        conn.keyCommands().del(k.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
                delBatchT.stop(Timer.builder("redis.coalescer.delete_batch")
                        .description("Redis DELETE 배치(파이프라인) 시간")
                        .publishPercentileHistogram()
                        .register(meterRegistry));
            }

            log.info("플러시 배치 완료 - 후보:{}, 전송/삭제:{}, 에러:{}", processedCount, flushedCount, errorCount);

            meterRegistry.counter("coalescer.flush.batch.processed").increment(processedCount);
            meterRegistry.counter("coalescer.flush.batch.flushed").increment(flushedCount);
            meterRegistry.counter("coalescer.flush.batch.errors").increment(errorCount);

        } catch (Exception e) {
            meterRegistry.counter("coalescer.flush.batch.errors").increment();
            log.warn("플러시 배치 중 오류", e);
        } finally {
            totalTimer.stop(Timer.builder("redis.coalescer.flush.batch.total")
                    .description("플러시 배치 전체 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }
}