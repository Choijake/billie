package com.nextdoor.nextdoor.domain.post.domain;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long id;

    @Column(name = "title", length = 255)
    private String title;

    @Column(length = 1000)
    private String content;

    @Column(name = "rental_fee")
    private Long rentalFee;

    @Column(name = "deposit")
    private Long deposit;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Column(name = "product_image")
    private List<ProductImage> productImages;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Column(name = "author_id")
    private Long authorId;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PostLike> likes = new HashSet<>();

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void addProductImage(String imageUrl) {
        ProductImage productImage = ProductImage.builder()
                .imageUrl(imageUrl)
                .post(this)
                .build();

        this.productImages.add(productImage);
    }

    public void addLike(Long memberId) {
        boolean alreadyLiked = this.likes.stream()
                .anyMatch(like -> like.getMemberId().equals(memberId));

        if (!alreadyLiked) {
            PostLike like = new PostLike(this, memberId);
            this.likes.add(like);
        }
    }

    public void removeLike(Long memberId) {
        this.likes.removeIf(like -> like.getMemberId().equals(memberId));
    }

    public int getLikeCount() {
        return this.likes.size();
    }

    public void softDelete() {
        this.deleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    @Builder
    public Post(Long id,
                String title,
                String content,
                Long rentalFee,
                Long deposit,
                String address,
                Double latitude,
                Double longitude,
                List<ProductImage> productImages,
                Category category,
                Long authorId,
                boolean deleted,
                LocalDateTime deletedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.rentalFee = rentalFee;
        this.deposit = deposit;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.productImages = productImages;
        this.category = category;
        this.authorId = authorId;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
    }
}