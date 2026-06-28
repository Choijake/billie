package com.nextdoor.nextdoor.domain.search.reconciliation;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import com.nextdoor.nextdoor.domain.search.indexing.SinglePostIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 체크포인트 기반 DB-ES 정합성 검증기.
 *
 * 마지막으로 완전히 성공한 윈도우 끝점을 DB에 저장한다. UPSERT와 DELETE 검증 중
 * 하나라도 실패하면 체크포인트를 전진시키지 않아 다음 실행에서 같은 구간을 재검사한다.
 */
@Component
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class TimeWindowReconciler {

    static final String CHECKPOINT_KEY = "post-index";

    private final JdbcTemplate jdbcTemplate;
    private final ElasticsearchClient esClient;
    private final SinglePostIndexer singlePostIndexer;
    private final SearchProperties props;
    private final ReconciliationCheckpointRepository checkpointRepository;

    public record ReconciliationResult(
            int windowChecked,
            int deletedChecked,
            int missingInEs,
            int staleInEs,
            int ghostInEs,
            int reindexed,
            int deletedFromEs,
            int failed,
            boolean checkpointAdvanced
    ) {
        static ReconciliationResult empty() {
            return new ReconciliationResult(0, 0, 0, 0, 0, 0, 0, 0, false);
        }

        @Override
        public String toString() {
            return String.format(
                    "windowChecked=%d, deletedChecked=%d, missingInEs=%d, staleInEs=%d, " +
                            "ghostInEs=%d, reindexed=%d, deletedFromEs=%d, failed=%d, checkpointAdvanced=%s",
                    windowChecked, deletedChecked, missingInEs, staleInEs, ghostInEs,
                    reindexed, deletedFromEs, failed, checkpointAdvanced);
        }
    }

    /**
     * 첫 실행에서는 now-windowMinutes를 시작점으로 사용한다. 이후 시작점은 DB 체크포인트가 결정한다.
     */
    public ReconciliationResult reconcile(long windowMinutes) {
        SearchProperties.Reconciliation config = props.getReconciliation();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime desiredEnd = now.minusMinutes(config.getBufferMinutes());

        ReconciliationCheckpoint checkpoint = loadOrInitializeCheckpoint(windowMinutes, now);
        LocalDateTime windowStart = checkpoint.getLastSuccessfulEnd();

        if (!windowStart.isBefore(desiredEnd)) {
            log.debug("Reconciliation: 처리 가능한 새 윈도우 없음 (checkpoint={}, desiredEnd={})",
                    windowStart, desiredEnd);
            return ReconciliationResult.empty();
        }

        LocalDateTime windowEnd = capWindow(windowStart, desiredEnd, config.getMaxWindowMinutes());

        // UPSERT와 DELETE 패스는 같은 체크포인트 윈도우를 독립적으로 조회한다.
        List<DbRecord> activeRecords = fetchDbRecords(windowStart, windowEnd, false);
        List<DbRecord> deletedRecords = fetchDbRecords(windowStart, windowEnd, true);

        log.info("Reconciliation 시작: start={}, end={}, active={}, deleted={}",
                windowStart, windowEnd, activeRecords.size(), deletedRecords.size());

        EsLookup activeLookup = fetchEsVersions(activeRecords, "UPSERT");
        EsLookup deletedLookup = fetchEsVersions(deletedRecords, "DELETE");

        int missing = 0;
        int stale = 0;
        int ghosts = 0;
        int reindexed = 0;
        int deletedFromEs = 0;
        int failed = (activeLookup.successful() ? 0 : 1) + (deletedLookup.successful() ? 0 : 1);

        // 기존 UPSERT 검증: ES에 없거나 DB보다 오래된 문서를 DB 최신 상태로 재색인한다.
        for (DbRecord db : activeRecords) {
            MismatchType mismatch = classify(db, activeLookup.versions());

            if (mismatch == MismatchType.MISSING) {
                missing++;
                log.debug("Reconciliation: postId={} ES에 없음 (DB version={})",
                        db.postId(), db.version());
            } else if (mismatch == MismatchType.STALE) {
                stale++;
                log.debug("Reconciliation: postId={} stale (dbVersion={}, esVersion={})",
                        db.postId(), db.version(), activeLookup.versions().get(db.postId()));
            }

            if (mismatch != MismatchType.OK) {
                try {
                    singlePostIndexer.indexSinglePost(db.postId());
                    reindexed++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Reconciliation: postId={} 재색인 실패", db.postId(), e);
                }
            }
        }

        // DELETE 검증: 삭제된 DB 레코드가 ES에 남아 있으면 ghost이므로 외부 버전으로 제거한다.
        for (DbRecord db : deletedRecords) {
            if (!deletedLookup.versions().containsKey(db.postId())) {
                continue;
            }

            ghosts++;
            try {
                DeleteOutcome outcome = deleteGhost(db);
                if (outcome == DeleteOutcome.DELETED) {
                    deletedFromEs++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Reconciliation: ghost postId={} 삭제 실패", db.postId(), e);
            }
        }

        boolean checkpointAdvanced = false;
        if (failed == 0) {
            checkpoint.advanceTo(windowEnd);
            checkpointRepository.saveAndFlush(checkpoint);
            checkpointAdvanced = true;
        } else {
            log.warn("Reconciliation: {}건 실패로 체크포인트 유지 (checkpoint={}, attemptedEnd={})",
                    failed, windowStart, windowEnd);
        }

        ReconciliationResult result = new ReconciliationResult(
                activeRecords.size(), deletedRecords.size(), missing, stale, ghosts,
                reindexed, deletedFromEs, failed, checkpointAdvanced);
        log.info("Reconciliation 완료: {}", result);
        return result;
    }

    private ReconciliationCheckpoint loadOrInitializeCheckpoint(long windowMinutes, LocalDateTime now) {
        return checkpointRepository.findById(CHECKPOINT_KEY)
                .orElseGet(() -> {
                    LocalDateTime initialEnd = now.minusMinutes(windowMinutes);
                    log.info("Reconciliation 체크포인트 초기화: key={}, lastSuccessfulEnd={}",
                            CHECKPOINT_KEY, initialEnd);
                    return checkpointRepository.saveAndFlush(
                            new ReconciliationCheckpoint(CHECKPOINT_KEY, initialEnd));
                });
    }

    private LocalDateTime capWindow(LocalDateTime start, LocalDateTime desiredEnd, long maxWindowMinutes) {
        if (maxWindowMinutes <= 0) {
            throw new IllegalArgumentException("search.reconciliation.max-window-minutes는 1 이상이어야 합니다.");
        }

        LocalDateTime maxEnd = start.plusMinutes(maxWindowMinutes);
        if (maxEnd.isBefore(desiredEnd)) {
            log.warn("Reconciliation 윈도우가 상한을 초과함: start={}, desiredEnd={}, maxWindow={}min. " +
                            "오래된 구간부터 {}까지 처리하며 반복 초과 시 전체 재색인을 검토해야 합니다.",
                    start, desiredEnd, maxWindowMinutes, maxEnd);
            return maxEnd;
        }
        return desiredEnd;
    }

    private List<DbRecord> fetchDbRecords(LocalDateTime windowStart,
                                          LocalDateTime windowEnd,
                                          boolean deleted) {
        String sql = """
                SELECT post_id,
                       UNIX_TIMESTAMP(updated_at) * 1000 AS version
                FROM post
                WHERE updated_at >= ?
                  AND updated_at < ?
                  AND deleted = ?
                ORDER BY post_id
                """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new DbRecord(rs.getLong("post_id"), rs.getLong("version")),
                windowStart, windowEnd, deleted);
    }

    /**
     * ES _mget 자체가 실패하면 빈 버전 맵과 unsuccessful을 반환한다.
     * UPSERT는 안전한 방향으로 전체 재색인을 시도하지만 체크포인트는 전진시키지 않는다.
     */
    private EsLookup fetchEsVersions(List<DbRecord> dbRecords, String passName) {
        if (dbRecords.isEmpty()) return EsLookup.success(Collections.emptyMap());

        List<String> ids = dbRecords.stream()
                .map(record -> String.valueOf(record.postId()))
                .toList();

        Map<Long, Long> versions = new HashMap<>(ids.size());
        boolean allItemsSuccessful = true;
        try {
            MgetResponse<PostDocument> response = esClient.mget(m -> m
                    .index(props.getIndexName())
                    .ids(ids), PostDocument.class);

            for (MultiGetResponseItem<PostDocument> item : response.docs()) {
                if (item.isFailure()) {
                    allItemsSuccessful = false;
                    log.warn("Reconciliation {}: ES _mget 개별 조회 실패 (postId={}, error={})",
                            passName, item.failure().id(), item.failure().error().reason());
                } else if (item.isResult() && item.result().found()) {
                    Long postId = Long.parseLong(item.result().id());
                    Long version = item.result().version();
                    if (version != null) versions.put(postId, version);
                }
            }
            return new EsLookup(versions, allItemsSuccessful);
        } catch (Exception e) {
            log.warn("Reconciliation {}: ES _mget 실패", passName, e);
            return EsLookup.failure();
        }
    }

    private DeleteOutcome deleteGhost(DbRecord db) throws Exception {
        try {
            esClient.delete(d -> d
                    .index(props.getIndexName())
                    .id(String.valueOf(db.postId()))
                    .version(db.version())
                    .versionType(VersionType.ExternalGte));
            log.debug("Reconciliation: ghost postId={} 삭제 완료 (version={})",
                    db.postId(), db.version());
            return DeleteOutcome.DELETED;
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                log.info("Reconciliation: ghost postId={} 삭제 version conflict 무시 (version={})",
                        db.postId(), db.version());
                return DeleteOutcome.VERSION_CONFLICT;
            }
            throw e;
        }
    }

    private boolean isVersionConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ElasticsearchException elasticsearchException
                    && elasticsearchException.status() == 409
                    && elasticsearchException.error() != null
                    && "version_conflict_engine_exception".equals(elasticsearchException.error().type())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    enum MismatchType { OK, MISSING, STALE }

    enum DeleteOutcome { DELETED, VERSION_CONFLICT }

    static MismatchType classify(DbRecord db, Map<Long, Long> esVersions) {
        Long esVersion = esVersions.get(db.postId());
        if (esVersion == null) return MismatchType.MISSING;
        if (esVersion < db.version()) return MismatchType.STALE;
        return MismatchType.OK;
    }

    record DbRecord(long postId, long version) {}

    private record EsLookup(Map<Long, Long> versions, boolean successful) {
        static EsLookup success(Map<Long, Long> versions) {
            return new EsLookup(versions, true);
        }

        static EsLookup failure() {
            return new EsLookup(Collections.emptyMap(), false);
        }
    }
}
