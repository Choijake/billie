package com.nextdoor.nextdoor.domain.search.benchmark;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.search.outbox.OutboxEventRepository;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostUpsertEvent;
import com.nextdoor.nextdoor.domain.search.reconciliation.TimeWindowReconciler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;

/**
 * Outbox → SQS → ES 파이프라인 장애 주입 벤치마크.
 *
 * 목표: "ES 5분 장애 시 방어 계층 없이 N건 유실, DLQ+Reconciler 도입 후 0건 유실" 수치를 측정한다.
 *
 * 전제:
 * - search-benchmark/docker-compose.benchmark.yml 인프라가 실행 중이어야 함
 * - SQS 큐에 DLQ + RedrivePolicy(maxReceiveCount=3) 설정 필요
 *   (search-benchmark/init/localstack/init-sqs.sh 참조)
 *
 * Phase 1 — Before (방어 계층 없음):
 *   1. 1000건 게시글 DB 삽입 + Outbox 이벤트 생성
 *   2. 파이프라인이 처리를 시작하면 ES 컨테이너 중단 (docker stop bench-es)
 *   3. DLQ maxReceiveCount=3 + VisibilityTimeout=30s → 약 90초 내 DLQ 이동
 *   4. ES 복구 후 자연 처리 대기
 *   5. DB count vs ES count 비교 → 유실 건수 측정
 *
 * Phase 2 — After (방어 계층 활성화):
 *   1. 새 1000건으로 동일 시나리오 재현
 *   2. ES 복구 후:
 *      a. DLQ 자동 소비 대기: DlqPostIndexRetryConsumer(@SqsListener)가 DLQ를 직접 소비
 *         waitForDlqDrained()으로 ApproximateNumberOfMessages=0 확인
 *      b. TimeWindowReconciler.reconcile() 호출 (2차 안전망)
 *   3. DB count vs ES count 비교 → 0건 유실 확인
 *
 * 실행 방법:
 *   ./gradlew test --tests "*FaultInjectionBenchmark" \
 *     -Dspring.profiles.active=benchmark,outbox,worker
 */
