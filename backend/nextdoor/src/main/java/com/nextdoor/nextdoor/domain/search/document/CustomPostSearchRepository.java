package com.nextdoor.nextdoor.domain.search.document;

import org.springframework.data.domain.Page;

import java.util.List;

public interface CustomPostSearchRepository {
    Page<PostDocument> search(SearchRequest request);
    List<PostDocument> autocomplete(String keyword, double lat, double lon);
}
