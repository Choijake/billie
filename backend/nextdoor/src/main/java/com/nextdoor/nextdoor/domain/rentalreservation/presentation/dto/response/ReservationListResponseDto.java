package com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response;

import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.PostDto;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.ReservationMemberQueryDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationListResponseDto {
    private Long reservationId;
    private Long postId;
    private String postTitle;
    private String postThumbnail;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal rentalFee;
    private BigDecimal deposit;
    private RentalReservationStatus status;
    private LocalDateTime createdAt;

    private Long renterId;
    private String renterName;
    private String renterProfileImage;

    private Long ownerId;
    private String ownerName;
    private String ownerProfileImage;

    public static ReservationListResponseDto from(
            RentalReservation reservation,
            PostDto post,
            ReservationMemberQueryDto renter) {

        return ReservationListResponseDto.builder()
                .reservationId(reservation.getId())
                .postId(post.getPostId())
                .postTitle(post.getTitle())
                .postThumbnail(post.getProductImage())
                .startDate(reservation.getPeriod().getStartDate())
                .endDate(reservation.getPeriod().getEndDate())
                .rentalFee(reservation.getRentalFee().getAmount())
                .deposit(reservation.getDeposit().getAmount())
                .status(reservation.getRentalReservationStatus())
                .createdAt(reservation.getCreatedAt())
                .renterId(reservation.getRenterId())
                .renterName(renter.getNickname())
                .renterProfileImage(renter.getProfileImageUrl())
                .ownerId(post.getAuthorId())
                .ownerName(post.getAuthorName())
                .ownerProfileImage(post.getAuthorProfileImageUrl())
                .build();
    }
}