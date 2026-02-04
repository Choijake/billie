package com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.jpa;

import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RentalReservationJpaRepository extends JpaRepository<RentalReservation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalReservation r where r.id = :id")
    Optional<RentalReservation> findByIdForUpdate(@Param("id") Long id);
}
