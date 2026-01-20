package com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DateReservationStatusDto {
    private LocalDate date;
    private boolean isReserved;
    private Long reservationId;
    private String renterName;
    private String renterProfileImage;
}