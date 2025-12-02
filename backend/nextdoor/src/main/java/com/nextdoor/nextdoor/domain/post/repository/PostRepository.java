package com.nextdoor.nextdoor.domain.post.repository;

import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.search.dto.PostWithLikeCountDto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p.id FROM Post p WHERE p.id > :lastId ORDER BY p.id ASC")
    List<Long> findPostIdsAfter(@Param("lastId") Long lastId, Pageable pageable);

    @Query("""
           SELECT p.id
           FROM Post p
           WHERE p.id > :lastId
             AND p.updatedAt <= :cutoff
           ORDER BY p.id ASC
           """)
    List<Long> findPostIdsAfterByCutoff(@Param("lastId") Long lastId,
                                        @Param("cutoff") java.time.LocalDateTime cutoff,
                                        Pageable pageable);


    @Query("SELECT new com.nextdoor.nextdoor.domain.post.search.dto.PostWithLikeCountDto(" +
            "p.id, p.title, p.content, p.rentalFee, p.deposit, " +
            "p.address, p.latitude, p.longitude, p.category, " +
            "p.createdAt, p.updatedAt, plc.likeCount) " +
            "FROM Post p " +
            "LEFT JOIN PostLikeCount plc ON p.id = plc.postId " +
            "WHERE p.id IN :ids " +
            "ORDER BY p.id ASC")
    List<PostWithLikeCountDto> findPostsWithLikeCountByIds(@Param("ids") List<Long> ids);

    @Query("SELECT new com.nextdoor.nextdoor.domain.post.search.dto.PostWithLikeCountDto(" +
            "p.id, p.title, p.content, p.rentalFee, p.deposit, " +
            "p.address, p.latitude, p.longitude, p.category, " +
            "p.createdAt, p.updatedAt, plc.likeCount) " +
            "FROM Post p " +
            "LEFT JOIN PostLikeCount plc ON p.id = plc.postId " +
            "WHERE p.id = :postId")
    Optional<PostWithLikeCountDto> findDtoById(@Param("postId") Long postId);

    @Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime currentTimestamp();
}