package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.domain.BatchExecutionLog;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.BatchExecutionLogRepository;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.UserInterestScoreRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 관심도 점수 감쇠 배치.
 *
 * <p>50만 행(DAU 10만 x 평균 카테고리 5개) 기준 설계.
 * PK range 기반 청크 처리로 lock 경합을 완화한다:
 * <ul>
 *   <li>lock 범위: 청크 단위(5000행)로 제한. 청크당 lock 보유 시간 ~14ms, UPSERT 최대 대기 ~14ms</li>
 *   <li>undo log 크기: 50만 행 단일 트랜잭션(~50MB) 대비 청크 5000행(~500KB)으로 100배 감소</li>
 *   <li>롤백 안전성: 실패 시 해당 청크만 롤백, 이전 청크는 커밋 유지</li>
 * </ul>
 *
 * <p>멀티 인스턴스 배포 시 ShedLock 등 분산 락 추가 필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInterestScoreDecayJob {

    private static final String JOB_NAME = "user-interest-score-decay";
    private static final int CHUNK_SIZE = 5000;
    private static final double DECAY_RATE = 0.7;

    private final UserInterestScoreRepository scoreRepository;
    private final BatchExecutionLogRepository logRepository;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "${feed.scoring.decay-cron:0 0 4 * * *}")
    public void decayScores() {
        BatchExecutionLog executionLog = logRepository.save(BatchExecutionLog.start(JOB_NAME));
        log.info("[Decay Started] jobId={}, chunkSize={}", executionLog.getId(), CHUNK_SIZE);

        try {
            long totalAffected = 0;
            Long maxId = scoreRepository.findMaxScoreId();

            if (maxId != null) {
                for (long start = 1; start <= maxId; start += CHUNK_SIZE) {
                    long end = Math.min(start + CHUNK_SIZE - 1, maxId);
                    int updated = scoreRepository.applyDecayInRange(DECAY_RATE, start, end);
                    int deleted = scoreRepository.deleteZeroScoresInRange(start, end);
                    totalAffected += updated + deleted;
                }
            }

            executionLog.complete(totalAffected);
            logRepository.save(executionLog);

            meterRegistry.counter("batch.decay", "status", "success").increment();
            meterRegistry.gauge("batch.decay.affected_rows", totalAffected);

            log.info("[Decay Complete] jobId={}, totalAffected={}",
                    executionLog.getId(), totalAffected);

        } catch (Exception e) {
            executionLog.fail(e.getMessage());
            logRepository.save(executionLog);

            meterRegistry.counter("batch.decay", "status", "failure").increment();

            log.error("[Decay Failed] jobId={}", executionLog.getId(), e);
            throw e;
        }
    }
}
