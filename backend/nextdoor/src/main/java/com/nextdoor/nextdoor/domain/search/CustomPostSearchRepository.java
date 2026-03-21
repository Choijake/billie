package com.nextdoor.nextdoor.domain.search;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomPostSearchRepository {
    Page<PostDocument> searchByKeywordWithAddress(String keyword, String address, Pageable pageable);
}