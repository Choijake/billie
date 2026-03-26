package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import com.nextdoor.nextdoor.domain.feed.application.port.UserInterestPort;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.feed.domain.service.FeedGenerator;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class FeedSessionServiceTest {

    @Mock FeedSessionStore sessionStore;
    @Mock FeedGeoIndexPort geoIndexPort;
    @Mock UserInterestPort userInterestPort;
    @Mock PostMetadataService metadataService;
    @Mock FeedGenerator feedGenerator;
    @Mock FeedConfig feedConfig;

    @InjectMocks FeedSessionService sessionService;

    @Test
    void 세션이_없을_때_윈도우를_요청하면_세션을_생성하고_해당_범위를_반환한다() {
        // given
        Long memberId = 1L;
        int windowSize = 10;
        long offset = 60L; // 61~70 구간이라고 가정
        double lat = 37.498;
        double lon = 127.027;
        GeoPoint point = new GeoPoint(lat, lon);

        // 세션이 아직 없다고 가정
        given(sessionStore.hasValidSession(eq(memberId), any(Duration.class))).willReturn(false);
        given(feedConfig.maxSessionTtl()).willReturn(Duration.ofHours(1));

        // FeedConfig 설정
        given(feedConfig.searchRadiusKm()).willReturn(5);
        given(feedConfig.geoLimit()).willReturn(500);
        given(feedConfig.sessionSize()).willReturn(150);
        Duration ttl = Duration.ofMinutes(10);
        given(feedConfig.sessionTtl()).willReturn(ttl);

        // 주변 후보 ID들
        List<Long> candidateIds = java.util.stream.LongStream.rangeClosed(1, 150).boxed().toList();
        given(geoIndexPort.findNearbyPostIds(point, 5.0, 500)).willReturn(candidateIds);

        // 메타데이터 / 관심사는 이 테스트에서 중요하지 않으므로 빈 맵으로
        given(metadataService.getPostMetadata(candidateIds))
                .willReturn(Collections.emptyMap());
        given(userInterestPort.getUserInterests(memberId))
                .willReturn(Collections.<Category, Long>emptyMap());

        // 세션에 저장될 최종 ID 목록 (여기서는 그냥 candidateIds 그대로 사용)
        List<Long> sessionIds = candidateIds;
        given(feedGenerator.generateSessionIds(
                eq(candidateIds),
                eq(Collections.emptyMap()),
                eq(Collections.<Category, Long>emptyMap()),
                eq(150))
        ).willReturn(sessionIds);

        // offset ~ offset+windowSize-1 범위에 대한 세션 내용
        List<Long> windowIds = java.util.stream.LongStream.rangeClosed(61, 70).boxed().toList();
        given(sessionStore.getSessionRange(memberId, offset, offset + windowSize - 1))
                .willReturn(windowIds);

        // when
        List<Long> result = sessionService.getIdsForWindow(memberId, offset, windowSize, point);

        // then
        assertThat(result).containsExactlyElementsOf(windowIds);

        // ensureSession 내부 동작 검증
        then(sessionStore).should(times(1)).hasValidSession(eq(memberId), any(Duration.class));
        then(geoIndexPort).should(times(1))
                .findNearbyPostIds(point, 5.0, 500);
        then(metadataService).should(times(1)).getPostMetadata(candidateIds);
        then(userInterestPort).should(times(1)).getUserInterests(memberId);
        then(feedGenerator).should(times(1))
                .generateSessionIds(candidateIds, Collections.emptyMap(), Collections.<Category, Long>emptyMap(), 150);
        then(sessionStore).should(times(1)).saveSession(memberId, sessionIds, ttl);

        // getIdsForWindow 본문 동작 검증
        then(sessionStore).should(times(1)).touchSession(memberId, ttl);
        then(sessionStore).should(times(1))
                .getSessionRange(memberId, offset, offset + windowSize - 1);
    }
}
