package com.nextdoor.nextdoor.domain.post.search;

import lombok.*;
import java.time.Instant;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ReindexRunState {
    public enum Status { RUNNING, INDEXED, SUCCEEDED, ABORTED }

    private String runId;
    private Instant cutoff;
    private String newIndex;
    private long lastId;           
    private long processed;
    private long fencingToken;
    private Status status;
    private Instant startedAt;
    private Instant updatedAt;
    private String abortReason;
}