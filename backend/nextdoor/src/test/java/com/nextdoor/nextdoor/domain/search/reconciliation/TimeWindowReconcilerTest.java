package com.nextdoor.nextdoor.domain.search.reconciliation;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.RequestBase;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetError;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.util.ObjectBuilder;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import com.nextdoor.nextdoor.domain.search.indexing.SinglePostIndexer;
import com.nextdoor.nextdoor.domain.search.reconciliation.TimeWindowReconciler.DbRecord;
import com.nextdoor.nextdoor.domain.search.reconciliation.TimeWindowReconciler.MismatchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeWindowReconcilerTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock ElasticsearchClient esClient;
    @Mock SinglePostIndexer singlePostIndexer;
    @Mock SearchProperties searchProperties;
    @Mock ReconciliationCheckpointRepository checkpointRepository;

    private SearchProperties.Reconciliation config;
    private ReconciliationCheckpoint checkpoint;
    private TimeWindowReconciler reconciler;

    @BeforeEach
    void setUp() {
        config = new SearchProperties.Reconciliation();
        checkpoint = new ReconciliationCheckpoint(
                TimeWindowReconciler.CHECKPOINT_KEY,
                LocalDateTime.now().minusMinutes(15));

        reconciler = new TimeWindowReconciler(
                jdbcTemplate, esClient, singlePostIndexer, searchProperties, checkpointRepository);

        lenient().when(searchProperties.getReconciliation()).thenReturn(config);
        lenient().when(searchProperties.getIndexName()).thenReturn("posts");
        lenient().when(checkpointRepository.findById(TimeWindowReconciler.CHECKPOINT_KEY))
                .thenReturn(Optional.of(checkpoint));
    }

    @Test
    void ES에_문서가_없으면_MISSING이다() {
        DbRecord db = new DbRecord(1L, 100L);
        assertThat(TimeWindowReconciler.classify(db, Map.of())).isEqualTo(MismatchType.MISSING);
    }

    @Test
    void ES_version이_DB보다_낮으면_STALE이다() {
        DbRecord db = new DbRecord(1L, 200L);
        assertThat(TimeWindowReconciler.classify(db, Map.of(1L, 100L))).isEqualTo(MismatchType.STALE);
    }

    @Test
    void ES_version이_DB와_같거나_높으면_OK이다() {
        DbRecord db = new DbRecord(1L, 100L);
        assertThat(TimeWindowReconciler.classify(db, Map.of(1L, 100L))).isEqualTo(MismatchType.OK);
        assertThat(TimeWindowReconciler.classify(db, Map.of(1L, 200L))).isEqualTo(MismatchType.OK);
    }

    @Test
    void UPSERT_누락과_stale을_재색인하고_성공하면_체크포인트를_갱신한다() throws Exception {
        stubDbQueries(
                List.of(new long[]{1L, 100L}, new long[]{2L, 200L}, new long[]{3L, 300L}),
                List.of());
        stubMgetResponses(mgetResponse(
                foundItem("1", 100L),
                foundItem("2", 100L),
                missingItem("3")));

        LocalDateTime before = checkpoint.getLastSuccessfulEnd();
        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.windowChecked()).isEqualTo(3);
        assertThat(result.staleInEs()).isEqualTo(1);
        assertThat(result.missingInEs()).isEqualTo(1);
        assertThat(result.reindexed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(checkpoint.getLastSuccessfulEnd()).isAfter(before);
        verify(singlePostIndexer).indexSinglePost(2L);
        verify(singlePostIndexer).indexSinglePost(3L);
        verify(checkpointRepository).saveAndFlush(checkpoint);
    }

    @Test
    void 재색인이_한_건이라도_실패하면_체크포인트를_갱신하지_않는다() throws Exception {
        stubDbQueries(List.of(new long[]{1L, 100L}), List.of());
        stubMgetResponses(mgetResponse(missingItem("1")));
        doThrow(new RuntimeException("ES 색인 실패")).when(singlePostIndexer).indexSinglePost(1L);
        LocalDateTime initial = checkpoint.getLastSuccessfulEnd();

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isFalse();
        assertThat(checkpoint.getLastSuccessfulEnd()).isEqualTo(initial);
        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void 성공하면_체크포인트를_윈도우_끝점으로_갱신한다() throws Exception {
        stubDbQueries(List.of(), List.of());
        LocalDateTime beforeRun = LocalDateTime.now().minusMinutes(config.getBufferMinutes() + 1);

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(checkpoint.getLastSuccessfulEnd()).isAfter(beforeRun);
        verify(checkpointRepository).saveAndFlush(checkpoint);
        verifyNoInteractions(esClient, singlePostIndexer);
    }

    @Test
    void 밀린_윈도우가_상한을_초과하면_오래된_구간부터_상한만큼만_처리한다() throws Exception {
        config.setMaxWindowMinutes(60);
        checkpoint = new ReconciliationCheckpoint(
                TimeWindowReconciler.CHECKPOINT_KEY,
                LocalDateTime.now().minusMinutes(180));
        when(checkpointRepository.findById(TimeWindowReconciler.CHECKPOINT_KEY))
                .thenReturn(Optional.of(checkpoint));
        LocalDateTime initial = checkpoint.getLastSuccessfulEnd();
        stubDbQueries(List.of(), List.of());

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(checkpoint.getLastSuccessfulEnd()).isEqualTo(initial.plusMinutes(60));
    }

    @Test
    void 삭제된_게시글이_ES에_남아있으면_GHOST로_판정하고_삭제한다() throws Exception {
        stubDbQueries(List.of(), List.of(new long[]{10L, 500L}));
        stubMgetResponses(mgetResponse(foundItem("10", 400L)));
        stubDeleteSuccess();

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.deletedChecked()).isEqualTo(1);
        assertThat(result.ghostInEs()).isEqualTo(1);
        assertThat(result.deletedFromEs()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.checkpointAdvanced()).isTrue();

        ArgumentCaptor<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>> requestCaptor =
                ArgumentCaptor.forClass(Function.class);
        verify(esClient).delete(requestCaptor.capture());
        DeleteRequest request = requestCaptor.getValue().apply(new DeleteRequest.Builder()).build();
        assertThat(request.id()).isEqualTo("10");
        assertThat(request.version()).isEqualTo(500L);
    }

    @Test
    void 삭제된_게시글이_ES에_없으면_정상이므로_삭제를_호출하지_않는다() throws Exception {
        stubDbQueries(List.of(), List.of(new long[]{10L, 500L}));
        stubMgetResponses(mgetResponse(missingItem("10")));

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.ghostInEs()).isZero();
        assertThat(result.deletedFromEs()).isZero();
        assertThat(result.checkpointAdvanced()).isTrue();
        verify(esClient, never()).delete(
                ArgumentMatchers.<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>>any());
    }

    @Test
    void GHOST_삭제가_실패하면_체크포인트를_갱신하지_않는다() throws Exception {
        stubDbQueries(List.of(), List.of(new long[]{10L, 500L}));
        stubMgetResponses(mgetResponse(foundItem("10", 400L)));
        doThrow(new RuntimeException("ES 삭제 실패")).when(esClient).delete(
                ArgumentMatchers.<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>>any());
        LocalDateTime initial = checkpoint.getLastSuccessfulEnd();

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isFalse();
        assertThat(checkpoint.getLastSuccessfulEnd()).isEqualTo(initial);
        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void GHOST_삭제_version_conflict는_무시하고_체크포인트를_갱신한다() throws Exception {
        stubDbQueries(List.of(), List.of(new long[]{10L, 500L}));
        stubMgetResponses(mgetResponse(foundItem("10", 600L)));

        ElasticsearchException conflict = mock(ElasticsearchException.class);
        ErrorCause errorCause = mock(ErrorCause.class);
        when(conflict.status()).thenReturn(409);
        when(conflict.error()).thenReturn(errorCause);
        when(errorCause.type()).thenReturn("version_conflict_engine_exception");
        doThrow(conflict).when(esClient).delete(
                ArgumentMatchers.<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>>any());

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.ghostInEs()).isEqualTo(1);
        assertThat(result.deletedFromEs()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.checkpointAdvanced()).isTrue();
    }

    @Test
    void ES_mget_실패시_UPSERT는_재색인하지만_체크포인트는_갱신하지_않는다() throws Exception {
        stubDbQueries(List.of(new long[]{1L, 100L}, new long[]{2L, 200L}), List.of());
        doThrow(new RuntimeException("ES 연결 실패"))
                .when(esClient).mget(
                        ArgumentMatchers.<Function<MgetRequest.Builder, ObjectBuilder<MgetRequest>>>any(),
                        eq(PostDocument.class));

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.reindexed()).isEqualTo(2);
        assertThat(result.missingInEs()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isFalse();
        verify(singlePostIndexer).indexSinglePost(1L);
        verify(singlePostIndexer).indexSinglePost(2L);
        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void ES_mget_개별_실패도_체크포인트를_갱신하지_않는다() throws Exception {
        stubDbQueries(List.of(new long[]{1L, 100L}), List.of());
        stubMgetResponses(mgetResponse(failureItem("1")));

        TimeWindowReconciler.ReconciliationResult result = reconciler.reconcile(20);

        assertThat(result.reindexed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isFalse();
        verify(singlePostIndexer).indexSinglePost(1L);
        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void 체크포인트가_없으면_기존_windowMinutes로_초기화한다() throws Exception {
        when(checkpointRepository.findById(TimeWindowReconciler.CHECKPOINT_KEY))
                .thenReturn(Optional.empty());
        when(checkpointRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubDbQueries(List.of(), List.of());
        LocalDateTime expectedLowerBound = LocalDateTime.now().minusMinutes(21);

        reconciler.reconcile(20);

        ArgumentCaptor<ReconciliationCheckpoint> captor =
                ArgumentCaptor.forClass(ReconciliationCheckpoint.class);
        verify(checkpointRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
        ReconciliationCheckpoint initialized = captor.getAllValues().getFirst();
        assertThat(initialized.getLastSuccessfulEnd()).isAfter(expectedLowerBound);
    }

    private void stubDbQueries(List<long[]> activeRecords, List<long[]> deletedRecords) {
        doAnswer(invocation -> {
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            Object[] arguments = invocation.getArguments();
            boolean deleted = Boolean.TRUE.equals(arguments[arguments.length - 1]);
            List<long[]> source = deleted ? deletedRecords : activeRecords;
            java.util.List<Object> mapped = new java.util.ArrayList<>();
            for (long[] record : source) {
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getLong("post_id")).thenReturn(record[0]);
                when(resultSet.getLong("version")).thenReturn(record[1]);
                mapped.add(rowMapper.mapRow(resultSet, mapped.size()));
            }
            return mapped;
        }).when(jdbcTemplate).query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(), any(), any());
    }

    @SafeVarargs
    private final void stubMgetResponses(MgetResponse<PostDocument>... responses) throws Exception {
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> responses[index.getAndIncrement()])
                .when(esClient).mget(
                        ArgumentMatchers.<Function<MgetRequest.Builder, ObjectBuilder<MgetRequest>>>any(),
                        eq(PostDocument.class));
    }

    @SafeVarargs
    private final MgetResponse<PostDocument> mgetResponse(MultiGetResponseItem<PostDocument>... items) {
        @SuppressWarnings("unchecked")
        MgetResponse<PostDocument> response = mock(MgetResponse.class);
        when(response.docs()).thenReturn(List.of(items));
        return response;
    }

    private MultiGetResponseItem<PostDocument> foundItem(String id, long version) {
        @SuppressWarnings("unchecked")
        MultiGetResponseItem<PostDocument> item = mock(MultiGetResponseItem.class);
        @SuppressWarnings("unchecked")
        GetResult<PostDocument> result = mock(GetResult.class);
        when(item.isResult()).thenReturn(true);
        when(item.result()).thenReturn(result);
        when(result.found()).thenReturn(true);
        when(result.id()).thenReturn(id);
        when(result.version()).thenReturn(version);
        return item;
    }

    private MultiGetResponseItem<PostDocument> missingItem(String id) {
        @SuppressWarnings("unchecked")
        MultiGetResponseItem<PostDocument> item = mock(MultiGetResponseItem.class);
        @SuppressWarnings("unchecked")
        GetResult<PostDocument> result = mock(GetResult.class);
        when(item.isResult()).thenReturn(true);
        when(item.result()).thenReturn(result);
        when(result.found()).thenReturn(false);
        lenient().when(result.id()).thenReturn(id);
        return item;
    }

    private MultiGetResponseItem<PostDocument> failureItem(String id) {
        @SuppressWarnings("unchecked")
        MultiGetResponseItem<PostDocument> item = mock(MultiGetResponseItem.class);
        MultiGetError failure = mock(MultiGetError.class);
        ErrorCause error = mock(ErrorCause.class);
        when(item.isFailure()).thenReturn(true);
        when(item.failure()).thenReturn(failure);
        when(failure.id()).thenReturn(id);
        when(failure.error()).thenReturn(error);
        when(error.reason()).thenReturn("조회 실패");
        return item;
    }

    private void stubDeleteSuccess() throws Exception {
        DeleteResponse response = mock(DeleteResponse.class);
        doReturn(response).when(esClient).delete(
                ArgumentMatchers.<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>>any());
    }
}
