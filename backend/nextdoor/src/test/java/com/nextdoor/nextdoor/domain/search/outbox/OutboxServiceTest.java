package com.nextdoor.nextdoor.domain.search.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    private final SqsPublisher sqsPublisher = mock(SqsPublisher.class);
    private final OutboxService service = new OutboxService(sqsPublisher, new SimpleMeterRegistry());

    @Test
    void 같은_postId는_순차_발행하고_다른_postId는_병렬로_발행한다() throws Exception {
        OutboxEventDto p1v1 = event(1L, 10L, "UPSERT", "p1-v1");
        OutboxEventDto p1v2 = event(2L, 10L, "UPSERT", "p1-v2");
        OutboxEventDto p2v1 = event(3L, 20L, "UPSERT", "p2-v1");

        CompletableFuture<SendMessageResponse> p1First = new CompletableFuture<>();
        when(sqsPublisher.send("p1-v1")).thenReturn(p1First);
        when(sqsPublisher.send("p1-v2")).thenReturn(completedSend());
        when(sqsPublisher.send("p2-v1")).thenReturn(completedSend());

        CompletableFuture<List<Long>> result = CompletableFuture.supplyAsync(() ->
                service.publishViewsAndCollectSuccessIds(List.of(p1v1, p1v2, p2v1)));

        awaitUntil(() -> {
            verify(sqsPublisher).send("p1-v1");
            verify(sqsPublisher).send("p2-v1");
        });
        verify(sqsPublisher, never()).send("p1-v2");

        p1First.complete(SendMessageResponse.builder().messageId("p1-v1").build());

        assertThat(result.get(1, TimeUnit.SECONDS)).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(sqsPublisher).send("p1-v2");
    }

    @Test
    void 같은_postId의_앞_발행이_실패해도_다음_이벤트는_계속_발행한다() {
        OutboxEventDto p1v1 = event(1L, 10L, "UPSERT", "p1-v1");
        OutboxEventDto p1v2 = event(2L, 10L, "DELETE", "p1-v2");

        CompletableFuture<SendMessageResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("SQS failure"));
        when(sqsPublisher.send("p1-v1")).thenReturn(failed);
        when(sqsPublisher.send("p1-v2")).thenReturn(completedSend());

        List<Long> okIds = service.publishViewsAndCollectSuccessIds(List.of(p1v1, p1v2));

        assertThat(okIds).containsExactly(2L);
        verify(sqsPublisher).send("p1-v1");
        verify(sqsPublisher).send("p1-v2");
    }

    private static CompletableFuture<SendMessageResponse> completedSend() {
        return CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("ok").build());
    }

    private static OutboxEventDto event(Long id, Long aggregateId, String eventType, String payload) {
        return new StubOutboxEventDto(id, aggregateId, eventType, payload);
    }

    private static void awaitUntil(Verification verification) throws InterruptedException {
        AssertionError last = null;
        for (int i = 0; i < 20; i++) {
            try {
                verification.verify();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(25);
            }
        }
        throw last;
    }

    @FunctionalInterface
    private interface Verification {
        void verify();
    }

    private record StubOutboxEventDto(Long id, Long aggregateId, String eventType, String payload)
            implements OutboxEventDto {

        @Override public Long getId() { return id; }
        @Override public String getAggregateType() { return "POST"; }
        @Override public Long getAggregateId() { return aggregateId; }
        @Override public String getEventType() { return eventType; }
        @Override public String getPayload() { return payload; }
        @Override public Long getVersion() { return 1L; }
    }
}
