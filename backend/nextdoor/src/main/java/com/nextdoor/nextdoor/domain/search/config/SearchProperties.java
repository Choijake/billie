package com.nextdoor.nextdoor.domain.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * search 모듈 전역 설정. (OCP)
 * 기존에 각 클래스에 하드코딩됐던 50개 이상의 매직넘버를 한 곳에서 관리한다.
 * application.yml의 'search.*' 접두사로 오버라이드 가능.
 */
@Data
@Component
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    /** Elasticsearch 인덱스명 */
    private String indexName = "posts";

    private final Outbox outbox = new Outbox();
    private final Coalescer coalescer = new Coalescer();
    private final Bulk bulk = new Bulk();
    private final Reindex reindex = new Reindex();
    private final Sqs sqs = new Sqs();
    private final Lock lock = new Lock();
    private final Suggestion suggestion = new Suggestion();

    @Data
    public static class Outbox {
        /** 한 번에 클레임할 Outbox 이벤트 수 */
        private int batchSize = 100;
        /** Outbox 클레임 TTL (초) */
        private int claimTtlSec = 120;
        /** 폴링 주기 (ms) */
        private long pollDelayMs = 2000;
        /** markPublished IN 절 청크 크기 */
        private int markChunkSize = 1000;
    }

    @Data
    public static class Coalescer {
        /** Redis 키 포맷 */
        private String keyFormat = "idx:post:%d";
        /** 키 TTL (초) */
        private long ttlSec = 180;
        /** 만료 임박 기준 (ms). 남은 TTL이 이 값 이하이면 플러시 */
        private long flushThresholdMs = 30_000;
        /** 플러시 스케줄 주기 (ms) */
        private long flushDelayMs = 30_000;
    }

    @Data
    public static class Bulk {
        /** ES 벌크 병렬 처리 수 */
        private int maxConcurrency = 2;
        /** 슬라이스당 최대 액션 수 */
        private int sliceMaxActions = 300;
        /** 정기 플러시 주기 (ms) */
        private long scheduleMs = 1000;
        /** 슬라이싱 목표 바이트 */
        private int targetSliceBytes = 8 * 1024 * 1024;
        /** 슬라이싱 최대 바이트 */
        private int maxSliceBytes = 15 * 1024 * 1024;
    }

    @Data
    public static class Reindex {
        /** Redis 상태 TTL (초) */
        private long stateTtlSec = 1800;
        /** 리인덱싱 배치 크기 */
        private int batchSize = 1000;
        /** 동시 in-flight 배치 수 */
        private int maxInFlight = 2;
    }

    @Data
    public static class Sqs {
        /** SQS 배치 전송 한도 */
        private int batchLimit = 10;
    }

    @Data
    public static class Lock {
        /** 전체 인덱싱 분산 락 키 */
        private String fullIndexLockKey = "FULL_INDEX_LOCK";
        /** 보류 인덱스 큐 키 */
        private String pendingQueueKey = "PENDING_INDEX_QUEUE";
        /** 락 TTL (초) */
        private long ttlSec = 7200;
    }

    @Data
    public static class Suggestion {
        /** 자동완성 최대 결과 수 */
        private int maxSuggestions = 4;
        /** Redis 키 접두사 */
        private String keyPrefix = "search:keywords:";
    }
}
