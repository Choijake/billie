package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserInterestScoreRepository extends JpaRepository<UserInterestScore, Long> {

    /**
     * 사용자의 모든 카테고리 점수 조회
     * - FeedService 개인화 점수 계산
     */
    List<UserInterestScore> findByMemberId(Long memberId);

    /**
     * 특정 사용자-카테고리 점수 조회
     * - SyncScheduler.syncScoresToDB(): UPSERT 로직
     */
    Optional<UserInterestScore> findByMemberIdAndCategory(Long memberId, Category category);

    /**
     * 사용자별 상위 N개 관심 카테고리 조회
     * - 관심사 분석, 추천 알고리즘 개선
     */
    @Query("SELECT u FROM UserInterestScore u " +
            "WHERE u.memberId = :memberId " +
            "ORDER BY u.score DESC")
    List<UserInterestScore> findTopCategoriesByMemberId(
            @Param("memberId") Long memberId
    );

    /**
     * 관심사 점수 원자적 누적 (concurrent write 안전)
     * - MySQL InnoDB row-level lock으로 score = score + :weight 원자 보장
     * - 행이 없으면 INSERT, 있으면 UPDATE (ON DUPLICATE KEY)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO user_interest_score (member_id, category, score) " +
            "VALUES (:memberId, :category, :weight) " +
            "ON DUPLICATE KEY UPDATE score = score + :weight",
            nativeQuery = true)
    void upsertScore(@Param("memberId") Long memberId,
                     @Param("category") String category,
                     @Param("weight") int weight);

    /**
     * PK range 기반 감쇠 적용.
     * WHERE 절로 범위 제한 → 해당 범위 행에만 InnoDB X-lock.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE user_interest_score SET score = FLOOR(score * :rate) " +
            "WHERE score_id BETWEEN :startId AND :endId",
            nativeQuery = true)
    int applyDecayInRange(@Param("rate") double rate,
                          @Param("startId") long startId,
                          @Param("endId") long endId);

    /**
     * PK range 기반 score=0 행 삭제 (applyDecayInRange 후 호출).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM user_interest_score WHERE score = 0 " +
            "AND score_id BETWEEN :startId AND :endId",
            nativeQuery = true)
    int deleteZeroScoresInRange(@Param("startId") long startId,
                                @Param("endId") long endId);

    /**
     * 최대 PK 조회 (청크 범위 결정용).
     */
    @Query(value = "SELECT MAX(score_id) FROM user_interest_score",
            nativeQuery = true)
    Long findMaxScoreId();
}