package com.nextdoor.nextdoor.domain.search;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.nextdoor.nextdoor.domain.post.exception.PostIndexException;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.search.dto.BatchTask;
import com.nextdoor.nextdoor.domain.search.dto.PostBatchResult;
import com.nextdoor.nextdoor.domain.search.dto.PostWithLikeCountDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 전체 리인덱싱 오케스트레이션 전담 서비스. (SRP)
 * 이전에 IndexingMessageConsumer.reindexAll() 90줄 메서드를
 * 단계별 private 메서드로 분해하고 별도 클래스로 격리한다.
 *
 * 단계: 락 획득 → 새 인덱스 준비 → 배치 루프 → in-flight 소진 → 앨리어스 스왑
 */
@Slf4j
@Service
@Profile("worker")
@RequiredArgsConstructor
public class ReindexOrchestrator {

    private static final int MAX_IN_FLIGHT = 2;

    private final PostRepository postRepository;
    private final ElasticsearchAsyncClient asyncEsClient;
    private final IndexLockService indexLockService;
    private final PostBatchReader postBatchReader;
    private final ReindexRunStore runStore;
    private final IndexAliasManager indexAliasManager;
    private final PostDocumentMapper documentMapper;

    public void reindexAll() {
        if (!indexLockService.acquireFullIndexLock()) {
            throw new PostIndexException("이미 전체 인덱싱 중입니다.");
        }
        String newIndex = null;
        try {
            ReindexRunState state = prepareRun();
            newIndex = state.getNewIndex();
            long token = state.getFencingToken();
            LocalDateTime cutoff = LocalDateTime.ofInstant(state.getCutoff(), ZoneId.systemDefault());

            long[] progress = {state.getLastId(), state.getProcessed()};
            progress = runBatchLoop(newIndex, cutoff, progress[0], progress[1]);

            finalizeIfTokenMatches(newIndex, token);
            log.info("전체 인덱싱 완료: newIndex={}, cutoff={}, processed={}", newIndex, state.getCutoff(), progress[1]);

        } catch (Exception e) {
            log.warn("인덱싱 오류: newIndex={}", newIndex, e);
            runStore.abort("error: " + e.getMessage());
            throw new PostIndexException("전체 인덱싱 중 오류", e);
        } finally {
            indexLockService.releaseFullIndexLock();
        }
    }

    // ──── private steps ──────────────────────────────────────────────────────

    private ReindexRunState prepareRun() throws Exception {
        LocalDateTime dbNow = postRepository.currentTimestamp();
        ReindexRunState state = runStore.beginOrResume(dbNow.atZone(ZoneId.systemDefault()).toInstant());

        if (state.getNewIndex() == null || state.getNewIndex().isBlank()) {
            String candidate = indexAliasManager.prepareNewIndex();
            runStore.attachNewIndexIfEmpty(candidate);
            state = runStore.get().orElse(state);
        }
        return state;
    }

    private long[] runBatchLoop(String newIndex, LocalDateTime cutoff, long lastId, long processed) {
        List<BatchTask> inFlight = new ArrayList<>(MAX_IN_FLIGHT);

        while (true) {
            PostBatchResult batch = postBatchReader.findNextBatch(lastId, cutoff);
            if (batch.getPosts().isEmpty()) break;

            List<BulkOperation> ops = buildBulkOps(batch.getPosts(), newIndex);
            CompletableFuture<Void> f = sendBulkAsync(ops);
            inFlight.add(new BatchTask(f, batch.getLastId(), batch.getPosts().size()));
            lastId = batch.getLastId();

            if (inFlight.size() >= MAX_IN_FLIGHT) {
                BatchTask done = inFlight.remove(0);
                done.future().join();
                processed += done.count();
                runStore.checkpointAdvance(done.lastId(), processed);
            }
        }

        processed = drainInFlight(inFlight, processed);
        runStore.markIndexed();
        return new long[]{lastId, processed};
    }

    private long drainInFlight(List<BatchTask> inFlight, long processed) {
        while (!inFlight.isEmpty()) {
            BatchTask done = inFlight.remove(0);
            done.future().join();
            processed += done.count();
            runStore.checkpointAdvance(done.lastId(), processed);
        }
        return processed;
    }

    private void finalizeIfTokenMatches(String newIndex, long token) throws Exception {
        ReindexRunState latest = runStore.get().orElseThrow();
        if (latest.getFencingToken() == token && newIndex.equals(latest.getNewIndex())) {
            indexAliasManager.finalizeAndSwap(newIndex, "1", "1s");
            boolean completed = runStore.completeIfToken(token);
            if (!completed) {
                log.info("펜싱 토큰 불일치 — 최신 런이 이미 완료했을 수 있음");
            }
        } else {
            log.info("더 최신 런 감지 — 앨리어스 스왑 건너뜀 (myToken={}, currentToken={})",
                    token, latest.getFencingToken());
        }
    }

    private List<BulkOperation> buildBulkOps(List<PostWithLikeCountDto> dtos, String index) {
        List<BulkOperation> ops = new ArrayList<>(dtos.size());
        for (PostWithLikeCountDto dto : dtos) {
            PostDocument doc = documentMapper.toDocument(dto);
            long version = documentMapper.toVersion(dto);
            ops.add(BulkOperation.of(b -> b.index(idx -> idx
                    .index(index)
                    .id(dto.getPostId().toString())
                    .version(version)
                    .versionType(VersionType.ExternalGte)
                    .document(doc))));
        }
        return ops;
    }

    private CompletableFuture<Void> sendBulkAsync(List<BulkOperation> ops) {
        BulkRetryExecutor exec = new BulkRetryExecutor(asyncEsClient);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (BulkRetryExecutor.Slice slice : exec.sliceBySize(ops)) {
            chain = chain.thenCompose(v -> exec.sendSliceWithRetry(slice));
        }
        return chain;
    }
}
