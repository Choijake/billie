package com.nextdoor.nextdoor.domain.post.listener;

import com.nextdoor.nextdoor.domain.post.event.PostCreatedEvent;
import com.nextdoor.nextdoor.domain.post.event.PostUpdatedEvent;
import com.nextdoor.nextdoor.domain.post.search.messaging.producer.PostIndexingProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Profile("api")
@RequiredArgsConstructor
public class PostIndexEventListener {

    private final PostIndexingProducer postIndexingProducer;

    @Async("asyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostCreated(PostCreatedEvent event) {
        postIndexingProducer.requestSinglePostIndexing(event.getPostId());
    }

    @Async("asyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostDeleted(PostUpdatedEvent event) {
        postIndexingProducer.requestSinglePostDeletion(event.getPostId());
    }
}