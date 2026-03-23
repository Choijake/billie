package com.nextdoor.nextdoor.domain.search.document;

import com.nextdoor.nextdoor.domain.search.dto.PostWithLikeCountDto;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

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
                .rentalFee(dto.getRentalFee() != null ? dto.getRentalFee().intValue() : null)
                .deposit(dto.getDeposit() != null ? dto.getDeposit().intValue() : null)
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
