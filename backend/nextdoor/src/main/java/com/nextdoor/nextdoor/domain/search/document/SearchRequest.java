package com.nextdoor.nextdoor.domain.search.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Pageable;

@Getter
@Builder
public class SearchRequest {
    private final String keyword;
    private final double lat;
    private final double lon;
    private final SearchSortType sortType;
    private final String category;
    private final Integer minPrice;
    private final Integer maxPrice;
    private final Pageable pageable;
}
