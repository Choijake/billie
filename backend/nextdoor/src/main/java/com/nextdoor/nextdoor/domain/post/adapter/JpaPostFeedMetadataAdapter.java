package com.nextdoor.nextdoor.domain.post.adapter;

import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.port.PostFeedMetadata;
import com.nextdoor.nextdoor.domain.post.port.PostFeedMetadataPort;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaPostFeedMetadataAdapter implements PostFeedMetadataPort {

    private final PostRepository postRepository;

    @Override
    public List<PostFeedMetadata> loadByIdsForFeed(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }

        List<Post> posts = postRepository.findAllByIdInAndDeletedFalse(postIds);

        List<PostFeedMetadata> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            result.add(new PostFeedMetadata(
                    post.getId(),
                    post.getTitle(),
                    post.getContent(),
                    post.getRentalFee(),
                    post.getDeposit(),
                    post.getAddress(),
                    post.getLatitude(),
                    post.getLongitude(),
                    post.getCategory(),
                    post.getCreatedAt(),
                    post.getUpdatedAt()
            ));
        }
        return result;
    }
}