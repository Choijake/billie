package com.nextdoor.nextdoor.domain.feed.application.service.dto;

/**
 * 피드 조회 요청 DTO
 */
public record FeedRequest(
        Double latitude,
        Double longitude
) {
    public FeedRequest {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("위도/경도는 필수입니다");
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("위도 범위: -90 ~ 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("경도 범위: -180 ~ 180");
        }
    }
}