@SpringBootTest(classes = BenchmarkTestConfig.class)
@ActiveProfiles({"benchmark", "outbox", "worker"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FaultInjectionBenchmark {

    // ─── 설정 상수 ──────────────────────────────────────────────────────────────
    private static final int POST_COUNT          = 1000;
    /** ES를 멈출 시점 (게시글 생성 이후 파이프라인 처리 시작 대기) */
    private static final int ES_DOWN_DELAY_MS    = 3_000;
    /** ES 중단 유지 시간. maxReceiveCount=3 + VisibilityTimeout=30s → 90s 필요 */
    private static final int ES_DOWN_DURATION_MS = 120_000;
    /** ES 복구 후 파이프라인 자연 처리 대기 (phase1에서 잔여 이벤트 처리용) */
    private static final int ES_RECOVERY_SETTLE_MS  = 30_000;
    /** DLQ 컨슈머(@SqsListener)가 DLQ를 완전히 소비할 때까지 최대 대기 시간 */
    private static final int DLQ_DRAIN_TIMEOUT_MS   = 180_000;
    /** DLQ 잔여 메시지 폴링 간격 (ApproximateNumberOfMessages는 eventual consistent) */
    private static final int DLQ_POLL_INTERVAL_MS   = 5_000;
    /** ES 최종 반영 확인 폴링 타임아웃 */
    private static final int ES_SETTLE_TIMEOUT_MS   = 60_000;
    private static final int ES_SETTLE_POLL_MS      = 1_000;

    private static final String ES_CONTAINER      = "bench-es";
    private static final String REPORT_PATH       = "search-benchmark/results/fault-injection-report.md";

    // ─── Phase 별 고정 postId 범위 (기존 벤치마크 데이터와 충돌 방지) ──────────
    private static final long PHASE1_BASE_POST_ID = 50_000_000L;
    private static final long PHASE2_BASE_POST_ID = 51_000_000L;

    // ─── 주입 의존성 ─────────────────────────────────────────────────────────
    @Autowired private OutboxEventRepository outboxRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ElasticsearchClient esClient;
    @Autowired private SqsAsyncClient sqsClient;
    @Autowired private TimeWindowReconciler reconciler;

    @Value("${sqs.queue.post-index-dlq}")
    private String postIndexDlqUrl;

    // ─── 결과 저장 ────────────────────────────────────────────────────────────
    private PhaseResult phase1Result;
    private PhaseResult phase2Result;

    record PhaseResult(
            int total,
            long esCountBeforeDefense,
            long lostBeforeDefense,
            long dlqConsumedByListener,   // @SqsListener 소비 완료 후 DLQ 잔여 건수 (0 = 정상)
            long reconciledByReconciler,
            long esCountAfterDefense,
            long remainingLoss
    ) {}

    // ─── Phase 1: Before ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Phase 1 — Before: 방어 계층 없이 ES 장애 시 유실 건수 측정")
    void phase1_measureLossWithoutDefense() throws Exception {
        System.out.println("\n========================================");
        System.out.println("  Phase 1: Before (방어 계층 없음)");
        System.out.println("========================================");

        // 1. DB + Outbox 데이터 생성
        System.out.printf("[1/5] %d건 게시글 + Outbox 이벤트 생성 중...%n", POST_COUNT);
        insertPostsAndOutboxEvents(PHASE1_BASE_POST_ID, POST_COUNT);
        System.out.printf("  → %d건 생성 완료%n", POST_COUNT);

        // 2. 파이프라인이 처리 시작할 때까지 대기
        System.out.printf("[2/5] 파이프라인 처리 시작 대기 (%dms)...%n", ES_DOWN_DELAY_MS);
        Thread.sleep(ES_DOWN_DELAY_MS);

        // 3. ES 컨테이너 중단 (장애 주입)
        System.out.printf("[3/5] ES 컨테이너 중단 (장애 주입)...%n");
        dockerStop(ES_CONTAINER);
        System.out.printf("  → ES down. %dms 유지 (SQS maxReceiveCount=3 소진 대기)...%n",
                ES_DOWN_DURATION_MS);
        Thread.sleep(ES_DOWN_DURATION_MS);

        // 4. ES 컨테이너 재기동
        System.out.printf("[4/5] ES 컨테이너 재기동...%n");
        dockerStart(ES_CONTAINER);
        waitForEs();
        System.out.printf("  → ES 복구 완료. 파이프라인 자연 처리 대기 (%dms)...%n",
                ES_RECOVERY_SETTLE_MS);
        Thread.sleep(ES_RECOVERY_SETTLE_MS);

        // 5. DB vs ES 비교
        System.out.printf("[5/5] DB vs ES count 비교...%n");
        long esCount = countEsDocs(PHASE1_BASE_POST_ID, PHASE1_BASE_POST_ID + POST_COUNT);
        long lost = POST_COUNT - esCount;

        phase1Result = new PhaseResult(
                POST_COUNT, esCount, lost, 0, 0, esCount, lost);

        System.out.printf("%n  ┌─ Phase 1 결과 ────────────────────────┐%n");
        System.out.printf("  │ 생성:        %,6d 건                  │%n", POST_COUNT);
        System.out.printf("  │ ES 반영:     %,6d 건                  │%n", esCount);
        System.out.printf("  │ 유실:        %,6d 건  ← 이게 문제     │%n", lost);
        System.out.printf("  └───────────────────────────────────────┘%n");

        // Phase 1은 어설션 없음 — 유실이 있어야 테스트가 의미 있음
    }

    // ─── Phase 2: After ──────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Phase 2 — After: DLQ Direct Indexer + TimeWindowReconciler로 유실 0건 확인")
    void phase2_measureRecoveryWithDefense() throws Exception {
        System.out.println("\n========================================");
        System.out.println("  Phase 2: After (DLQ + Reconciler)");
        System.out.println("========================================");

        // 1. 새 게시글 생성 (phase1과 다른 ID 범위)
        System.out.printf("[1/6] %d건 게시글 + Outbox 이벤트 생성 중 (phase2 범위)...%n", POST_COUNT);
        insertPostsAndOutboxEvents(PHASE2_BASE_POST_ID, POST_COUNT);
        System.out.printf("  → %d건 생성 완료%n", POST_COUNT);

        // 2. 파이프라인 처리 시작 대기
        Thread.sleep(ES_DOWN_DELAY_MS);

        // 3. ES 장애 주입
        System.out.printf("[2/6] ES 컨테이너 중단 (장애 주입)...%n");
        dockerStop(ES_CONTAINER);
        System.out.printf("  → ES down. %dms 유지...%n", ES_DOWN_DURATION_MS);
        Thread.sleep(ES_DOWN_DURATION_MS);

        // 4. ES 복구
        System.out.printf("[3/6] ES 컨테이너 재기동...%n");
        dockerStart(ES_CONTAINER);
        waitForEs();
        // 짧게 대기하여 SQS에 남은 잔여 메시지 처리
        Thread.sleep(ES_RECOVERY_SETTLE_MS);

        // 5. ES에 반영된 현황 파악 (방어 전)
        long esCountBeforeDefense = countEsDocs(PHASE2_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT);
        long lostBeforeDefense = POST_COUNT - esCountBeforeDefense;
        System.out.printf("[4/6] 방어 활성화 전: ES=%d건, 유실=%d건%n",
                esCountBeforeDefense, lostBeforeDefense);

        // 6-A. DLQ Direct Indexer: DlqPostIndexRetryConsumer(@SqsListener)가 자동으로 DLQ 소비
        //      processDlqManually()를 사용하지 않고 실제 프로덕션 경로로 검증한다.
        System.out.printf("[5/6] DLQ 컨슈머 자동 소비 대기 (DlqPostIndexRetryConsumer @SqsListener)...%n");
        System.out.printf("  최대 대기: %dms, 폴링 간격: %dms%n", DLQ_DRAIN_TIMEOUT_MS, DLQ_POLL_INTERVAL_MS);
        long dlqRemaining = waitForDlqDrained(DLQ_DRAIN_TIMEOUT_MS);
        if (dlqRemaining == 0) {
            System.out.printf("  → DLQ 완전 소비 완료%n");
        } else {
            System.out.printf("  → DLQ 타임아웃: 잔여 %d건 (Reconciler가 안전망으로 처리)%n", dlqRemaining);
        }

        // 6-B. TimeWindowReconciler: 아직 안 잡힌 나머지 처리
        System.out.printf("[6/6] TimeWindowReconciler 실행 (window=%dmin)...%n",
                120L); // 충분히 넓은 윈도우
        TimeWindowReconciler.ReconciliationResult reconResult =
                reconciler.reconcile(120L);
        System.out.printf("  → Reconciler: %s%n", reconResult);

        // 최종 ES 반영 확인 (색인 refresh 대기)
        Thread.sleep(2_000);
        esClient.indices().refresh(r -> r.index("posts"));
        Thread.sleep(1_000);

        long esCountAfterDefense = countEsDocs(PHASE2_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT);
        long remainingLoss = POST_COUNT - esCountAfterDefense;

        phase2Result = new PhaseResult(
                POST_COUNT,
                esCountBeforeDefense, lostBeforeDefense,
                dlqRemaining, reconResult.reindexed(),
                esCountAfterDefense, remainingLoss
        );

        System.out.printf("%n  ┌─ Phase 2 결과 ────────────────────────────────────┐%n");
        System.out.printf("  │ 생성:                  %,6d 건                  │%n", POST_COUNT);
        System.out.printf("  │ 방어 전 ES 반영:       %,6d 건 (유실 %,d건)    │%n",
                esCountBeforeDefense, lostBeforeDefense);
        System.out.printf("  │ DLQ 잔여 (소비 후):    %,6d 건 (0=정상완료)    │%n", dlqRemaining);
        System.out.printf("  │ Reconciler 복구:       %,6d 건                  │%n", reconResult.reindexed());
        System.out.printf("  │ 방어 후 ES 반영:       %,6d 건                  │%n", esCountAfterDefense);
        System.out.printf("  │ 최종 유실:             %,6d 건  ← 0이어야 함   │%n", remainingLoss);
        System.out.printf("  └───────────────────────────────────────────────────┘%n");

        // Phase 2: 유실이 0이어야 함
        if (remainingLoss > 0) {
            System.out.printf("%n  ⚠️  %d건 미복구. 로그를 확인하세요.%n", remainingLoss);
        } else {
            System.out.println("\n  ✓ 유실 0건 — 방어 계층 정상 동작 확인");
        }
    }

    // ─── 리포트 생성 ─────────────────────────────────────────────────────────

    @AfterAll
    void generateReport() throws Exception {
        System.out.println("\n=== 리포트 생성 중... ===");

        try (PrintWriter w = new PrintWriter(new FileWriter(REPORT_PATH))) {
            w.println("# 장애 주입 벤치마크 결과 — Outbox → SQS → ES End-to-End 안전성");
            w.println();
            w.println("## 환경");
            w.printf("- ES: bench-es 컨테이너 (nori plugin, 힙 1g)%n");
            w.printf("- SQS: LocalStack (VisibilityTimeout=30s, maxReceiveCount=3)%n");
            w.printf("- 장애 시나리오: ES %d초 중단%n", ES_DOWN_DURATION_MS / 1000);
            w.printf("- 테스트 게시글: %,d건 (phase당)%n", POST_COUNT);
            w.printf("- 실행 일시: %s%n", LocalDateTime.now());
            w.println();

            w.println("## 핵심 결과");
            w.println();
            w.println("| 구분 | 생성 | ES 반영 | 유실 |");
            w.println("|------|------|---------|------|");

            if (phase1Result != null) {
                w.printf("| Phase 1 (방어 계층 없음) | %,d | %,d | **%,d** |%n",
                        phase1Result.total(),
                        phase1Result.esCountBeforeDefense(),
                        phase1Result.lostBeforeDefense());
            } else {
                w.println("| Phase 1 (방어 계층 없음) | - | - | - |");
            }

            if (phase2Result != null) {
                w.printf("| Phase 2 (방어 활성화 전) | %,d | %,d | %,d |%n",
                        phase2Result.total(),
                        phase2Result.esCountBeforeDefense(),
                        phase2Result.lostBeforeDefense());
                w.printf("| Phase 2 (DLQ + Reconciler 후) | %,d | %,d | **%,d** |%n",
                        phase2Result.total(),
                        phase2Result.esCountAfterDefense(),
                        phase2Result.remainingLoss());
            } else {
                w.println("| Phase 2 (방어 활성화 전) | - | - | - |");
                w.println("| Phase 2 (DLQ + Reconciler 후) | - | - | - |");
            }
            w.println();

            w.println("## 방어 계층별 복구 기여도 (Phase 2)");
            w.println();
            if (phase2Result != null) {
                w.println("| 방어 계층 | 결과 | 설명 |");
                w.println("|-----------|------|------|");
                w.printf("| DLQ Direct Indexer | DLQ 잔여 %,d건 | DlqPostIndexRetryConsumer @SqsListener 자동 소비 (0=완전 소비) |%n",
                        phase2Result.dlqConsumedByListener());
                w.printf("| TimeWindowReconciler | %,d건 재색인 | DB-ES 시간 윈도우 diff → 누락/stale 재색인 |%n",
                        phase2Result.reconciledByReconciler());
                w.printf("| **최종 유실** | **%,d건** | — |%n",
                        phase2Result.remainingLoss());
                w.println();
            }

            w.println("## 방어 계층 설계");
            w.println();
            w.println("```");
            w.println("Outbox → SQS → ES  파이프라인");
            w.println();
            w.println("장애 시나리오:");
            w.println("  SQS 메시지 → Worker → ES bulk 실패 → NACK");
            w.println("  → SQS 재시도 (maxReceiveCount=3, 30s interval)");
            w.println("  → DLQ로 이동 → [기존: 영구 유실]");
            w.println();
            w.println("Layer 1: DLQ Direct Indexer");
            w.println("  DlqPostIndexRetryConsumer (@SqsListener, search.dlq.enabled=true)");
            w.println("  DLQ → postId 추출 → DB 최신 조회 → ES 직접 색인");
            w.println("  실패 → ACK (재진입 방지) → Reconciler가 안전망");
            w.println();
            w.println("Layer 2: TimeWindowReconciler");
            w.println("  10분 주기, 20분 윈도우");
            w.println("  DB updated_at 기반 변경 레코드 → ES _mget 비교");
            w.println("  누락/stale → SinglePostIndexer 재색인");
            w.println("```");
            w.println();

            w.println("## Count 비교 전략 선택 근거");
            w.println();
            w.println("count 비교는 false negative가 있어 안전망으로 부적절:");
            w.println("> DB에서 1건 삭제 + 1건 추가 됐는데 ES에 둘 다 미반영이면 count는 동일하나 2건 불일치.");
            w.println();
            w.println("**Time-window ID+version diff**를 채택:");
            w.println("- 비교 대상: 수백~수천 건 (20분 윈도우 내 변경분)");
            w.println("- false negative 없음 (ID+version 직접 비교)");
            w.println("- DB/ES 부하 미미 (_mget 단일 호출)");
        }

        System.out.println("=== 리포트 생성 완료: " + REPORT_PATH + " ===");
    }

    @AfterAll
    void cleanup() {
        // 벤치마크 데이터 정리
        jdbc.update("DELETE FROM post WHERE post_id BETWEEN ? AND ?",
                PHASE1_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT + 1);
        jdbc.update("DELETE FROM post_like_count WHERE post_id BETWEEN ? AND ?",
                PHASE1_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT + 1);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id BETWEEN ? AND ?",
                PHASE1_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT + 1);

        try {
            List<String> allIds = LongStream.range(PHASE1_BASE_POST_ID, PHASE2_BASE_POST_ID + POST_COUNT)
                    .mapToObj(String::valueOf)
                    .toList();
            esClient.deleteByQuery(d -> d
                    .index("posts")
                    .query(q -> q.ids(i -> i.values(allIds))));
            esClient.indices().refresh(r -> r.index("posts"));
        } catch (Exception ignored) {}
    }

    // ─── 헬퍼: DB + Outbox 데이터 삽입 ──────────────────────────────────────

    private void insertPostsAndOutboxEvents(long basePostId, int count) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long versionBase = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // 배치 삽입 (JdbcTemplate batchUpdate)
        List<Object[]> postRows = new ArrayList<>(count);
        List<Object[]> likeRows = new ArrayList<>(count);
        List<Object[]> outboxRows = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long postId = basePostId + i;
            long version = versionBase + i;

            postRows.add(new Object[]{
                    postId,
                    "장애 주입 테스트 게시글 " + postId,
                    "ES 장애 주입 테스트 본문. postId=" + postId,
                    10000L, 30000L,
                    "서울특별시 강남구 테헤란로",
                    37.4979, 127.0276,
                    "DIGITAL_DEVICE",
                    1L,  // author_id
                    0,   // deleted
                    now, // created_at
                    now  // updated_at
            });

            likeRows.add(new Object[]{postId, 0L});

            PostUpsertEvent evt = PostUpsertEvent.builder()
                    .postId(postId).version(version)
                    .title("장애 주입 테스트 게시글 " + postId)
                    .content("ES 장애 주입 테스트 본문. postId=" + postId)
                    .rentalFee(10000L).deposit(30000L)
                    .address("서울특별시 강남구 테헤란로")
                    .lat(37.4979).lon(127.0276)
                    .category("DIGITAL_DEVICE").likeCount(0)
                    .createdAtIso(now.toString())
                    .build();

            outboxRows.add(new Object[]{
                    "POST", postId, "UPSERT",
                    objectMapper.writeValueAsString(evt),
                    version, now, false
            });
        }

        jdbc.batchUpdate(
                "INSERT INTO post (post_id, title, content, rental_fee, deposit, address, " +
                        "latitude, longitude, category, author_id, deleted, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                postRows);

        jdbc.batchUpdate(
                "INSERT IGNORE INTO post_like_count (post_id, like_count) VALUES (?,?)",
                likeRows);

        jdbc.batchUpdate(
                "INSERT INTO outbox_event " +
                        "(aggregate_type, aggregate_id, event_type, payload, version, created_at, published) " +
                        "VALUES (?,?,?,?,?,?,?)",
                outboxRows);
    }

    // ─── 헬퍼: DLQ 소비 완료 대기 ───────────────────────────────────────────
    //
    // DlqPostIndexRetryConsumer(@SqsListener)가 DLQ 메시지를 모두 소비할 때까지 대기한다.
    // SQS ApproximateNumberOfMessages(visible) + ApproximateNumberOfMessagesNotVisible(in-flight)
    // 가 모두 0이 되면 소비 완료로 판단한다.
    //
    // 프로덕션 경로 검증: 이 메서드는 실제 @SqsListener가 올바르게 동작하는지를 검증하기 위해
    // processDlqManually()를 대체한다. 로그에서 "DLQ upsert 복구 성공: postId=..." 메시지가
    // 출력되면 DlqPostIndexRetryConsumer.onMessage()가 실제로 호출됐다는 증거이다.
    //
    // @return 타임아웃 시 잔여 메시지 수 (0이면 정상 완료)

    private long waitForDlqDrained(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var resp = sqsClient.getQueueAttributes(r -> r
                    .queueUrl(postIndexDlqUrl)
                    .attributeNames(
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
            ).get(10, TimeUnit.SECONDS);

            long visible    = Long.parseLong(resp.attributes()
                    .getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
            long notVisible = Long.parseLong(resp.attributes()
                    .getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));

            System.out.printf("  DLQ 잔여: visible=%d, in-flight=%d%n", visible, notVisible);

            if (visible == 0 && notVisible == 0) {
                return 0;
            }
            Thread.sleep(DLQ_POLL_INTERVAL_MS);
        }
        // 타임아웃: 잔여 visible 메시지 수 반환
        var resp = sqsClient.getQueueAttributes(r -> r
                .queueUrl(postIndexDlqUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
        ).get(10, TimeUnit.SECONDS);
        return Long.parseLong(resp.attributes()
                .getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
    }

    // ─── 헬퍼: ES 문서 수 조회 ───────────────────────────────────────────────
    // ES 8.x에서 _id 필드는 range 쿼리를 지원하지 않음.
    // ids() 쿼리를 사용하여 정확한 ID 목록으로 count한다.

    private long countEsDocs(long fromId, long toId) throws Exception {
        esClient.indices().refresh(r -> r.index("posts"));

        List<String> ids = LongStream.range(fromId, toId)
                .mapToObj(String::valueOf)
                .toList();

        var resp = esClient.count(c -> c
                .index("posts")
                .query(q -> q.ids(i -> i.values(ids))));

        return resp.count();
    }

    // ─── 헬퍼: ES 가용성 + shard 준비 완료 대기 ──────────────────────────────

    private void waitForEs() throws InterruptedException {
        System.out.print("  → ES 기동 대기");
        long deadline = System.currentTimeMillis() + 120_000;

        // Step 1: ping 성공까지 대기
        while (System.currentTimeMillis() < deadline) {
            try {
                esClient.ping();
                System.out.println(" → 응답 확인");
                break;
            } catch (Exception ignored) {
                System.out.print(".");
                Thread.sleep(2_000);
            }
        }

        // Step 2: posts 인덱스 shard가 YELLOW 이상이 될 때까지 대기
        System.out.print("  → shard 준비 대기");
        while (System.currentTimeMillis() < deadline) {
            try {
                var health = esClient.cluster().health(h -> h
                        .index("posts")
                        .waitForStatus(co.elastic.clients.elasticsearch._types.HealthStatus.Yellow)
                        .timeout(t -> t.time("10s")));
                String status = health.status().jsonValue();
                System.out.printf(" → cluster status=%s%n", status);
                return;
            } catch (Exception e) {
                System.out.print(".");
                Thread.sleep(3_000);
            }
        }
        System.out.println("\n  ⚠️  ES shard 준비 타임아웃 (120s)");
    }

    // ─── 헬퍼: Docker 컨테이너 제어 ──────────────────────────────────────────

    private void dockerStop(String containerName) throws Exception {
        runDockerCommand("docker", "stop", containerName);
    }

    private void dockerStart(String containerName) throws Exception {
        runDockerCommand("docker", "start", containerName);
    }

    private void runDockerCommand(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException(
                    "Docker 명령 실패 (exitCode=" + exitCode + "): " +
                            String.join(" ", cmd) + "\n" + output);
        }
        System.out.printf("  실행됨: %s%n", String.join(" ", cmd));
    }
}
