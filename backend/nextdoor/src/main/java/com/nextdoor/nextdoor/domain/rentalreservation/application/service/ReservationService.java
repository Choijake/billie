package com.nextdoor.nextdoor.domain.rentalreservation.application.service;

import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.Money;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationSaveRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationStatusUpdateRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationUpdateRequestDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.DateReservationStatusDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.ReservationListResponseDto;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.response.ReservationResponseDto;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationProcess;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservationStatus;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.exception.AlreadyConfirmedException;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.exception.IllegalStatusException;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.exception.NoSuchReservationException;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.exception.UnauthorizedException;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.message.RentalStatusMessage;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.message.RequestRemittanceStatusMessage;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.MemberUuidQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.RentalDetailQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.RentalReservationPostQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.ReservationMemberQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.repository.RentalReservationRepository;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.PostDto;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.ReservationMemberQueryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional
public class ReservationService {

    private final MemberUuidQueryPort memberUuidQueryPort;
    private final RentalDetailQueryPort rentalDetailQueryPort;
    private final RentalReservationRepository rentalReservationRepository;
    private final RentalReservationPostQueryPort rentalReservationPostQueryPort;
    private final ReservationMemberQueryPort reservationMemberQueryPort;
    private final SimpMessagingTemplate messagingTemplate;
    private static final int MAX_PENDING_RESERVATIONS = 100;

    /**
     * 기존: HOLD 방식 예약 생성 (하위 호환성)
     */
    @Transactional
    public ReservationResponseDto createHoldReservation(
            Long loginUserId,
            ReservationSaveRequestDto reservationSaveRequestDto) {

        PostDto post = rentalReservationPostQueryPort
                .findByIdWithLock(reservationSaveRequestDto.getPostId())
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다."));

        if (rentalReservationRepository.existsOverlap(
                post.getPostId(),
                reservationSaveRequestDto.getStartDate(),
                reservationSaveRequestDto.getEndDate())) {
            throw new IllegalStateException("이미 예약된 일정입니다.");
        }

        RentalReservation rentalReservation = RentalReservation.createHold(
                reservationSaveRequestDto.getStartDate(),
                reservationSaveRequestDto.getEndDate(),
                new Money(post.getRentalFee()),
                new Money(post.getDeposit()),
                post.getAuthorId(),
                loginUserId,
                post.getPostId()
        );

        rentalReservationRepository.save(rentalReservation);

        ReservationMemberQueryDto member = reservationMemberQueryPort
                .findById(loginUserId)
                .orElseThrow();

        ReservationResponseDto response = ReservationResponseDto.from(
                rentalReservation, post, member);

        messagingTemplate.convertAndSend(
                "/topic/rental-reservation/" + post.getAuthorUuid() + "/status",
                response);

        return response;
    }

    /**
     * 신규: PENDING 방식 예약 생성 (다중 예약)
     */
    @Transactional
    public ReservationResponseDto createPendingReservation(
            Long loginUserId,
            ReservationSaveRequestDto requestDto) {

        // 1. 게시물 조회 (락 불필요 - 확정 체크만)
        PostDto post = rentalReservationPostQueryPort
                .findById(requestDto.getPostId())
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다."));

        // 2. 해당 날짜에 이미 확정된 예약이 있는지 체크
        if (rentalReservationRepository.existsConfirmedOverlap(
                post.getPostId(),
                requestDto.getStartDate(),
                requestDto.getEndDate())) {
            throw new IllegalStateException("해당 날짜는 이미 예약이 확정되었습니다.");
        }

        // 3. 중복 신청 방지
        if (rentalReservationRepository.existsPendingByRenterAndDateRange(
                post.getPostId(),
                loginUserId,
                requestDto.getStartDate(),
                requestDto.getEndDate())) {
            throw new IllegalStateException("이미 해당 날짜에 예약 신청하셨습니다.");
        }

        // 4. 예약 수 제한 체크
        long pendingCount = rentalReservationRepository.countPendingByPostIdAndDateRange(
                post.getPostId(),
                requestDto.getStartDate(),
                requestDto.getEndDate()
        );

        if (pendingCount >= MAX_PENDING_RESERVATIONS) {
            throw new IllegalStateException("해당 날짜의 예약 신청이 마감되었습니다.");
        }

        // 5. PENDING 상태로 예약 생성
        RentalReservation reservation = RentalReservation.createPending(
                requestDto.getStartDate(),
                requestDto.getEndDate(),
                new Money(post.getRentalFee()),
                new Money(post.getDeposit()),
                post.getAuthorId(),
                loginUserId,
                post.getPostId()
        );

        rentalReservationRepository.save(reservation);

        // 6. 응답 DTO 생성
        ReservationMemberQueryDto member = reservationMemberQueryPort
                .findById(loginUserId)
                .orElseThrow();

        ReservationResponseDto response = ReservationResponseDto.from(
                reservation, post, member);

        // 7. 작성자에게 실시간 알림
        messagingTemplate.convertAndSend(
                "/topic/rental-reservation/" + post.getAuthorUuid() + "/new-request",
                response
        );

        return response;
    }

