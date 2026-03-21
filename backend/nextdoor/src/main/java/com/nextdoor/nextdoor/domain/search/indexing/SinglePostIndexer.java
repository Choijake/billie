package com.nextdoor.nextdoor.domain.search.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.nextdoor.nextdoor.domain.post.exception.PostIndexException;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.search.dto.PostWithLikeCountDto;
import com.nextdoor.nextdoor.domain.search.lock.IndexLockService;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import com.nextdoor.nextdoor.domain.search.document.PostDocumentMapper;
import com.nextdoor.nextdoor.domain.search.document.PostSearchRepository;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단건 Post 색인/삭제 전담 서비스. (SRP)
 * 전체 리인덱싱 오케스트레이션(ReindexOrchestrator)과 분리하여
 * 각 책임이 독립적으로 변경 가능하도록 한다.
 */
@Slf4j
@Service
@Profile("worker")
@RequiredArgsConstructor
public class SinglePostIndexer {

    private final PostRepository postRepository;
    private final ElasticsearchAsyncClient asyncEsClient;
    private final IndexLockService indexLockService;
    private final PostSearchRepository postSearchRepository;
    private final PostDocumentMapper documentMapper;
    private final SearchProperties props;

    public void indexSinglePost(Long postId) {
        PostWithLikeCountDto dto = postRepository.findDtoById(postId)
                .orElseThrow(() -> new PostIndexException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));

        PostDocument doc = documentMapper.toDocument(dto);

        if (indexLockService.isFullIndexLocked()) {
            indexLockService.addToPendingIndexQueue(doc);
            throw new PostIndexException("전체 인덱싱 진행 중 — 단건 색인 보류");
        }

        long version = documentMapper.toVersion(dto);
        asyncEsClient.index(i -> i
                .index(props.getIndexName())
                .id(dto.getPostId().toString())
                .version(version)
                .versionType(VersionType.ExternalGte)
                .document(doc)
        ).join();

        log.info("단건 색인 완료: id={}, version={}", postId, version);
    }

    @Transactional
    public void deleteSingleIndex(Long postId) {
        if (indexLockService.isFullIndexLocked()) {
            throw new PostIndexException("전체 인덱싱 진행 중 — 단건 삭제 보류");
        }
        postSearchRepository.deleteById(postId);
        log.info("단건 삭제 완료: id={}", postId);
    }
}
