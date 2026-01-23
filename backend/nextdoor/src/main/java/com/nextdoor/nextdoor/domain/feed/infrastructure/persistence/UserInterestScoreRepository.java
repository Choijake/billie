package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}