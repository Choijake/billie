package com.nextdoor.nextdoor.domain.search.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.port.PostIndexPort;
import com.nextdoor.nextdoor.domain.search.outbox.OutboxEvent;
import com.nextdoor.nextdoor.domain.search.outbox.OutboxEventRepository;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostUpsertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * PostIndexPort의 구현체.
 * post 도메인에서 직접 참조하던 Outbox 생성 로직을 search 모듈로 격리한다.
 * post 모듈은 이 클래스의 존재를 알지 못하며 PostIndexPort 인터페이스에만 의존한다. (DIP)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPostIndexAdapter implements PostIndexPort {

    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    @Override
    public void requestUpsert(Post post, int likeCount) {
        long version = post.getUpdatedAt()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        PostUpsertEvent evt = PostUpsertEvent.builder()
                .postId(post.getId())
                .version(version)
                .title(post.getTitle())
                .content(post.getContent())
                .rentalFee(post.getRentalFee())
                .deposit(post.getDeposit())
                .address(post.getAddress())
                .lat(post.getLatitude())
                .lon(post.getLongitude())
                .category(post.getCategory() != null ? post.getCategory().name() : null)
                .likeCount(likeCount)
                .createdAtIso(post.getCreatedAt().toString())
                .build();

        saveOutboxEvent(post.getId(), "UPSERT", evt, version, post.getUpdatedAt());
        log.debug("Outbox UPSERT 저장: postId={}, version={}", post.getId(), version);
    }

    @Override
    public void requestDelete(Long postId) {
        long version = System.currentTimeMillis();

        PostDeleteEvent evt = PostDeleteEvent.builder()
                .postId(postId)
                .version(version)
                .build();

        saveOutboxEvent(postId, "DELETE", evt, version, LocalDateTime.now());
        log.debug("Outbox DELETE 저장: postId={}, version={}", postId, version);
    }

    private void saveOutboxEvent(Long aggregateId, String eventType, Object payload,
                                  long version, LocalDateTime createdAt) {
        OutboxEvent ob = new OutboxEvent();
        ob.setAggregateType("POST");
        ob.setAggregateId(aggregateId);
        ob.setEventType(eventType);
        ob.setVersion(version);
        ob.setCreatedAt(createdAt);
        ob.setPublished(false);
        try {
            ob.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패: postId=" + aggregateId, e);
        }
        outboxRepo.save(ob);
    }
}