    /**
     * 기존: 예약 수정
     */
    @Transactional
    public ReservationResponseDto updateReservation(
            Long loginUserId,
            Long reservationId,
            ReservationUpdateRequestDto reservationUpdateRequestDto) {

        RentalReservation rentalReservation = rentalReservationRepository
                .findById(reservationId)
                .orElseThrow(NoSuchReservationException::new);

        validateOwner(loginUserId, rentalReservation);

        rentalReservation.modifyReservationDetails(
                reservationUpdateRequestDto.getStartDate(),
                reservationUpdateRequestDto.getEndDate(),
                new Money(reservationUpdateRequestDto.getRentalFee()),
                new Money(reservationUpdateRequestDto.getDeposit())
        );

        return ReservationResponseDto.from(
                rentalReservation,
                rentalReservationPostQueryPort.findById(rentalReservation.getPostId()).orElseThrow(),
                reservationMemberQueryPort.findById(loginUserId).orElseThrow());
    }

    /**
     * 핵심: 예약 확정 (1명 선택 → 나머지 자동 거절)
     */
    @Transactional
    public void confirmReservation(
            Long loginUserId,
            Long reservationId,
            ReservationStatusUpdateRequestDto requestDto) {

        // 1. 상태 검증
        if (requestDto.getStatus() != RentalReservationStatus.CONFIRMED) {
            throw new IllegalStatusException("잘못된 status입니다.");
        }

        // 2. 예약 조회 및 권한 체크
        RentalReservation selectedReservation = rentalReservationRepository
                .findById(reservationId)
                .orElseThrow(NoSuchReservationException::new);

        validateOwner(loginUserId, selectedReservation);

        // 3. PENDING 상태 검증
        if (selectedReservation.getRentalReservationStatus() != RentalReservationStatus.PENDING) {
            throw new IllegalStatusException("대기 중인 예약만 확정할 수 있습니다.");
        }

        // 4. 비관적 락으로 동시성 제어 (이미 확정된 예약이 있는지)
        boolean alreadyConfirmed = rentalReservationRepository.existsConfirmedOverlap(
                selectedReservation.getPostId(),
                selectedReservation.getPeriod().getStartDate(),
                selectedReservation.getPeriod().getEndDate()
        );

        if (alreadyConfirmed) {
            throw new AlreadyConfirmedException("해당 날짜에 이미 확정된 예약이 있습니다.");
        }

        // 5. 선택한 예약 확정
        selectedReservation.changeStatus(RentalReservationStatus.CONFIRMED);

        // 6. 같은 날짜 범위의 나머지 대기 예약들 거절
        List<RentalReservation> overlappingPending =
                rentalReservationRepository.findPendingByPostIdAndDateRange(
                        selectedReservation.getPostId(),
                        selectedReservation.getPeriod().getStartDate(),
                        selectedReservation.getPeriod().getEndDate()
                );

        List<RentalReservation> rejectedReservations = new ArrayList<>();

        for (RentalReservation pending : overlappingPending) {
            if (!pending.getId().equals(reservationId)) {
                pending.changeStatus(RentalReservationStatus.REJECTED);
                rejectedReservations.add(pending);
            }
        }

        // 7. 확정 알림 전송 (선택된 사람)
        sendConfirmationNotification(selectedReservation);

        // 8. 거절 알림 전송 (나머지 사람들)
        sendRejectionNotifications(rejectedReservations);

        // 9. 작성자에게 실시간 업데이트
        notifyOwnerReservationConfirmed(selectedReservation);

        log.info("예약 확정 완료 - reservationId: {}, 거절된 예약 수: {}",
                reservationId, rejectedReservations.size());
    }

