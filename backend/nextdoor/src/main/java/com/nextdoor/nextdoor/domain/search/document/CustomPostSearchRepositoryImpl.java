package com.nextdoor.nextdoor.domain.search.document;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class CustomPostSearchRepositoryImpl implements CustomPostSearchRepository {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostSearchQueryBuilder queryBuilder;

    @Override
    public Page<PostDocument> searchByKeywordWithAddress(String keyword, String address, Pageable pageable) {
        NativeQuery searchQuery = queryBuilder.buildKeywordAddressQuery(keyword, address, pageable);

        SearchHits<PostDocument> searchHits = elasticsearchOperations.search(searchQuery, PostDocument.class);

        List<PostDocument> content = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        return PageableExecutionUtils.getPage(content, pageable, searchHits::getTotalHits);
    }
}
