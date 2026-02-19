package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FeedSessionManagerTest {

    @Mock FeedSessionStore sessionStore;
    @Mock FeedGenerator feedGenerator;

    @Test
    void 세션이_만료된_상태에서_페이지를_요청하면_피드를_재생성하고_반환한다() {
        Long memberId = 1L;
        int page = 6;
        Double lat = 37.498;
        Double lon = 127.027;

        FeedSessionManager sessionManager = new FeedSessionManager(sessionStore, feedGenerator, 5);

        given(sessionStore.hasValidSession(memberId)).willReturn(false);

        List<Long> newFeed = LongStream.rangeClosed(1, 150).boxed().toList();
        given(feedGenerator.generate(memberId, lat, lon)).willReturn(newFeed);

        List<Long> pageIds = LongStream.rangeClosed(61, 70).boxed().toList();
        given(sessionStore.getSessionPage(memberId, page, FeedSessionManager.PAGE_SIZE_PUBLIC))
                .willReturn(pageIds);

        List<Long> result = sessionManager.getIdsForPage(memberId, page, lat, lon);

        assertThat(result).containsExactlyElementsOf(pageIds);

        then(sessionStore).should(times(1)).hasValidSession(memberId);
        then(feedGenerator).should(times(1)).generate(memberId, lat, lon);

        then(sessionStore).should(times(1))
                .saveSession(eq(memberId), eq(newFeed), any(Duration.class));

        then(sessionStore).should(times(1))
                .touchSession(eq(memberId), any(Duration.class));

        then(sessionStore).should(times(1))
                .getSessionPage(memberId, page, FeedSessionManager.PAGE_SIZE_PUBLIC);
    }
}
