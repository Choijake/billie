package com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.adapter;

import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.AiComparisonResult;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.repository.RentalReservationRepository;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.custom.RentalReservationCustomRepository;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.jpa.RentalReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RentalReservationAdapter implements RentalReservationRepository {

    private final RentalReservationJpaRepository jpaRepository;
    private final RentalReservationCustomRepository customRepository;

    @Override
    public Optional<RentalReservation> findByIdForUpdate(Long id) {
        return jpaRepository.findByIdForUpdate(id);
    }

    @Override
    public Optional<RentalReservation> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<AiComparisonResult> findRentalWithImagesByRentalId(Long rentalId) {
        return customRepository.findRentalWithImagesByRentalId(rentalId);
    }

    @Override
    public RentalReservation save(RentalReservation rentalReservation) {
        return jpaRepository.save(rentalReservation);
    }

    @Override
    public void delete(RentalReservation rentalReservation) {
        jpaRepository.delete(rentalReservation);
    }

    @Override
    public boolean existsOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        return customRepository.existsOverlap(postId, reqStart, reqEnd);
    }

    @Override
    public boolean existsConfirmedOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        return customRepository.existsConfirmedOverlap(postId, reqStart, reqEnd);
    }

    @Override
    public boolean existsPendingByRenterAndDateRange(Long postId, Long renterId, LocalDate reqStart, LocalDate reqEnd) {
        return customRepository.existsPendingByRenterAndDateRange(postId, renterId, reqStart, reqEnd);
    }

    @Override
    public List<RentalReservation> findPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        return customRepository.findPendingByPostIdAndDateRange(postId, reqStart, reqEnd);
    }

    @Override
    public List<RentalReservation> findByPostIdAndOwnerIdAndStatusIn(Long postId, Long ownerId, List<RentalReservationStatus> statuses) {
        return customRepository.findByPostIdAndOwnerIdAndStatusIn(postId, ownerId, statuses);
    }

    @Override
    public List<RentalReservation> findByRenterIdAndStatusIn(Long renterId, List<RentalReservationStatus> statuses) {
        return customRepository.findByRenterIdAndStatusIn(renterId, statuses);
    }

    @Override
    public List<RentalReservation> findConfirmedByPostIdFromDate(Long postId, LocalDate fromDate) {
        return customRepository.findConfirmedByPostIdFromDate(postId, fromDate);
    }

    @Override
    public long countPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        return customRepository.countPendingByPostIdAndDateRange(postId, reqStart, reqEnd);
    }

    @Override
    public List<RentalReservation> findPendingByPostIdAndDateRangeForUpdate(Long postId, LocalDate startDate, LocalDate endDate) {
        return customRepository.findPendingByPostIdAndDateRangeForUpdate(postId, startDate, endDate);
    }
}