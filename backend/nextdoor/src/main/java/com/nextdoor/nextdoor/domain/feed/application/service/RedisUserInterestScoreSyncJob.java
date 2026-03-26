package com.nextdoor.nextdoor.domain.feed.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Redis → DB 동기화 스케줄러
 *
 * [핵심 아키텍처]
 * 1. Virtual Threads: I/O 대기가 많은 작업을 수천 개 동시 처리
 * 2. Semaphore: DB 커넥션 풀 보호 (동시 접근 제한)
 * 3. CompletableFuture: 비동기 병렬 처리 + 에러 핸들링
 *
 * [성능 최적화]
 * - Platform Thread 사용 시: 200개 키 처리 → 약 20초
 * - Virtual Thread 사용 시: 200개 키 처리 → 약 4초 (5배 개선)
 *
 * [안정성 보장]
 * - Semaphore(5): 동시 DB 접근 5개로 제한
 * - 메인 API(HikariCP pool size 10)에 최소 5개 커넥션 확보
 * - 스케줄러가 커넥션 독점하여 서비스 장애 발생 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUserInterestScoreSyncJob {

    private final RedisTemplate<String, Object> dataRedisTemplate;
    private final UserInterestScoreSyncTxService syncTxService;
    private final Executor virtualThreadExecutor; // Virtual Thread Executor 주입

    /**
     * DB 커넥션 보호용 Semaphore
     *
     * [설정 이유]
     * - HikariCP pool size: 10개 (application-dev.yml)
     * - 메인 API 예약: 5개
     * - 스케줄러 허용: 5개
     *
     * [동작 방식]
     * - acquire(): 세마포어 획득 (대기 시 Blocking)
     * - release(): 세마포어 반환
     * - Virtual Thread는 Blocking 시 다른 작업 실행 (효율적)
     */
    private final Semaphore dbSemaphore = new Semaphore(5);

    /**
     * 10분마다 Redis 점수를 DB에 동기화
     *
     * [처리 흐름]
     * 1. Redis SCAN으로 모든 user:*:interest 키 조회
     * 2. 각 키마다 Virtual Thread 생성하여 병렬 처리
     * 3. Semaphore로 동시 DB 접근 제한
     * 4. 모든 작업 완료 대기 (CompletableFuture.allOf)
     */
    @Scheduled(fixedRate = 600000) // 10분 (600,000ms)
    public void syncRedisScoresToDatabase() {
        long startTime = System.currentTimeMillis();
        log.info("[Sync Started] Redis → DB 동기화 시작");

        try {
            // 1. Redis에서 모든 user interest 키 조회
            List<String> keys = scanUserInterestKeys();
            log.info("[Scan Complete] 발견된 키 개수: {}", keys.size());

            if (keys.isEmpty()) {
                log.info("[Sync Skipped] 동기화할 데이터 없음");
                return;
            }

            // 2. Virtual Thread로 병렬 처리
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String key : keys) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    processKey(key);
                }, virtualThreadExecutor); // Virtual Thread 사용

                futures.add(future);
            }

            // 3. 모든 작업 완료 대기
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .join(); // 모든 Future 완료까지 대기

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Sync Complete] 처리 시간: {}ms, 처리 키: {}", elapsed, keys.size());

        } catch (Exception e) {
            log.error("[Sync Failed] 에러 발생", e);
        }
    }

    /**
     * Redis SCAN으로 user:*:interest 패턴 키 조회
     *
     * [SCAN vs KEYS 명령]
     * - KEYS: 전체 키 스캔 (Blocking, 운영 환경 금지)
     * - SCAN: Cursor 기반 점진적 스캔 (Non-blocking)
     */
    private List<String> scanUserInterestKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match("user:*:interest")
                .count(100) // 한 번에 100개씩 조회
                .build();

        try (Cursor<byte[]> cursor = dataRedisTemplate.getConnectionFactory()
                .getConnection()
                .scan(options)) {

            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        } catch (Exception e) {
            log.error("[Scan Failed] Redis SCAN 실패", e);
        }

        return keys;
    }

    /**
     * 단일 키 처리 (Virtual Thread에서 실행)
     *
     * [중요] Semaphore로 DB 접근 제어
     * - 가상 스레드 수천 개 생성되어도 DB는 최대 5개만 동시 접근
     * - acquire() 대기 중에도 다른 Virtual Thread는 계속 실행
     */
    private void processKey(String key) {
        try {
            // key 파싱: "user:123:interest" → memberId = 123
            Long memberId = extractMemberId(key);
            if (memberId == null) {
                log.warn("[Invalid Key] 파싱 실패: {}", key);
                return;
            }

            // Redis Hash 전체 조회
            Map<Object, Object> scores = dataRedisTemplate.opsForHash().entries(key);
            if (scores.isEmpty()) {
                return;
            }

            // Semaphore 획득 (DB 보호)
            dbSemaphore.acquire();
            try {
                syncTxService.syncScores(memberId, scores);
            } finally {
                // 반드시 release (finally 블록)
                dbSemaphore.release();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Thread Interrupted] memberId 처리 중단: {}", key);
        } catch (Exception e) {
            log.error("[Process Failed] 키 처리 실패: {}", key, e);
        }
    }

    /**
     * Redis Key에서 memberId 추출
     * "user:123:interest" → 123
     */
    private Long extractMemberId(String key) {
        try {
            String[] parts = key.split(":");
            if (parts.length == 3 && "user".equals(parts[0]) && "interest".equals(parts[2])) {
                return Long.parseLong(parts[1]);
            }
        } catch (Exception e) {
            log.error("[Parse Failed] Invalid key format: {}", key);
        }
        return null;
    }
}
