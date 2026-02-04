package com.nextdoor.nextdoor.domain.rentalreservation.application.service;

import com.nextdoor.nextdoor.domain.member.domain.model.Member;
import com.nextdoor.nextdoor.domain.member.infrastructure.persistence.jpa.MemberJpaRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.PostDto;
import com.nextdoor.nextdoor.domain.rentalreservation.application.dto.ReservationMemberQueryDto;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.MemberUuidQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.RentalDetailQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.RentalReservationPostQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.application.port.ReservationMemberQueryPort;
import com.nextdoor.nextdoor.domain.rentalreservation.domain.model.RentalReservation;
import com.nextdoor.nextdoor.domain.rentalreservation.infrastructure.persistence.jpa.RentalReservationJpaRepository;
import com.nextdoor.nextdoor.domain.rentalreservation.presentation.dto.request.ReservationSaveRequestDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RentalReservationJpaRepository rentalReservationJpaRepository;

    @Autowired
    private MemberJpaRepository memberJpaRepository;

    @Autowired
    private PostRepository postRepository;

    // [삭제] AccountRepository accountRepository; (이제 불필요)

    @MockitoBean
    private RentalReservationPostQueryPort rentalReservationPostQueryPort;
    @MockitoBean
    private ReservationMemberQueryPort reservationMemberQueryPort;
    @MockitoBean
    private MemberUuidQueryPort memberUuidQueryPort;
    @MockitoBean
    private RentalDetailQueryPort rentalDetailQueryPort;
    @MockitoBean
    private SimpMessagingTemplate simpMessagingTemplate;

    @AfterEach
    void tearDown() {
        rentalReservationJpaRepository.deleteAll();
        // accountRepository.deleteAll(); // 삭제
        postRepository.deleteAll();
        memberJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 100명이 같은 날짜에 예약을 시도하면 1명만 성공해야 한다.")
    void createHoldReservation_concurrency() throws InterruptedException {
        // Given
        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 1. [Owner] 저장
        Member owner = memberJpaRepository.save(Member.builder()
                .nickname("owner")
                .providerId("kakao_owner")
                .build());

        // 2. [Post] 저장
        Post post = postRepository.save(Post.builder()
                .title("Concurrency Test Post")
                .content("Content")
                .authorId(owner.getId())
                .category(Category.DIGITAL_DEVICE)
                .rentalFee(10000L)
                .deposit(5000L)
                .build());
        Long realPostId = post.getId();

        // 3. [Renters] 100명 저장 (계좌 저장 로직 삭제됨)
        List<Long> renterIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Member renter = memberJpaRepository.save(Member.builder()
                    .nickname("renter_" + i)
                    .providerId("kakao_" + i)
                    .build());
            renterIds.add(renter.getId());
        }

        // 4. [Mock] 설정
        PostDto mockPostDto = PostDto.builder()
                .postId(realPostId)
                .authorId(owner.getId())
                .authorUuid(owner.getUuid())
                .rentalFee(BigDecimal.valueOf(10000))
                .deposit(BigDecimal.valueOf(5000))
                .build();

        ReservationMemberQueryDto mockMemberDto = new ReservationMemberQueryDto(
                1L, "renter", "profile_url"
        );

        given(rentalReservationPostQueryPort.findById(realPostId)).willReturn(Optional.of(mockPostDto));
        given(rentalReservationPostQueryPort.findByIdWithLock(realPostId)).willReturn(Optional.of(mockPostDto));
        given(reservationMemberQueryPort.findById(any())).willReturn(Optional.of(mockMemberDto));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        LocalDate startDate = LocalDate.of(2025, 12, 20);
        LocalDate endDate = LocalDate.of(2025, 12, 21);

        for (int i = 0; i < threadCount; i++) {
            Long renterId = renterIds.get(i);

            executorService.submit(() -> {
                try {
                    ReservationSaveRequestDto requestDto = new ReservationSaveRequestDto(
                            realPostId,
                            startDate,
                            endDate
                    );

                    reservationService.createHoldReservation(renterId, requestDto);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    // 에러 발생 시 로그 출력 (디버깅용)
                    // if (failCount.get() == 1) e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        List<RentalReservation> reservations = rentalReservationJpaRepository.findAll();

        System.out.println("=== 테스트 결과 ===");
        System.out.println("성공한 요청 수: " + successCount.get());
        System.out.println("실패한 요청 수: " + failCount.get());
        System.out.println("DB 저장된 데이터 수: " + reservations.size());

        assertThat(reservations.size()).isEqualTo(1);
        assertThat(successCount.get()).isEqualTo(1);
    }
}