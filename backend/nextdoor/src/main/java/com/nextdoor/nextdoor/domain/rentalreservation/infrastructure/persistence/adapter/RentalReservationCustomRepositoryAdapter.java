package com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.adapter;

import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.AiComparisonResult;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.*;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.custom.RentalReservationCustomRepository;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RentalReservationCustomRepositoryAdapter implements RentalReservationCustomRepository {

    private final JPAQueryFactory queryFactory;
    private final QRentalReservation rentalReservation = QRentalReservation.rentalReservation;
    private final QAiImage aiImage = QAiImage.aiImage;
    private final QAiImage beforeAiImage = new QAiImage("beforeAiImage");
    private final QAiImage afterAiImage = new QAiImage("afterAiImage");
    private final QAiImageComparisonPair aiImageComparisonPair = QAiImageComparisonPair.aiImageComparisonPair;

    @Override
    public boolean existsOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        Integer hit = queryFactory
                .selectOne()
                .from(r)
                .where(
                        r.postId.eq(postId),
                        r.rentalReservationStatus.in(RentalReservationStatus.PENDING, RentalReservationStatus.CONFIRMED),
                        r.period.startDate.loe(reqEnd),
                        r.period.endDate.goe(reqStart)
                )
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchFirst();

        return hit != null;
    }

    /**
     * 확정된 예약만 체크 (확정 이후 상태만)
     */
    @Override
    public boolean existsConfirmedOverlap(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        Integer hit = queryFactory
                .selectOne()
                .from(r)
                .where(
                        r.postId.eq(postId),
                        r.rentalReservationStatus.in(
                                RentalReservationStatus.CONFIRMED,
                                RentalReservationStatus.BEFORE_PHOTO_ANALYZED,
                                RentalReservationStatus.REMITTANCE_REQUESTED,
                                RentalReservationStatus.REMITTANCE_COMPLETED,
                                RentalReservationStatus.RENTAL_PERIOD_ENDED,
                                RentalReservationStatus.BEFORE_AND_AFTER_COMPARED,
                                RentalReservationStatus.DEPOSIT_REQUESTED,
                                RentalReservationStatus.RENTAL_COMPLETED
                        ),
                        r.period.startDate.loe(reqEnd),
                        r.period.endDate.goe(reqStart)
                )
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchFirst();

        return hit != null;
    }

    /**
     * 같은 사용자가 같은 날짜에 이미 신청했는지
     */
    @Override
    public boolean existsPendingByRenterAndDateRange(Long postId, Long renterId, LocalDate reqStart, LocalDate reqEnd) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        Integer hit = queryFactory
                .selectOne()
                .from(r)
                .where(
                        r.postId.eq(postId),
                        r.renterId.eq(renterId),
                        r.rentalReservationStatus.eq(RentalReservationStatus.PENDING),
                        r.period.startDate.loe(reqEnd),
                        r.period.endDate.goe(reqStart)
                )
                .fetchFirst();

        return hit != null;
    }

    /**
     * 특정 날짜 범위의 대기 중인 예약 목록 (선착순)
     */
    @Override
    public List<RentalReservation> findPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        return queryFactory
                .selectFrom(r)
                .where(
                        r.postId.eq(postId),
                        r.rentalReservationStatus.eq(RentalReservationStatus.PENDING),
                        r.period.startDate.loe(reqEnd),
                        r.period.endDate.goe(reqStart)
                )
                .orderBy(r.createdAt.asc())
                .fetch();
    }

    /**
     * 작성자의 게시물별 예약 목록
     */
    @Override
    public List<RentalReservation> findByPostIdAndOwnerIdAndStatusIn(
            Long postId,
            Long ownerId,
            List<RentalReservationStatus> statuses) {

        QRentalReservation r = QRentalReservation.rentalReservation;

        return queryFactory
                .selectFrom(r)
                .where(
                        r.postId.eq(postId),
                        r.ownerId.eq(ownerId),
                        r.rentalReservationStatus.in(statuses)
                )
                .orderBy(r.createdAt.desc())
                .fetch();
    }

    /**
     * 신청자의 예약 목록
     */
    @Override
    public List<RentalReservation> findByRenterIdAndStatusIn(
            Long renterId,
            List<RentalReservationStatus> statuses) {

        QRentalReservation r = QRentalReservation.rentalReservation;

        return queryFactory
                .selectFrom(r)
                .where(
                        r.renterId.eq(renterId),
                        r.rentalReservationStatus.in(statuses)
                )
                .orderBy(r.createdAt.desc())
                .fetch();
    }

    /**
     * 날짜별 확정 현황 조회 (달력)
     */
    @Override
    public List<RentalReservation> findConfirmedByPostIdFromDate(Long postId, LocalDate fromDate) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        return queryFactory
                .selectFrom(r)
                .where(
                        r.postId.eq(postId),
                        r.rentalReservationStatus.in(
                                RentalReservationStatus.CONFIRMED,
                                RentalReservationStatus.BEFORE_PHOTO_ANALYZED,
                                RentalReservationStatus.REMITTANCE_REQUESTED,
                                RentalReservationStatus.REMITTANCE_COMPLETED,
                                RentalReservationStatus.RENTAL_PERIOD_ENDED,
                                RentalReservationStatus.BEFORE_AND_AFTER_COMPARED,
                                RentalReservationStatus.DEPOSIT_REQUESTED,
                                RentalReservationStatus.RENTAL_COMPLETED
                        ),
                        r.period.startDate.goe(fromDate)
                )
                .orderBy(r.period.startDate.asc())
                .fetch();
    }

    /**
     * 대기 중인 예약 수 조회
     */
    @Override
    public long countPendingByPostIdAndDateRange(Long postId, LocalDate reqStart, LocalDate reqEnd) {
        QRentalReservation r = QRentalReservation.rentalReservation;

        Long count = queryFactory
                .select(r.count())
                .from(r)
                .where(
                        r.postId.eq(postId),
                        r.rentalReservationStatus.eq(RentalReservationStatus.PENDING),
                        r.period.startDate.loe(reqEnd),
                        r.period.endDate.goe(reqStart)
                )
                .fetchOne();

        return count!=null?count:0L;
    }

    @Override
    public Optional<AiComparisonResult> findRentalWithImagesByRentalId(Long rentalId) {
        RentalReservation foundRental = queryFactory
                .selectFrom(rentalReservation)
                .leftJoin(rentalReservation.aiImages, aiImage).fetchJoin()
                .where(rentalReservation.id.eq(rentalId))
                .fetchOne();
        if (foundRental == null) {
            return Optional.empty();
        }

        List<AiComparisonResult.MatchingResult> matchingResults = queryFactory.select(Projections.constructor(
                        AiComparisonResult.MatchingResult.class,
                        beforeAiImage.imageUrl,
                        afterAiImage.imageUrl,
                        aiImageComparisonPair.pairComparisonResult))
                .from(aiImageComparisonPair)
                .leftJoin(beforeAiImage).on(aiImageComparisonPair.beforeImageId.eq(beforeAiImage.id)).fetchJoin()
                .leftJoin(afterAiImage).on(aiImageComparisonPair.afterImageId.eq(afterAiImage.id)).fetchJoin()
                .where(aiImageComparisonPair.rentalId.eq(rentalId))
                .fetch();

        AiComparisonResult result = getAiComparisonResult(foundRental, matchingResults);

        return Optional.of(result);
    }

    private static AiComparisonResult getAiComparisonResult(RentalReservation foundRental, List<AiComparisonResult.MatchingResult> matchingResults) {
        List<String> beforeImages = new ArrayList<>();
        List<String> afterImages = new ArrayList<>();

        for (AiImage image : foundRental.getAiImages()) {
            if (AiImageType.BEFORE.equals(image.getType())) {
                beforeImages.add(image.getImageUrl());
            } else if (AiImageType.AFTER.equals(image.getType())) {
                afterImages.add(image.getImageUrl());
            }
        }

        return new AiComparisonResult(
                beforeImages,
                afterImages,
                foundRental.getDamageAnalysis(),
                foundRental.getComparedAnalysis(),
                matchingResults
        );
    }
}