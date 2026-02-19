package com.nextdoor.nextdoor.domain.rentalreservation.application.event;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReservationCreatedEvent {
    private Long memberId;
    private Category category;
}