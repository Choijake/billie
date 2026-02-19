package com.nextdoor.nextdoor.domain.feed.domain;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 카테고리 관심도 점수
 * - Redis Write-Back 패턴의 영구 저장소
 * - 사용자 행동(VIEW, LIKE, RESERVE)을 카테고리별로 집계
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "user_interest_score",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_category",
                columnNames = {"member_id", "category"}
        )
)
public class UserInterestScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "score_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Category category;

    /**
     * 누적 점수
     * - VIEW: +1
     * - LIKE: +3
     * - RESERVE: +10
     */
    @Column(nullable = false)
    private Long score;

    @Builder
    public UserInterestScore(Long memberId, Category category, Long score) {
        this.memberId = memberId;
        this.category = category;
        this.score = score;
    }

    /**
     * 점수 증가 (UPSERT 로직용)
     */
    public void addScore(Long delta) {
        this.score += delta;
    }

    /**
     * 점수 설정 (Sync 로직용)
     */
    public void updateScore(Long newScore) {
        this.score = newScore;
    }
}