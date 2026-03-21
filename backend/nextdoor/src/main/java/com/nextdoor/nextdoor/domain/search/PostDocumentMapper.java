package com.nextdoor.nextdoor.domain.search;

import com.nextdoor.nextdoor.domain.search.dto.PostWithLikeCountDto;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * PostWithLikeCountDto → PostDocument 변환 책임. (SRP)
 * 이전에는 IndexingMessageConsumer 안에 private 메서드로 존재했으며,
 * Consumer의 인덱싱 실행 책임과 섞여 있었다.
 */
@Component
public class PostDocumentMapper {

    public PostDocument toDocument(PostWithLikeCountDto dto) {
        PostDocument.GeoPoint location = null;
        if (dto.getLatitude() != null && dto.getLongitude() != null) {
            location = new PostDocument.GeoPoint(dto.getLatitude(), dto.getLongitude());
        }
        return PostDocument.builder()
                .id(dto.getPostId())
                .title(dto.getTitle())
                .content(dto.getContent())
                .rentalFee(dto.getRentalFee())
                .deposit(dto.getDeposit())
                .address(dto.getAddress())
                .location(location)
                .category(dto.getCategory() != null ? dto.getCategory().name() : null)
                .likeCount(dto.getLikeCount().intValue())
                .createdAt(dto.getCreatedAt())
                .build();
    }

    public long toVersion(PostWithLikeCountDto dto) {
        return dto.getUpdatedAt()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
