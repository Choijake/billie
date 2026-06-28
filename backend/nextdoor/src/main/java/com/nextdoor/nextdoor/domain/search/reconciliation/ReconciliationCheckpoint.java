package com.nextdoor.nextdoor.domain.search.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Reconciler가 성공적으로 처리한 마지막 윈도우 끝점을 보관한다.
 * Worker가 재시작되어도 실패 구간을 잃지 않도록 DB에 영속화한다.
 */
@Entity
@Table(name = "search_reconciliation_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationCheckpoint {

    @Id
    @Column(name = "checkpoint_key", length = 100, nullable = false)
    private String checkpointKey;

    @Column(name = "last_successful_end", nullable = false)
    private LocalDateTime lastSuccessfulEnd;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public ReconciliationCheckpoint(String checkpointKey, LocalDateTime lastSuccessfulEnd) {
        this.checkpointKey = checkpointKey;
        this.lastSuccessfulEnd = lastSuccessfulEnd;
    }

    public void advanceTo(LocalDateTime nextEnd) {
        if (nextEnd.isBefore(lastSuccessfulEnd)) {
            throw new IllegalArgumentException("체크포인트를 이전 시각으로 이동할 수 없습니다.");
        }
        this.lastSuccessfulEnd = nextEnd;
    }
}
