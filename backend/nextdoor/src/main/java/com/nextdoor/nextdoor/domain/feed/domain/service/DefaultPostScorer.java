package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Map;

/**
 * 게시물 스코어링.
 *
 * <p>공식: {@code score = 1.0 + 0.5 * ln(1 + interestScore) + 0.3 * exp(-ageSeconds / 604800)}
 *
 * <h3>컴포넌트 설명</h3>
 * <ul>
 *   <li>기본값 1.0: 관심사 없는 사용자에게도 최소 점수 보장</li>
 *   <li>관심도 0.5 x ln(1+x): 로그 스케일로 극단적 점수 차이 억제.
 *       ln(11)=2.40 vs ln(101)=4.62 → 10배 점수 차이가 2배 수준으로 완화</li>
 *   <li>최신성 0.3 x exp(-t/604800): 7일(604800초) 반감기.
 *       렌탈 게시글은 1주일 전 등록이어도 유효한 후보이므로,
 *       일반 피드의 1일 감쇠(86400초)보다 길게 설정</li>
 * </ul>
 *
 * <h3>가산 결합 선택 근거</h3>
 * <p>관심도와 최신성을 가산(+)으로 결합한다. 곱셈(*) 대안과 비교:
 * <ul>
 *   <li>가산: cold start 유저(관심도=0)에게 최신 글이 차별화되어 노출 (1.0~1.3 범위)</li>
 *   <li>곱셈: cold start 유저의 모든 글이 동일 기본값 → 최신성 변별력 없음</li>
 * </ul>
 * <p>렌탈 플랫폼은 "이번 주 드릴, 다음 달 텐트" 등 넓은 탐색 패턴이므로
 * cold start에서도 최신 글이 노출되는 가산 방식이 적합하다.
 *
 * <h3>업계 분류</h3>
 * <p>이 공식은 Hacker News/Reddit 계열의 Tier 1 (수학 공식 기반 랭킹)에 해당한다.
 * ln(1+x)가 헤비/라이트 유저 간 절대값 차이를 억제하는 비선형 정규화 역할을 한다.
 * 학습 데이터 축적 시 ML 기반 Tier 2 (DeepFM, Two-tower 등) 전환을 검토할 수 있다.
 *
 * <h3>10x 가중치 스케일링과의 관계</h3>
 * <p>가중치 10x 적용 후 관심도 컴포넌트가 커져서 (예: RESERVE 1회 score=100 → 0.5*ln(101)=2.31)
 * 최신성(최대 0.3) 대비 관심도 신호가 강해진다. 이는 "관심 카테고리 상위 노출"이라는
 * 피드 목표에 부합하는 방향이다.
 */
@Component
public class DefaultPostScorer implements PostScorer {

    private static final double BASE_SCORE      = 1.0;
    private static final double INTEREST_WEIGHT = 0.5;
    private static final double RECENCY_WEIGHT  = 0.3;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final double decayConstantSeconds;

    public DefaultPostScorer(
            @Value("${feed.scoring.decay-constant-seconds:604800}") double decayConstantSeconds) {
        this.decayConstantSeconds = decayConstantSeconds;
    }

    @Override
    public double calculateScore(PostMetadata metadata, Map<Category, Long> userInterests) {
        double score = BASE_SCORE;

        if (metadata.category() != null) {
            Long interestScore = userInterests.get(metadata.category());
            if (interestScore != null) {
                score += INTEREST_WEIGHT * Math.log(1 + interestScore);
            }
        }

        if (metadata.createdAt() != null) {
            long epochMillis = metadata.createdAt()
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli();
            long ageSeconds = (System.currentTimeMillis() - epochMillis) / 1000;
            score += RECENCY_WEIGHT * Math.exp(-ageSeconds / decayConstantSeconds);
        }

        return score;
    }
}
