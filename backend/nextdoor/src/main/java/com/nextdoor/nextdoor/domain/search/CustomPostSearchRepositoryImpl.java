package com.nextdoor.nextdoor.domain.search;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.support.PageableExecutionUtils;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;


import java.util.List;
import java.util.stream.Collectors;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;

@RequiredArgsConstructor
public class CustomPostSearchRepositoryImpl implements CustomPostSearchRepository {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public Page<PostDocument> searchByKeywordWithAddress(String keyword, String address, Pageable pageable) {

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        if (address != null && !address.isBlank()) {
            boolQuery.must(
                    new TermQuery.Builder()
                            .field("address.keyword")
                            .value(address)
                            .build()._toQuery()
            );
        }

        if (keyword != null && !keyword.isBlank()) {
            boolQuery.should(
                    new MatchQuery.Builder()
                            .field("title")
                            .query(keyword)
                            .operator(Operator.And)
                            .boost(3.0f)
                            .build()._toQuery()
            );

            boolQuery.should(
                    new MatchQuery.Builder()
                            .field("content")
                            .query(keyword)
                            .operator(Operator.And)
                            .boost(1.0f)
                            .build()._toQuery()
            );

            boolQuery.minimumShouldMatch("1");
        }

        NativeQuery searchQuery = NativeQuery.builder()
                .withQuery(boolQuery.build()._toQuery())
                .withPageable(pageable)
                .build();

        SearchHits<PostDocument> searchHits = elasticsearchOperations.search(
                searchQuery, PostDocument.class);

        List<PostDocument> content = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        return PageableExecutionUtils.getPage(content, pageable, searchHits::getTotalHits);
    }
}