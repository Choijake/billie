package com.nextdoor.nextdoor.domain.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PostSearchRepository extends ElasticsearchRepository<PostDocument, Long>, CustomPostSearchRepository {
}