    /**
     * 기존: 예약 상태 업데이트 (다른 상태 변경용)
     */
    @Transactional
    public ReservationResponseDto updateReservationStatus(
            Long loginUserId,
            RentalReservation rentalReservation,
            ReservationStatusUpdateRequestDto reservationStatusUpdateRequestDto) {

        validateOwner(loginUserId, rentalReservation);
        validateNotConfirmed(rentalReservation);
        rentalReservation.changeStatus(reservationStatusUpdateRequestDto.getStatus());

        return ReservationResponseDto.from(
                rentalReservation,
                rentalReservationPostQueryPort.findById(rentalReservation.getPostId()).orElseThrow(),
                reservationMemberQueryPort.findById(loginUserId).orElseThrow());
    }

    /**
     * 기존: 예약 삭제 (PENDING 상태만)
     */
    @Transactional
    public void deleteReservation(Long loginUserId, Long reservationId) {
        RentalReservation rentalReservation = rentalReservationRepository
                .findById(reservationId)
                .orElseThrow(NoSuchReservationException::new);

        validateOwnerOrRenter(loginUserId, rentalReservation);
        validateNotConfirmed(rentalReservation);

        rentalReservationRepository.delete(rentalReservation);
    }

    // ==================== 조회 기능 ====================

    /**
     * 신규: 날짜별 예약 확정 현황 조회 (달력용)
     */
    @Transactional(readOnly = true)
    public List<DateReservationStatusDto> getReservationCalendar(
            Long postId,
            LocalDate fromDate) {

        // 내일부터 조회
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate startDate = fromDate != null ? fromDate : tomorrow;

        // 확정된 예약 목록 조회
        List<RentalReservation> confirmedReservations =
                rentalReservationRepository.findConfirmedByPostIdFromDate(postId, startDate);

        // 날짜 범위를 개별 날짜로 펼치기
        List<DateReservationStatusDto> result = new ArrayList<>();

        for (RentalReservation reservation : confirmedReservations) {
            LocalDate current = reservation.getPeriod().getStartDate();
            LocalDate end = reservation.getPeriod().getEndDate();

            // 신청자 정보 조회
            ReservationMemberQueryDto renter = reservationMemberQueryPort
                    .findById(reservation.getRenterId())
                    .orElse(null);

            while (!current.isAfter(end)) {
                result.add(DateReservationStatusDto.builder()
                        .date(current)
                        .isReserved(true)
                        .reservationId(reservation.getId())
                        .renterName(renter != null ? renter.getNickname() : "Unknown")
                        .renterProfileImage(renter != null ? renter.getProfileImageUrl() : null)
                        .build());

                current = current.plusDays(1);
            }
        }

        return result;
    }

    /**
     * 신규: 작성자의 대기 중인 예약 목록 조회
     */
    @Transactional(readOnly = true)
    public List<ReservationListResponseDto> getPendingReservationsByOwner(
            Long ownerId,
            Long postId) {

        // 권한 체크
        PostDto post = rentalReservationPostQueryPort
                .findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다."));

        if (!post.getAuthorId().equals(ownerId)) {
            throw new UnauthorizedException("권한이 없습니다.");
        }

        // PENDING 상태 예약 목록
        List<RentalReservation> pendingReservations =
                rentalReservationRepository.findByPostIdAndOwnerIdAndStatusIn(
                        postId,
                        ownerId,
                        List.of(RentalReservationStatus.PENDING)
                );

        return pendingReservations.stream()
                .map(reservation -> {
                    ReservationMemberQueryDto renter = reservationMemberQueryPort
                            .findById(reservation.getRenterId())
                            .orElseThrow();
                    return ReservationListResponseDto.from(reservation, post, renter);
                })
                .collect(Collectors.toList());
    }

