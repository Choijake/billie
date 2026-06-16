package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import com.nextdoor.nextdoor.domain.post.exception.PostIndexException;
import com.nextdoor.nextdoor.domain.search.indexing.SinglePostIndexer;
import com.nextdoor.nextdoor.domain.search.outbox.Jsons;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnProperty(name = "search.dlq.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DlqPostIndexRetryConsumer {

    private final SinglePostIndexer singlePostIndexer;
    private final Jsons jsons;

    @SqsListener(value = "${sqs.queue.post-index-dlq}", acknowledgementMode = "MANUAL")
    public void onMessage(String message, Acknowledgement ack) {
        Long postId = null;
        try {
            switch (jsons.readEventType(message)) {
                case UPSERT -> {
                    postId = jsons.toUpsert(message).getPostId();
                    singlePostIndexer.indexSinglePost(postId);
                    log.info("DLQ post-index upsert 복구 성공: postId={}", postId);
                }
                case DELETE -> {
                    postId = jsons.toDelete(message).getPostId();
                    singlePostIndexer.deleteSingleIndex(postId);
                    log.info("DLQ post-index delete 복구 성공: postId={}", postId);
                }
            }
            ack.acknowledge();
        } catch (PostIndexException e) {
            log.warn("DLQ post-index: postId={}가 DB에 없음 또는 보류됨, ACK 처리", postId);
            ack.acknowledge();
        } catch (Exception e) {
            if (isEsUnavailable(e)) {
                log.warn("DLQ post-index: ES 연결 불가, ACK 없이 재시도 대기: postId={}", postId);
                return;
            }
            log.error("DLQ post-index 영구 실패: postId={}, message={}",
                    postId, abbreviate(message), e);
            ack.acknowledge();
        }
    }

    private static boolean isEsUnavailable(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.ConnectException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String abbreviate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
