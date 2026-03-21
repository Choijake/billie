package com.nextdoor.nextdoor.domain.search.document;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.stereotype.Component;

/**
 * ES 검색 쿼리 생성 전담 컴포넌트. (SRP)
 * CustomPostSearchRepositoryImpl에서 쿼리 빌드 로직을 분리하여
 * 쿼리 변경 시 실행 코드를 건드리지 않아도 된다. (OCP)
 */
@Component
public class PostSearchQueryBuilder {

    private static final float TITLE_BOOST   = 3.0f;
    private static final float CONTENT_BOOST = 1.0f;

    public NativeQuery buildKeywordAddressQuery(String keyword, String address, Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (address != null && !address.isBlank()) {
            bool.must(TermQuery.of(t -> t.field("address.keyword").value(address))._toQuery());
        }

        if (keyword != null && !keyword.isBlank()) {
            bool.should(MatchQuery.of(m -> m
                    .field("title").query(keyword)
                    .operator(Operator.And).boost(TITLE_BOOST))._toQuery());

            bool.should(MatchQuery.of(m -> m
                    .field("content").query(keyword)
                    .operator(Operator.And).boost(CONTENT_BOOST))._toQuery());

            bool.minimumShouldMatch("1");
        }

        return NativeQuery.builder()
                .withQuery(bool.build()._toQuery())
                .withPageable(pageable)
                .build();
    }
}
