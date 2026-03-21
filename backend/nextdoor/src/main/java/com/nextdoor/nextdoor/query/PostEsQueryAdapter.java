package com.nextdoor.nextdoor.query;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSearchPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostSummary;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PostEsQueryAdapter implements FeedSearchPort {

    private final ElasticsearchOperations operations;

    private static final String GEO_FIELD = "location";
    private static final String RADIUS = "5km";

    @Override
    public List<PostSummary> searchNearbyFast(double lat, double lon, int page, int size) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .filter(f -> f.geoDistance(g -> g
                                .field(GEO_FIELD)
                                .distance(RADIUS)
                                .location(loc -> loc
                                        .latlon(ll -> ll
                                                .lat(lat)
                                                .lon(lon)
                                        )
                                )
                        ))
                ))
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<PostDocument> hits = operations.search(query, PostDocument.class);

        return hits.getSearchHits()
                .stream()
                .map(h -> PostSummary.from(h.getContent()))
                .toList();
    }
}