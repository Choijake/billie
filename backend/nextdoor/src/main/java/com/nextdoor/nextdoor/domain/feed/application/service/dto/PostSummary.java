package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import com.nextdoor.nextdoor.domain.post.search.PostDocument;

import java.time.LocalDateTime;

public record PostSummary(
        Long id,
        String title,
        Long rentalFee,
        Long deposit,
        String address,
        String category,
        Integer likeCount,
        LocalDateTime createdAt,
        Double lat,
        Double lon
) {
    public static PostSummary from(PostDocument doc) {
        Double lat = null;
        Double lon = null;

        if (doc.getLocation() != null) {
            lat = doc.getLocation().getLat();
            lon = doc.getLocation().getLon();
        }

        return new PostSummary(
                doc.getId(),
                doc.getTitle(),
                doc.getRentalFee(),
                doc.getDeposit(),
                doc.getAddress(),
                doc.getCategory(),
                doc.getLikeCount(),
                doc.getCreatedAt(),
                lat,
                lon
        );
    }
}