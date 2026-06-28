package com.nextdoor.nextdoor.domain.search.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReconciliationCheckpointRepository
        extends JpaRepository<ReconciliationCheckpoint, String> {
}
