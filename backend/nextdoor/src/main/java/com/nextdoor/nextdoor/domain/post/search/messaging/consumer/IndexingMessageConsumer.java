package com.nextdoor.nextdoor.domain.post.search.messaging.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.post.exception.PostIndexException;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.post.search.*;
import com.nextdoor.nextdoor.domain.post.search.dto.BatchTask;
import com.nextdoor.nextdoor.domain.post.search.dto.PostBatchResult;
import com.nextdoor.nextdoor.domain.post.search.dto.PostWithLikeCountDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class IndexingMessageConsumer implements MessageConsumer {

    private static final int MAX_IN_FLIGHT_TASKS = 2;

    private final PostRepository postRepository;
    private final ElasticsearchAsyncClient asyncEsClient;
    private final IndexLockService indexLockService;
    private final PostSearchRepository elasticSearchRepository;
    private final PostBatchReader postBatchReader;
    private final ObjectMapper objectMapper;
    private final ReindexRunStore runStore;
    private final IndexAliasManager indexAliasManager;

    @Override
    public void processMessage(String payload) throws Exception {
        Map<String, Object> message = objectMapper.readValue(payload, Map.class);
        String action = (String) message.get("action");

        if ("REINDEX_ALL".equals(action)) {
            this.reindexAll();
        } else {
            Long postId = ((Number) message.get("postId")).longValue();
            if ("INDEX".equals(action)) {
                this.indexSinglePost(postId);
            } else if ("DELETE".equals(action)) {
                this.deleteSingleIndex(postId);
            } else {
                log.warn("처리할 수 없는 인덱싱 액션 : {}", action);
            }
        }
    }

    public void reindexAll() {
        if (!indexLockService.acquireFullIndexLock()) {
            throw new PostIndexException("이미 전체 인덱싱 중입니다.");
        }

        String newIndex = null;
        try {
            LocalDateTime dbNow = postRepository.currentTimestamp();
            ReindexRunState state = runStore.beginOrResume(dbNow.atZone(ZoneId.systemDefault()).toInstant());

            if (state.getNewIndex() == null || state.getNewIndex().isBlank()) {
                String candidate = indexAliasManager.prepareNewIndex();
                boolean attached = runStore.attachNewIndexIfEmpty(candidate);
                if (!attached) {
                    state = runStore.get().orElse(state);
                } else {
                    state = runStore.get().orElse(state);
                }
            }
            newIndex = state.getNewIndex();
            long token = state.getFencingToken();

            long lastId = state.getLastId();
            long processed = state.getProcessed();
            LocalDateTime cutoff = LocalDateTime.ofInstant(state.getCutoff(), ZoneId.systemDefault());

            List<BatchTask> inFlight = new ArrayList<>(MAX_IN_FLIGHT_TASKS);

            while (true) {
                PostBatchResult result = postBatchReader.findNextBatch(lastId, cutoff);
                List<PostWithLikeCountDto> postDtos = result.getPosts();
                if (postDtos.isEmpty()) break;

                List<BulkOperation> ops = new ArrayList<>(postDtos.size());
                for (PostWithLikeCountDto dto : postDtos) {
                    PostDocument doc = toDocument(dto);
                    long version = toVersion(dto);
                    String finalNewIndex = newIndex;
                    ops.add(BulkOperation.of(b -> b.index(idx -> idx
                            .index(finalNewIndex)
                            .id(dto.getPostId().toString())
                            .version(version)
                            .versionType(VersionType.ExternalGte)
                            .document(doc))));
                }

                CompletableFuture<Void> f = bulkIndexAsync(ops);
                inFlight.add(new BatchTask(f, result.getLastId(), postDtos.size()));
                lastId = result.getLastId();

                if (inFlight.size() >= MAX_IN_FLIGHT_TASKS) {
                    BatchTask done = inFlight.remove(0);
                    done.future().join();
                    processed += done.count();
                    runStore.checkpointAdvance(done.lastId(), processed);
                }
            }

            while (!inFlight.isEmpty()) {
                BatchTask done = inFlight.remove(0);
                done.future().join();
                processed += done.count();
                runStore.checkpointAdvance(done.lastId(), processed);
            }

            runStore.markIndexed();

            ReindexRunState latest = runStore.get().orElse(state);
            if (latest.getFencingToken() == token && newIndex.equals(latest.getNewIndex())) {
                indexAliasManager.finalizeAndSwap(newIndex, "1", "1s");
                boolean completed = runStore.completeIfToken(token);
                if (!completed) {
                    log.info("펜싱 토큰 불일치로 스왑 결과 기록 생략(이미 최신 런이 완료했을 수 있음)");
                }
            } else {
                log.info("더 최신 런이 감지되어 스왑 건너뜀 (myToken={}, currentToken={}, myIndex={}, currentIndex={})",
                        token, latest.getFencingToken(), newIndex, latest.getNewIndex());
            }

            log.info("전체 인덱싱 완료: newIndex={}, cutoff={}, processed={}",
                    newIndex, state.getCutoff(), processed);

        } catch (Exception e) {
            log.warn("새 인덱스 {} 로 적재/스왑 중 오류", newIndex, e);
            runStore.abort("error: " + e.getMessage());
            throw new PostIndexException("전체 인덱싱 중 오류", e);
        } finally {
            indexLockService.releaseFullIndexLock();
        }
    }



    public void indexSinglePost(Long postId) {
        PostWithLikeCountDto dto = postRepository.findDtoById(postId)
                .orElseThrow(() -> new PostIndexException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));

        PostDocument doc = toDocument(dto);

        if (indexLockService.isFullIndexLocked()) {
            indexLockService.addToPendingIndexQueue(doc);
            throw new PostIndexException("이미 전체 인덱싱 중입니다. 배치 색인을 기다려주세요.");
        } else {
            long version = toVersion(dto);
            asyncEsClient.index(i -> i
                    .index("posts")
                    .id(dto.getPostId().toString())
                    .version(version)
                    .versionType(VersionType.ExternalGte)
                    .document(doc)
            ).join();

            log.info("단건 색인 완료: id={}, version={}", doc.getId(), version);
        }
    }

    @Transactional
    public void deleteSingleIndex(Long postId) {
        if (indexLockService.isFullIndexLocked()) {
            throw new PostIndexException("이미 전체 인덱싱 중입니다.");
        }

        Long versionFromMsg = null;
        if (versionFromMsg != null) {
            asyncEsClient.delete(d -> d
                    .index("posts")
                    .id(postId.toString())
                    .version(versionFromMsg)
                    .versionType(VersionType.ExternalGte)
            ).join();
            log.info("단건 삭제 완료: id={}, version={}", postId, versionFromMsg);
        } else {
            elasticSearchRepository.deleteById(postId);
            log.info("단건 삭제: {}", postId);
        }
    }

    private CompletableFuture<Void> bulkIndexAsync(List<BulkOperation> operations) {
        BulkRetryExecutor exec = new BulkRetryExecutor(asyncEsClient);

        List<BulkRetryExecutor.Slice> slices = exec.sliceBySize(operations);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (BulkRetryExecutor.Slice s : slices) {
            chain = chain.thenCompose(v -> exec.sendSliceWithRetry(s));
        }
        return chain;
    }

    private PostDocument toDocument(PostWithLikeCountDto dto) {
        PostDocument.GeoPoint location = null;
        if (dto.getLatitude() != null && dto.getLongitude() != null) {
            location = new PostDocument.GeoPoint(dto.getLatitude(), dto.getLongitude());
        }

        return PostDocument.builder()
                .id(dto.getPostId())
                .title(dto.getTitle())
                .content(dto.getContent())
                .rentalFee(dto.getRentalFee())
                .deposit(dto.getDeposit())
                .address(dto.getAddress())
                .location(location)
                .category(dto.getCategory() != null ? dto.getCategory().name() : null)
                .likeCount(dto.getLikeCount().intValue())
                .createdAt(dto.getCreatedAt())
                .build();
    }

    private long toVersion(PostWithLikeCountDto dto) {
        return dto.getUpdatedAt()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}