package com.nextdoor.nextdoor.domain.rentalreservation.domain.model;

import com.nextdoor.nextdoor.domain.rentalreservation.domain.exception.InvalidRentalStatusException;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.util.ValidationUtils;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "rental_reservation",
        indexes = {
                // 대기 중인 예약 조회 최적화
                @Index(name = "idx_post_status_created", columnList = "post_id, rental_status, created_at"),
                // 신청자별 조회 최적화
                @Index(name = "idx_renter_status_created", columnList = "renter_id, rental_status, created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rental_reservation_id")
    private Long id;

    @OneToMany(mappedBy = "rental", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AiImage> aiImages = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "rental_status", nullable = false, length = 50)
    private RentalReservationStatus rentalReservationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "rental_process", nullable = false, length = 50)
    private RentalReservationProcess rentalReservationProcess;

    @Lob
    @Column(name = "damage_analysis", columnDefinition = "TEXT")
    private String damageAnalysis;

    @Lob
    @Column(name = "compared_analysis", columnDefinition = "TEXT")
    private String comparedAnalysis;

    @Embedded
    private AccountInfo accountInfo;

    @Column(name = "deposit_id")
    private Long depositId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "final_amount"))
    })
    private Money finalAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Embedded
    private Period period;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "rentalFee"))
    })
    private Money rentalFee;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "deposit"))
    })
    private Money deposit;

    @NotNull
    @Column(name = "owner_id")
    private Long ownerId;

    @NotNull
    @Column(name = "renter_id")
    private Long renterId;

    @NotNull
    @Column(name = "post_id")
    private Long postId;

    public static RentalReservation createHold(
            LocalDate startDate,
            LocalDate endDate,
            Money rentalFee,
            Money deposit,
            Long ownerId,
            Long renterId,
            Long postId) {

        Period period = new Period(startDate, endDate);

        return RentalReservation.builder()
                .period(period)
                .rentalFee(rentalFee)
                .deposit(deposit)
                .ownerId(ownerId)
                .renterId(renterId)
                .postId(postId)
                .rentalReservationStatus(RentalReservationStatus.PENDING)
                .rentalReservationProcess(getRentalProcessForStatus(RentalReservationStatus.PENDING))
                .createdAt(LocalDateTime.now())
                .aiImages(new ArrayList<>())
                .accountInfo(null)
                .damageAnalysis(null)
                .comparedAnalysis(null)
                .depositId(null)
                .finalAmount(null)
                .build();
    }

    public static RentalReservation createPending(
            LocalDate startDate,
            LocalDate endDate,
            Money rentalFee,
            Money deposit,
            Long ownerId,
            Long renterId,
            Long postId) {

        Period period = new Period(startDate, endDate);

        return RentalReservation.builder()
                .period(period)
                .rentalFee(rentalFee)
                .deposit(deposit)
                .ownerId(ownerId)
                .renterId(renterId)
                .postId(postId)
                .rentalReservationStatus(RentalReservationStatus.PENDING)
                .rentalReservationProcess(getRentalProcessForStatus(RentalReservationStatus.PENDING))
                .createdAt(LocalDateTime.now())
                .aiImages(new ArrayList<>())
                .accountInfo(null)
                .damageAnalysis(null)
                .comparedAnalysis(null)
                .depositId(null)
                .finalAmount(null)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private RentalReservation(List<AiImage> aiImages, RentalReservationStatus rentalReservationStatus, RentalReservationProcess rentalReservationProcess,
                              String damageAnalysis, String comparedAnalysis, LocalDateTime createdAt, AccountInfo accountInfo, Long depositId,
                              Period period, Money rentalFee, Money deposit, Money finalAmount,
                              Long ownerId, Long renterId, Long postId) {
        this.aiImages = aiImages;
        this.rentalReservationStatus = rentalReservationStatus;
        this.rentalReservationProcess = rentalReservationProcess;
        this.damageAnalysis = damageAnalysis;
        this.comparedAnalysis = comparedAnalysis;
        this.createdAt = createdAt;
        this.accountInfo = accountInfo;
        this.depositId = depositId;
        this.period = period;
        this.rentalFee = rentalFee;
        this.deposit = deposit;
        this.finalAmount = finalAmount;
        this.ownerId = ownerId;
        this.renterId = renterId;
        this.postId = postId;
    }

    public void changeStartDate(LocalDate startDate) {
        this.period = new Period(startDate, this.period.getEndDate());
    }

    public void changeEndDate(LocalDate endDate) {
        this.period = new Period(this.period.getStartDate(), endDate);
    }

    public void changeRentalFee(Money rentalFee) {
        this.rentalFee = rentalFee;
    }

    public void changeDeposit(Money deposit) {
        this.deposit = deposit;
    }

    public void modifyReservationDetails(LocalDate newStartDate, LocalDate newEndDate, Money newRentalFee, Money newDeposit) {
        if (this.rentalReservationStatus != RentalReservationStatus.PENDING){
            throw new InvalidRentalStatusException("현재 대여 상태(" + this.rentalReservationStatus + ")에서는 예약 정보를 수정할 수 없습니다.");
        }

        changeStartDate(newStartDate);
        changeEndDate(newEndDate);
        changeRentalFee(newRentalFee);
        changeDeposit(newDeposit);
        changeStatus(RentalReservationStatus.PENDING);
    }

    public void requestRemittance() {
        changeStatus(RentalReservationStatus.REMITTANCE_REQUESTED);
    }

    public void completeRemittance() {
        validateRemittanceCompletionStatus();
        changeStatus(RentalReservationStatus.REMITTANCE_COMPLETED);
    }

    private void validateRemittanceCompletionStatus() {
        if(rentalReservationStatus != RentalReservationStatus.BEFORE_PHOTO_ANALYZED){
            throw new InvalidRentalStatusException("결제 완료 처리가 불가능한 대여 상태입니다");
        }
    }

    public void endRentalPeriod() {
        validateRentalPeriodEndStatus();
        changeStatus(RentalReservationStatus.RENTAL_PERIOD_ENDED);
    }

    private void validateRentalPeriodEndStatus() {
        if(rentalReservationStatus != RentalReservationStatus.REMITTANCE_COMPLETED){
            throw new InvalidRentalStatusException("대여 기간 종료가 불가능한 대여 상태입니다");
        }
    }

    public void recordDamageAnalysis(String damageAnalysis) {
        this.damageAnalysis = damageAnalysis;
        changeStatus(RentalReservationStatus.BEFORE_PHOTO_ANALYZED);
    }

    public void recordComparedAnalysis(String comparedAnalysis) {
        this.comparedAnalysis = comparedAnalysis;
    }

    public void updateAccountInfo(String accountNo, String bankCode) {
        ValidationUtils.validateNotBlank(accountNo, "accountNo");
        ValidationUtils.validateNotBlank(bankCode, "bankCode");
        this.accountInfo = new AccountInfo(accountNo, bankCode);

        changeStatus(RentalReservationStatus.REMITTANCE_REQUESTED);
    }

    public void completeDeposit(){
        validateDepositCompletionStatus();
        changeStatus(RentalReservationStatus.RENTAL_COMPLETED);
    }

    private void validateDepositCompletionStatus() {
        if (rentalReservationStatus != RentalReservationStatus.BEFORE_AND_AFTER_COMPARED) {
            throw new InvalidRentalStatusException(this.rentalReservationStatus.name() + ": 보증금을 처리가 불가능한 대여 상태입니다");
        }
    }

    public void assignDepositId(Long depositId) {
        this.depositId = depositId;
    }

    public void changeFinalAmount(Money amount) {
        this.finalAmount = amount;
    }

    public void addAiImage(AiImageType imageType, String imageUrl, String mimeType) {
        AiImage newImage = AiImage.create(this, imageType, imageUrl, mimeType);
        AiImages imagesWrapper = new AiImages(this.aiImages);
        imagesWrapper.addImage(newImage);
        this.aiImages.add(newImage);
    }

    public void changeStatus(RentalReservationStatus rentalReservationStatus){
        this.rentalReservationStatus = rentalReservationStatus;
        this.rentalReservationProcess = getRentalProcessForStatus(rentalReservationStatus);
    }

    private static RentalReservationProcess getRentalProcessForStatus(RentalReservationStatus status) {
        if (status == null) {
            return RentalReservationProcess.BEFORE_RENTAL;
        }

        switch (status) {
            case PENDING:
            case CONFIRMED:
            case BEFORE_PHOTO_ANALYZED:
            case REMITTANCE_REQUESTED:
            case CANCELLED:
                return RentalReservationProcess.BEFORE_RENTAL;
            case REMITTANCE_COMPLETED:
                return RentalReservationProcess.RENTAL_IN_ACTIVE;
            case RENTAL_PERIOD_ENDED:
            case BEFORE_AND_AFTER_COMPARED:
            case DEPOSIT_REQUESTED:
                return RentalReservationProcess.RETURNED;
            case RENTAL_COMPLETED:
                return RentalReservationProcess.RENTAL_COMPLETED;
            case REJECTED:
                return RentalReservationProcess.BEFORE_RENTAL;
            default:
                return RentalReservationProcess.BEFORE_RENTAL;
        }
    }
}