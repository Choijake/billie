package com.nextdoor.nextdoor.domain.post.search.outbox.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class PostDeleteEvent implements PostIndexEvent {
    private final String type = "DELETE";
    private Long postId;
    private Long version;
}