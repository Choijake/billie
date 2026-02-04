package com.nextdoor.nextdoor.domain.rentalreservation.domain.repository;

import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.AiComparisonResult;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RentalReservationRepository {

    Optional<RentalReservation> findById(Long id);
    Optional<AiComparisonResult> findRentalWithImagesByRentalId(Long rentalId);
    RentalReservation save(RentalReservation rentalReservation);
    void delete(RentalReservation rentalReservation);
    boolean existsOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd);
    // 확정된 예약만 체크
    boolean existsConfirmedOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd);
    // 중복 신청 방지
    boolean existsPendingByRenterAndDateRange(Long postId, Long renterId, LocalDate reqStart, LocalDate reqEnd);
    // 특정 날짜 범위의 대기 중인 예약 목록
    List<RentalReservation> findPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd);
    // 작성자의 게시물별 예약 목록
    List<RentalReservation> findByPostIdAndOwnerIdAndStatusIn(Long postId, Long ownerId, List<RentalReservationStatus> statuses);
    // 신청자의 예약 목록
    List<RentalReservation> findByRenterIdAndStatusIn(Long renterId, List<RentalReservationStatus> statuses);
    // 날짜별 확정 현황 조회
    List<RentalReservation> findConfirmedByPostIdFromDate(Long postId, LocalDate fromDate);
    // 대기 중인 예약 수 조회
    long countPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd);
    List<RentalReservation> findPendingByPostIdAndDateRangeForUpdate(@NotNull Long postId, LocalDate startDate, LocalDate endDate);
    Optional<RentalReservation> findByIdForUpdate(Long reservationId);
}