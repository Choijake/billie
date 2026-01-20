package com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class ReservationSaveRequestDto {

    @NotNull
    private Long postId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;
}