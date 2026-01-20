package com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.custom;

import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.AiComparisonResult;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RentalReservationCustomRepository {

    boolean existsOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd);
    Optional<AiComparisonResult> findRentalWithImagesByRentalId(Long rentalId);
    boolean existsConfirmedOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd);
    boolean existsPendingByRenterAndDateRange(Long postId, Long renterId, LocalDate reqStart, LocalDate reqEnd);
    List<RentalReservation> findPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd);
    List<RentalReservation> findByPostIdAndOwnerIdAndStatusIn(Long postId, Long ownerId, List<RentalReservationStatus> statuses);
    List<RentalReservation> findByRenterIdAndStatusIn(Long renterId, List<RentalReservationStatus> statuses);
    List<RentalReservation> findConfirmedByPostIdFromDate(Long postId, LocalDate fromDate);
    long countPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd);
}