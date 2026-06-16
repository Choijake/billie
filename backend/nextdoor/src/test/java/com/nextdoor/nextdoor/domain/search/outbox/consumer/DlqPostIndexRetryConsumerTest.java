package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import com.nextdoor.nextdoor.domain.post.exception.PostIndexException;
import com.nextdoor.nextdoor.domain.search.indexing.SinglePostIndexer;
import com.nextdoor.nextdoor.domain.search.outbox.Jsons;
import com.nextdoor.nextdoor.domain.search.outbox.OutboxEventType;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostUpsertEvent;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqPostIndexRetryConsumerTest {

    @Mock SinglePostIndexer singlePostIndexer;
    @Mock Jsons jsons;
    @Mock Acknowledgement ack;

    @InjectMocks DlqPostIndexRetryConsumer consumer;

    @Test
    void UPSERT는_postId_추출_후_색인하고_ACK한다() {
        PostUpsertEvent event = PostUpsertEvent.builder().postId(1L).version(100L).build();
        when(jsons.readEventType("msg")).thenReturn(OutboxEventType.UPSERT);
        when(jsons.toUpsert("msg")).thenReturn(event);

        consumer.onMessage("msg", ack);

        verify(singlePostIndexer).indexSinglePost(1L);
        verify(ack).acknowledge();
    }

    @Test
    void DELETE는_postId_추출_후_삭제하고_ACK한다() {
        PostDeleteEvent event = PostDeleteEvent.builder().postId(10L).version(1L).build();
        when(jsons.readEventType("msg")).thenReturn(OutboxEventType.DELETE);
        when(jsons.toDelete("msg")).thenReturn(event);

        consumer.onMessage("msg", ack);

        verify(singlePostIndexer).deleteSingleIndex(10L);
        verify(ack).acknowledge();
    }

    @Test
    void 게시글이_DB에_없으면_warn_후_ACK한다() {
        PostUpsertEvent event = PostUpsertEvent.builder().postId(2L).version(100L).build();
        when(jsons.readEventType("msg")).thenReturn(OutboxEventType.UPSERT);
        when(jsons.toUpsert("msg")).thenReturn(event);
        doThrow(new PostIndexException("게시물이 존재하지 않습니다."))
                .when(singlePostIndexer).indexSinglePost(2L);

        consumer.onMessage("msg", ack);

        verify(ack).acknowledge();
    }

    @Test
    void ES_일반_오류는_영구_실패로_ACK한다() {
        PostUpsertEvent event = PostUpsertEvent.builder().postId(3L).version(100L).build();
        when(jsons.readEventType("msg")).thenReturn(OutboxEventType.UPSERT);
        when(jsons.toUpsert("msg")).thenReturn(event);
        doThrow(new RuntimeException("ES 매핑 오류"))
                .when(singlePostIndexer).indexSinglePost(3L);

        consumer.onMessage("msg", ack);

        verify(ack).acknowledge();
    }

    @Test
    void ES_연결_불가시_ACK하지_않아_재노출한다() {
        PostUpsertEvent event = PostUpsertEvent.builder().postId(4L).version(100L).build();
        when(jsons.readEventType("msg")).thenReturn(OutboxEventType.UPSERT);
        when(jsons.toUpsert("msg")).thenReturn(event);
        RuntimeException connectError = new RuntimeException("ES 연결 실패",
                new java.net.ConnectException("Connection refused"));
        doThrow(connectError).when(singlePostIndexer).indexSinglePost(4L);

        consumer.onMessage("msg", ack);

        verify(ack, never()).acknowledge();
    }

    @Test
    void 메시지_파싱_실패시도_ACK한다() {
        when(jsons.readEventType("bad-json")).thenThrow(new RuntimeException("JSON 파싱 실패"));

        consumer.onMessage("bad-json", ack);

        verify(singlePostIndexer, never()).indexSinglePost(any());
        verify(singlePostIndexer, never()).deleteSingleIndex(any());
        verify(ack).acknowledge();
    }
}
