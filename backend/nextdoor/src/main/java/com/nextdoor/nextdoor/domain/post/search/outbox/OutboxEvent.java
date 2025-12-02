package com.nextdoor.nextdoor.domain.post.search.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter @Setter
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateType;
    private Long aggregateId;
    private String eventType;

    @Lob
    private String payload;

    private Long version;
    private LocalDateTime createdAt;
    private boolean published;

    private String claimedBy;
    private LocalDateTime claimedAt;

    public OutboxEvent() {
        this.createdAt = LocalDateTime.now();
        this.published = false;
    }
}