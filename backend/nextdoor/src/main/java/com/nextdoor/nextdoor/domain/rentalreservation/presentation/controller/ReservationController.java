package com.nextdoor.nextdoor.domain.rentalreservation.presentation.controller;

import com.nextdoor.nextdoor.domain.rentalreservation.application.service.ReservationService;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationSaveRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationStatusUpdateRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationUpdateRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.DateReservationStatusDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.ReservationListResponseDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.ReservationResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 기능 1: 예약 신청 (PENDING)
     */
    @PostMapping
    public ResponseEntity<ReservationResponseDto> createReservation(
            @RequestHeader("userId") Long userId,
            @RequestBody @Valid ReservationSaveRequestDto requestDto) {

        // 1. 요청이 들어왔는지 확인
        System.out.println("DEBUG >>> [ReservationController] 진입 - userId: " + userId);
        System.out.println("DEBUG >>> [ReservationController] DTO: " + requestDto);

        try {
            // 2. 서비스 로직 실행
            ReservationResponseDto response =
                    reservationService.createPendingReservation(userId, requestDto);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            // 3. [핵심] 에러 로그 강제 출력 !!!
            System.err.println("🔥🔥🔥 [FATAL ERROR] 예약 생성 중 치명적 오류 발생 🔥🔥🔥");
            e.printStackTrace(); // 스택 트레이스 출력
            throw e; // 다시 던져서 기존 응답 흐름 유지
        }
    }

    /**
     * 예약 수정
     */
    @PutMapping("/{reservationId}")
    public ResponseEntity<ReservationResponseDto> updateReservation(
            @RequestHeader("userId") Long userId,
            @PathVariable Long reservationId,
            @RequestBody @Valid ReservationUpdateRequestDto requestDto) {

        ReservationResponseDto response =
                reservationService.updateReservation(userId, reservationId, requestDto);

        return ResponseEntity.ok(response);
    }

    /**
     * 예약 확정
     */
    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<Void> confirmReservation(
            @RequestHeader("userId") Long userId,
            @PathVariable Long reservationId,
            @RequestBody @Valid ReservationStatusUpdateRequestDto requestDto) {

        reservationService.confirmReservation(userId, reservationId, requestDto);

        return ResponseEntity.ok().build();
    }

    /**
     * 예약 취소
     */
    @DeleteMapping("/{reservationId}")
    public ResponseEntity<Void> deleteReservation(
            @RequestHeader("userId") Long userId,
            @PathVariable Long reservationId) {

        reservationService.deleteReservation(userId, reservationId);

        return ResponseEntity.noContent().build();
    }

    /**
     * 기능 2: 날짜별 예약 확정 현황 조회 (달력)
     */
    @GetMapping("/calendar")
    public ResponseEntity<List<DateReservationStatusDto>> getReservationCalendar(
            @RequestParam Long postId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate) {

        List<DateReservationStatusDto> calendar =
                reservationService.getReservationCalendar(postId, fromDate);

        return ResponseEntity.ok(calendar);
    }

    /**
     * 기능 3-1: 작성자의 대기 중인 예약 목록 조회
     */
    @GetMapping("/owner/pending")
    public ResponseEntity<List<ReservationListResponseDto>> getPendingReservationsByOwner(
            @RequestHeader("userId") Long userId,
            @RequestParam Long postId) {

        List<ReservationListResponseDto> reservations =
                reservationService.getPendingReservationsByOwner(userId, postId);

        return ResponseEntity.ok(reservations);
    }

    /**
     * 기능 3-2: 작성자의 확정된 예약 목록 조회
     */
    @GetMapping("/owner/confirmed")
    public ResponseEntity<List<ReservationListResponseDto>> getConfirmedReservationsByOwner(
            @RequestHeader("userId") Long userId,
            @RequestParam Long postId) {

        List<ReservationListResponseDto> reservations =
                reservationService.getConfirmedReservationsByOwner(userId, postId);

        return ResponseEntity.ok(reservations);
    }

    /**
     * 기능 3: 작성자의 예약 목록 조회 (필터 통합)
     */
    @GetMapping("/owner")
    public ResponseEntity<List<ReservationListResponseDto>> getOwnerReservations(
            @RequestHeader("userId") Long userId,
            @RequestParam Long postId,
            @RequestParam(defaultValue = "PENDING") String filter) {

        List<ReservationListResponseDto> reservations;

        if ("PENDING".equalsIgnoreCase(filter)) {
            reservations = reservationService.getPendingReservationsByOwner(userId, postId);
        } else if ("CONFIRMED".equalsIgnoreCase(filter)) {
            reservations = reservationService.getConfirmedReservationsByOwner(userId, postId);
        } else {
            throw new IllegalArgumentException("Invalid filter: " + filter);
        }

        return ResponseEntity.ok(reservations);
    }

    /**
     * 기능 4: 신청자의 예약 목록 조회
     */
    @GetMapping("/renter")
    public ResponseEntity<List<ReservationListResponseDto>> getRenterReservations(
            @RequestHeader("userId") Long userId,
            @RequestParam(required = false) List<RentalReservationStatus> statuses) {

        List<ReservationListResponseDto> reservations =
                reservationService.getReservationsByRenter(userId, statuses);

        return ResponseEntity.ok(reservations);
    }
}