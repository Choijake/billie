package com.nextdoor.nextdoor.query;

import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.ReservationQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.ReservationDto;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.QRentalReservation;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@Adapter
@RequiredArgsConstructor
public class RentalReservationQueryAdapter implements ReservationQueryPort {

    private final JPAQueryFactory queryFactory;
    private final QRentalReservation rental = QRentalReservation.rentalReservation;

    @Override
    public Optional<ReservationDto> getReservationByRentalId(Long rentalId) {
        return Optional.ofNullable(
                queryFactory
                        .select(Projections.constructor(
                                ReservationDto.class,
                                rental.id.as("reservationId"),
                                rental.period.startDate,
                                rental.period.endDate,
                                rental.rentalFee,
                                rental.deposit,
                                rental.rentalReservationStatus.stringValue(),
                                rental.ownerId,
                                rental.renterId,
                                rental.postId
                        ))
                        .from(rental)
                        .where(rental.id.eq(rentalId))
                        .fetchOne()
        );
    }
}