    /**
     * 신규: 작성자의 확정된 예약 목록 조회
     */
    @Transactional(readOnly = true)
    public List<ReservationListResponseDto> getConfirmedReservationsByOwner(
            Long ownerId,
            Long postId) {

        // 권한 체크
        PostDto post = rentalReservationPostQueryPort
                .findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다."));

        if (!post.getAuthorId().equals(ownerId)) {
            throw new UnauthorizedException("권한이 없습니다.");
        }

        // 확정 및 진행 중인 상태들
        List<RentalReservationStatus> confirmedStatuses = List.of(
                RentalReservationStatus.CONFIRMED,
                RentalReservationStatus.BEFORE_PHOTO_ANALYZED,
                RentalReservationStatus.REMITTANCE_REQUESTED,
                RentalReservationStatus.REMITTANCE_COMPLETED,
                RentalReservationStatus.RENTAL_PERIOD_ENDED,
                RentalReservationStatus.BEFORE_AND_AFTER_COMPARED,
                RentalReservationStatus.DEPOSIT_REQUESTED,
                RentalReservationStatus.RENTAL_COMPLETED
        );

        List<RentalReservation> confirmedReservations =
                rentalReservationRepository.findByPostIdAndOwnerIdAndStatusIn(
                        postId,
                        ownerId,
                        confirmedStatuses
                );

        return confirmedReservations.stream()
                .map(reservation -> {
                    ReservationMemberQueryDto renter = reservationMemberQueryPort
                            .findById(reservation.getRenterId())
                            .orElseThrow();
                    return ReservationListResponseDto.from(reservation, post, renter);
                })
                .collect(Collectors.toList());
    }

    /**
     * 신규: 신청자의 예약 목록 조회
     */
    @Transactional(readOnly = true)
    public List<ReservationListResponseDto> getReservationsByRenter(
            Long renterId,
            List<RentalReservationStatus> statuses) {

        // 상태가 없으면 모든 활성 상태 조회
        if (statuses == null || statuses.isEmpty()) {
            statuses = List.of(
                    RentalReservationStatus.PENDING,
                    RentalReservationStatus.CONFIRMED,
                    RentalReservationStatus.REMITTANCE_REQUESTED,
                    RentalReservationStatus.REMITTANCE_COMPLETED,
                    RentalReservationStatus.RENTAL_PERIOD_ENDED,
                    RentalReservationStatus.REJECTED  // 거절된 것도 보여줌
            );
        }

        List<RentalReservation> reservations =
                rentalReservationRepository.findByRenterIdAndStatusIn(renterId, statuses);

        return reservations.stream()
                .map(reservation -> {
                    PostDto post = rentalReservationPostQueryPort
                            .findById(reservation.getPostId())
                            .orElseThrow();
                    ReservationMemberQueryDto renter = reservationMemberQueryPort
                            .findById(renterId)
                            .orElseThrow();
                    return ReservationListResponseDto.from(reservation, post, renter);
                })
                .collect(Collectors.toList());
    }

    // ==================== Private 헬퍼 메서드 ====================

