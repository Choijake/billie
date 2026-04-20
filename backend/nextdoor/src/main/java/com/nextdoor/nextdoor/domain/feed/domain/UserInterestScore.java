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
     * 누적 관심도 점수.
     *
     * <p>가중치 (10x 스케일링 적용):
     * <ul>
     *   <li>VIEW: +10</li>
     *   <li>LIKE: +30</li>
     *   <li>RESERVE: +100</li>
     * </ul>
     *
     * <p>매일 새벽 FLOOR(score * 0.7) 감쇠 적용.
     * FLOOR 정수 절사로 인해 저점 구간에서 감쇠가 가속된다:
     * <ul>
     *   <li>score 10 (VIEW 1회): 5일 후 소멸</li>
     *   <li>score 30 (LIKE 1회): 8일 후 소멸</li>
     *   <li>score 100 (RESERVE 1회): ~13일 후 소멸</li>
     * </ul>
     * 이 가속 감쇠는 의도된 동작이다. 약한 관심이 빠르게 소멸되는 것은
     * 피드 신선도 유지에 부합한다.
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