package com.nextdoor.nextdoor.domain.rentalreservation.application.port;

import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.PostDto;

import java.util.Optional;

public interface RentalReservationPostQueryPort {

    Optional<PostDto> findById(Long postId);
    Optional<PostDto> findByIdWithLock(Long postId);
}