    /**
     * 확정 알림 전송
     */
    private void sendConfirmationNotification(RentalReservation reservation) {
        try {
            String renterUuid = memberUuidQueryPort.getMemberUuidByRentalIdAndRole(
                    reservation.getId(),
                    "RENTER"
            );

            RentalStatusMessage.RentalDetailResult rentalDetail =
                    rentalDetailQueryPort.getRentalDetailByRentalIdAndRole(
                            reservation.getId()
                    );

            messagingTemplate.convertAndSend(
                    "/topic/rental-reservation/" + renterUuid + "/confirmed",
                    RequestRemittanceStatusMessage.builder()
                            .rentalId(reservation.getId())
                            .process(RentalReservationProcess.BEFORE_RENTAL.name())
                            .detailStatus(RentalReservationStatus.CONFIRMED.name())
                            .rentalDetail(rentalDetail)
                            .build()
            );

            log.info("예약 확정 알림 전송 완료 - reservationId: {}, renterUuid: {}",
                    reservation.getId(), renterUuid);

        } catch (Exception e) {
            log.error("확정 알림 전송 실패 - reservationId: {}", reservation.getId(), e);
            // 알림 실패는 전체 트랜잭션을 롤백하지 않음
        }
    }

    /**
     * 거절 알림 전송 (다중)
     */
    private void sendRejectionNotifications(List<RentalReservation> rejectedReservations) {
        for (RentalReservation rejected : rejectedReservations) {
            try {
                String renterUuid = memberUuidQueryPort.getMemberUuidByRentalIdAndRole(
                        rejected.getId(),
                        "RENTER"
                );

                messagingTemplate.convertAndSend(
                        "/topic/rental-reservation/" + renterUuid + "/rejected",
                        RentalStatusMessage.builder()
                                .process(RentalReservationProcess.BEFORE_RENTAL.name())
                                .detailStatus(RentalReservationStatus.REJECTED.name())
                                .rentalDetail(null)
                                .build()
                );

                log.info("예약 거절 알림 전송 완료 - reservationId: {}, renterUuid: {}",
                        rejected.getId(), renterUuid);

            } catch (Exception e) {
                log.error("거절 알림 전송 실패 - reservationId: {}", rejected.getId(), e);
                // 계속 진행
            }
        }
    }

    /**
     * 작성자에게 확정 완료 알림
     */
    private void notifyOwnerReservationConfirmed(RentalReservation reservation) {
        try {
            String ownerUuid = memberUuidQueryPort.getMemberUuidByRentalIdAndRole(
                    reservation.getId(),
                    "OWNER"
            );

            RentalStatusMessage.RentalDetailResult rentalDetail =
                    rentalDetailQueryPort.getRentalDetailByRentalIdAndRole(
                            reservation.getId()
                    );

            messagingTemplate.convertAndSend(
                    "/topic/rental-reservation/" + ownerUuid + "/status",
                    RequestRemittanceStatusMessage.builder()
                            .rentalId(reservation.getId())
                            .process(RentalReservationProcess.BEFORE_RENTAL.name())
                            .detailStatus(RentalReservationStatus.CONFIRMED.name())
                            .rentalDetail(rentalDetail)
                            .build()
            );

            messagingTemplate.convertAndSend(
                    "/topic/rental-reservation/" + reservation.getId() + "/status",
                    RentalStatusMessage.builder()
                            .process(RentalReservationProcess.BEFORE_RENTAL.name())
                            .detailStatus(RentalReservationStatus.CONFIRMED.name())
                            .rentalDetail(rentalDetail)
                            .build()
            );

        } catch (Exception e) {
            log.error("작성자 알림 전송 실패 - reservationId: {}", reservation.getId(), e);
        }
    }

    private void validateNotConfirmed(RentalReservation rentalReservation) {
        if (rentalReservation.getRentalReservationStatus() == RentalReservationStatus.CONFIRMED) {
            throw new AlreadyConfirmedException("이미 확정된 예약입니다.");
        }
    }

    private void validateOwner(Long loginUserId, RentalReservation rentalReservation) {
        if (!rentalReservation.getOwnerId().equals(loginUserId)) {
            throw new UnauthorizedException("권한이 없습니다.");
        }
    }

    private void validateOwnerOrRenter(Long loginUserId, RentalReservation rentalReservation) {
        if (!rentalReservation.getOwnerId().equals(loginUserId) &&
                !rentalReservation.getRenterId().equals(loginUserId)) {
            throw new UnauthorizedException("권한이 없습니다.");
        }
    }
}