package com.nextdoor.nextdoor.domain.search.document;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

@RequiredArgsConstructor
public class CustomPostSearchRepositoryImpl implements CustomPostSearchRepository {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostSearchQueryBuilder queryBuilder;

    @Override
    public Page<PostDocument> search(SearchRequest request) {
        NativeQuery query = switch (request.getSortType()) {
            case RECOMMENDED -> queryBuilder.buildRecommendedQuery(request);
            case NEWEST      -> queryBuilder.buildNewestQuery(request);
            case CHEAPEST    -> queryBuilder.buildCheapestQuery(request);
            case EXPENSIVE   -> queryBuilder.buildExpensiveQuery(request);
            case NEAREST     -> queryBuilder.buildNearestQuery(request);
        };

        SearchHits<PostDocument> hits = elasticsearchOperations.search(query, PostDocument.class);
        List<PostDocument> content = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();
        return PageableExecutionUtils.getPage(content, request.getPageable(), hits::getTotalHits);
    }

    @Override
    public List<PostDocument> autocomplete(String keyword, double lat, double lon) {
        NativeQuery query = queryBuilder.buildAutocompleteQuery(keyword, lat, lon);
        SearchHits<PostDocument> hits = elasticsearchOperations.search(query, PostDocument.class);
        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();
    }
}
