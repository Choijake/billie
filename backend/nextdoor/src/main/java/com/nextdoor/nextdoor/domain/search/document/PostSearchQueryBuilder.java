package com.nextdoor.nextdoor.domain.search.document;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.json.JsonData;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.stereotype.Component;

@Component
public class PostSearchQueryBuilder {

    private static final float TITLE_BOOST   = 3.0f;
    private static final float RAW_BOOST     = 0.5f;
    private static final String GEO_DISTANCE = "5km";

    public NativeQuery buildRecommendedQuery(SearchRequest req) {
        BoolQuery.Builder base = buildBaseFilters(req);
        applyKeywordMust(base, req.getKeyword());

        FunctionScoreQuery functionScore = FunctionScoreQuery.of(fs -> fs
                .query(base.build()._toQuery())
                .functions(
                        FunctionScore.of(f -> f
                                .fieldValueFactor(fvf -> fvf
                                        .field("likeCount")
                                        .modifier(FieldValueFactorModifier.Log1p)
                                        .missing(0.0)
                                )
                                .weight(2.0)
                        ),
                        FunctionScore.of(f -> f
                                .gauss(gd -> gd
                                        .field("createdAt")
                                        .placement(gp -> gp
                                                .origin(JsonData.of("now"))
                                                .scale(JsonData.of("7d"))
                                                .decay(0.5)
                                        )
                                )
                                .weight(1.5)
                        ),
                        FunctionScore.of(f -> f
                                .gauss(gd -> gd
                                        .field("location")
                                        .placement(gp -> gp
                                                .origin(JsonData.of(req.getLat() + "," + req.getLon()))
                                                .scale(JsonData.of("2km"))
                                                .decay(0.5)
                                        )
                                )
                                .weight(1.0)
                        )
                )
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Multiply)
        );

        return NativeQuery.builder()
                .withQuery(functionScore._toQuery())
                .withPageable(req.getPageable())
                .build();
    }

    public NativeQuery buildNewestQuery(SearchRequest req) {
        BoolQuery.Builder base = buildBaseFilters(req);
        applyKeywordMust(base, req.getKeyword());

        return NativeQuery.builder()
                .withQuery(base.build()._toQuery())
                .withSort(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc)))
                .withPageable(req.getPageable())
                .build();
    }

    public NativeQuery buildCheapestQuery(SearchRequest req) {
        BoolQuery.Builder base = buildBaseFilters(req);
        applyKeywordMust(base, req.getKeyword());

        return NativeQuery.builder()
                .withQuery(base.build()._toQuery())
                .withSort(s -> s.field(f -> f.field("rentalFee").order(SortOrder.Asc)))
                .withPageable(req.getPageable())
                .build();
    }

    public NativeQuery buildExpensiveQuery(SearchRequest req) {
        BoolQuery.Builder base = buildBaseFilters(req);
        applyKeywordMust(base, req.getKeyword());

        return NativeQuery.builder()
                .withQuery(base.build()._toQuery())
                .withSort(s -> s.field(f -> f.field("rentalFee").order(SortOrder.Desc)))
                .withPageable(req.getPageable())
                .build();
    }

    public NativeQuery buildNearestQuery(SearchRequest req) {
        BoolQuery.Builder base = buildBaseFilters(req);
        applyKeywordMust(base, req.getKeyword());

        return NativeQuery.builder()
                .withQuery(base.build()._toQuery())
                .withSort(s -> s.geoDistance(gd -> gd
                        .field("location")
                        .location(l -> l.latlon(ll -> ll.lat(req.getLat()).lon(req.getLon())))
                        .order(SortOrder.Asc)
                        .distanceType(co.elastic.clients.elasticsearch._types.GeoDistanceType.Plane)
                ))
                .withPageable(req.getPageable())
                .build();
    }

    public NativeQuery buildAutocompleteQuery(String keyword, double lat, double lon) {
        BoolQuery autocomplete = BoolQuery.of(b -> b
                .filter(GeoDistanceQuery.of(gd -> gd
                        .field("location")
                        .location(l -> l.latlon(ll -> ll.lat(lat).lon(lon)))
                        .distance(GEO_DISTANCE)
                )._toQuery())
                .must(MatchQuery.of(m -> m
                        .field("title.autocomplete")
                        .query(keyword)
                        .operator(Operator.And)
                )._toQuery())
        );

        return NativeQuery.builder()
                .withQuery(autocomplete._toQuery())
                .withMaxResults(5)
                .build();
    }

    private BoolQuery.Builder buildBaseFilters(SearchRequest req) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        bool.filter(GeoDistanceQuery.of(gd -> gd
                .field("location")
                .location(l -> l.latlon(ll -> ll.lat(req.getLat()).lon(req.getLon())))
                .distance(GEO_DISTANCE)
        )._toQuery());

        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            bool.filter(TermQuery.of(t -> t
                    .field("category")
                    .value(req.getCategory())
            )._toQuery());
        }

        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            bool.filter(RangeQuery.of(r -> {
                var range = r.field("rentalFee");
                if (req.getMinPrice() != null) {
                    range.gte(JsonData.of(req.getMinPrice()));
                }
                if (req.getMaxPrice() != null) {
                    range.lte(JsonData.of(req.getMaxPrice()));
                }
                return range;
            })._toQuery());
        }

        return bool;
    }

    private void applyKeywordMust(BoolQuery.Builder bool, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }

        bool.must(MultiMatchQuery.of(m -> m
                .query(keyword)
                .fields("title^" + TITLE_BOOST, "content")
                .type(TextQueryType.BestFields)
                .operator(Operator.And)
        )._toQuery());

        bool.should(MatchQuery.of(m -> m
                .field("title.raw")
                .query(keyword)
                .boost(RAW_BOOST)
        )._toQuery());
    }
}
