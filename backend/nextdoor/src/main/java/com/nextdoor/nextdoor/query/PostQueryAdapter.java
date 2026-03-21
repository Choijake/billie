package com.nextdoor.nextdoor.query;

import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.member.domain.model.QMember;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.domain.QPost;
import com.nextdoor.nextdoor.domain.post.domain.QPostLike;
import com.nextdoor.nextdoor.domain.post.domain.QPostLikeCount;
import com.nextdoor.nextdoor.domain.post.domain.QProductImage;
import com.nextdoor.nextdoor.domain.post.exception.NoSuchPostException;
import com.nextdoor.nextdoor.domain.post.port.PostQueryPort;
import com.nextdoor.nextdoor.domain.post.service.dto.LocationDto;
import com.nextdoor.nextdoor.domain.post.service.dto.PostDetailResult;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostCommand;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostResult;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

@Adapter
@RequiredArgsConstructor
public class PostQueryAdapter implements PostQueryPort {

    private final JPAQueryFactory queryFactory;
    private final QPost post = QPost.post;
    private final QMember member = QMember.member;
    private final QProductImage productImage = QProductImage.productImage;
    private final QPostLike postLike = QPostLike.postLike;
    private final QPostLikeCount postLikeCount = QPostLikeCount.postLikeCount;

    @Override
    public Page<SearchPostResult> searchPostsByMemberAddress(SearchPostCommand command) {
        String userAddress = queryFactory
                .select(member.address)
                .from(member)
                .where(member.id.eq(command.getUserId()))
                .fetchOne();

        JPAQuery<SearchPostResult> query = basePostListQuery();
        query.join(member).on(post.authorId.eq(member.id));

        if (userAddress != null) {
            query.where(post.address.eq(userAddress));
        }

        return paginate(query, command.getPageable(), withSort(command.getPageable().getSort()));
    }

    @Override
    public Page<SearchPostResult> searchPostsLikedByMember(SearchPostCommand command) {
        JPAQuery<SearchPostResult> query = basePostListQuery()
                .join(postLike).on(postLike.post.eq(post).and(postLike.memberId.eq(command.getUserId())));

        return paginate(query, command.getPageable(), new OrderSpecifier[0]);
    }

    @Override
    public PostDetailResult getPostDetail(Long postId) {
        Post postEntity = queryFactory
                .selectFrom(post)
                .where(post.id.eq(postId))
                .fetchOne();

        if (postEntity == null) {
            throw new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다.");
        }

        String nickname = queryFactory
                .select(member.nickname)
                .from(member)
                .where(member.id.eq(postEntity.getAuthorId()))
                .fetchOne();

        List<String> productImages = queryFactory
                .select(productImage.imageUrl)
                .from(productImage)
                .where(productImage.post.id.eq(postId))
                .fetch();

        LocationDto location = null;
        if (postEntity.getLatitude() != null && postEntity.getLongitude() != null) {
            location = new LocationDto(postEntity.getLatitude(), postEntity.getLongitude());
        }

        Integer likeCount = queryFactory
                .select(postLikeCount.likeCount.intValue())
                .from(postLikeCount)
                .where(postLikeCount.postId.eq(postId))
                .fetchOne();

        return PostDetailResult.builder()
                .title(postEntity.getTitle())
                .content(postEntity.getContent())
                .rentalFee(Math.toIntExact(postEntity.getRentalFee()))
                .deposit(Math.toIntExact(postEntity.getDeposit()))
                .address(postEntity.getAddress())
                .location(location)
                .productImages(productImages)
                .category(postEntity.getCategory().toString())
                .authorId(postEntity.getAuthorId())
                .nickname(nickname)
                .likeCount(likeCount != null ? likeCount : 0)
                .build();
    }

    // ──── private helpers ────────────────────────────────────────────────────

    /**
     * 목록 조회에 공통으로 사용하는 프로젝션 쿼리.
     * 이전에 searchPostsByMemberAddress / searchPostsLikedByMember 에 중복 존재하던 코드를 추출. (DRY)
     */
    private JPAQuery<SearchPostResult> basePostListQuery() {
        return queryFactory
                .select(Projections.constructor(
                        SearchPostResult.class,
                        post.id,
                        post.title,
                        thumbnailSubquery(),
                        post.rentalFee,
                        post.deposit,
                        postLikeCount.likeCount.intValue().coalesce(0),
                        Expressions.constant(0)
                ))
                .from(post)
                .leftJoin(postLikeCount).on(postLikeCount.postId.eq(post.id))
                .groupBy(post.id);
    }

    private com.querydsl.core.types.Expression<String> thumbnailSubquery() {
        return queryFactory
                .select(productImage.imageUrl.min())
                .from(productImage)
                .where(productImage.post.id.eq(post.id));
    }

    private Page<SearchPostResult> paginate(JPAQuery<SearchPostResult> query,
                                             Pageable pageable,
                                             OrderSpecifier<?>[] orders) {
        long total = query.fetchCount();
        List<SearchPostResult> results = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(orders)
                .fetch();
        return new PageImpl<>(results, pageable, total);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderSpecifier<?>[] withSort(Sort sort) {
        List<OrderSpecifier<?>> specs = new ArrayList<>();
        sort.forEach(order -> {
            Order direction = order.isAscending() ? Order.ASC : Order.DESC;
            PathBuilder<Post> path = new PathBuilder<>(Post.class, "post");
            specs.add(new OrderSpecifier(direction, path.get(order.getProperty())));
        });
        return specs.toArray(new OrderSpecifier[0]);
    }
}
