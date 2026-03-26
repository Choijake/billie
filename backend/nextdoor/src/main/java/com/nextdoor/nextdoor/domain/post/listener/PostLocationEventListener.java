package com.nextdoor.nextdoor.domain.post.listener;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.post.event.PostLocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostLocationEventListener {

    private final FeedGeoIndexPort feedGeoIndexPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostLocationEvent(PostLocationEvent event) {

        Optional<GeoPoint> geoPointOpt =
                GeoPoint.of(event.getLatitude(), event.getLongitude());

        if (geoPointOpt.isEmpty()) {
            log.warn("유효하지 않은 좌표 값 - postId={}, lat={}, lon={}",
                    event.getPostId(),
                    event.getLatitude(),
                    event.getLongitude());
            return;
        }

        try {
            feedGeoIndexPort.addGeoLocation(event.getPostId(), geoPointOpt.get());

            log.debug("Redis Geo 위치 정보 저장 완료 - postId={}, lat={}, lon={}",
                    event.getPostId(),
                    event.getLatitude(),
                    event.getLongitude());

        } catch (Exception e) {
            log.error("Redis Geo 위치 정보 저장 실패 - postId={}, error={}",
                    event.getPostId(),
                    e.getMessage(),
                    e);
        }
    }
}