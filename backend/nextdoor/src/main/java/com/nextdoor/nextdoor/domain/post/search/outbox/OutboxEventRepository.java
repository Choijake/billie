package com.nextdoor.nextdoor.domain.post.search.outbox;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
      SELECT id
      FROM outbox_event
      WHERE published = 0
        AND (claimed_by IS NULL OR claimed_at < DATE_SUB(NOW(), INTERVAL :ttlSec SECOND))
      ORDER BY id
      LIMIT :batch
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
    List<Long> selectClaimableIds(@Param("ttlSec") int ttlSec, @Param("batch") int batch);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE outbox_event SET claimed_by = :worker, claimed_at = NOW()
      WHERE id IN (:ids)
      """, nativeQuery = true)
    int markClaimed(@Param("worker") String worker, @Param("ids") List<Long> ids);

    @Query(value = """
      SELECT
        e.id               AS id,
        e.aggregate_type   AS aggregateType,
        e.aggregate_id     AS aggregateId,
        e.event_type       AS eventType,
        e.payload          AS payload,
        e.version          AS version
      FROM outbox_event e
      WHERE e.id IN (:ids)
      ORDER BY e.id ASC
      """, nativeQuery = true)
    List<OutboxEventDto> findViewsByIds(@Param("ids") List<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE outbox_event SET published = 1
      WHERE published = 0 AND id IN (:ids)
      """, nativeQuery = true)
    int markPublishedIn(@Param("ids") List<Long> ids);
}