package com.nextdoor.nextdoor.common;

import com.nextdoor.nextdoor.domain.feed.application.service.FeedService;
import com.nextdoor.nextdoor.domain.member.domain.model.Gender;
import com.nextdoor.nextdoor.domain.member.domain.model.Member;
import com.nextdoor.nextdoor.domain.member.domain.repository.MemberRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@RequiredArgsConstructor
@Profile("dev")
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final PostRepository postRepository;
    private final FeedService feedService;

    private static final int MEMBER_TARGET_COUNT = 10000;
    private static final int POST_TARGET_COUNT = 1000000;
    private static final int BATCH_SIZE = 1000;

    private static final double[][] HOTSPOTS = {
            {37.4979, 127.0276}, {37.5563, 126.9225}, {37.5219, 126.9241},
            {37.5133, 127.1001}, {37.5704, 126.9921}, {37.3947, 127.1111},
            {37.2636, 127.0286}, {35.1578, 129.0600}, {35.1587, 129.1604},
            {35.8714, 128.6014}
    };

    @Override
    public void run(String... args) throws Exception {
        long currentCount = 0;
        if (memberRepository instanceof JpaRepository) {
            currentCount = ((JpaRepository<?, ?>) memberRepository).count();
        } else {
            currentCount = memberRepository.count();
        }

        if (currentCount > 5000) {
            log.info("ℹ️ 데이터가 이미 충분합니다 (현재 회원 수: {}명 이상). 초기화를 건너뜁니다.", currentCount);
            return;
        }

        log.info("🚀 초대용량 데이터 생성을 시작합니다... (목표: 게시물 {}개)", POST_TARGET_COUNT);
        long startTime = System.currentTimeMillis();

        List<Member> members = createMembersInBatch();

        createPostsInBatch(members);

        long endTime = System.currentTimeMillis();
        log.info("✅ 데이터 생성 완료! 소요 시간: {}초", (endTime - startTime) / 1000);
    }

    private List<Member> createMembersInBatch() {
        log.info("... 회원 {}명 생성 중", MEMBER_TARGET_COUNT);
        List<Member> allMembers = new ArrayList<>();
        List<Member> batch = new ArrayList<>();
        Random random = new Random();

        Member admin = Member.builder()
                .nickname("테스트유저")
                .providerId("KAKAO_123456789")
                .authProvider("KAKAO")
                .userKey(UUID.randomUUID().toString())
                .securePassword("123456")
                .address("서울시 강남구 테헤란로 123")
                .birth("19990101")
                .gender(Gender.MALE)
                .profileImageUrl("https://via.placeholder.com/150")
                .build();
        memberRepository.save(admin);
        allMembers.add(admin);

        for (int i = 2; i <= MEMBER_TARGET_COUNT; i++) {
            batch.add(Member.builder()
                    .nickname("유저" + i)
                    .providerId("KAKAO_DUMMY_" + i)
                    .authProvider("KAKAO")
                    .userKey(UUID.randomUUID().toString())
                    .securePassword("000000")
                    .address("서울시 어딘가 " + i + "번지")
                    .birth(generateRandomBirth(random))
                    .gender(random.nextBoolean() ? Gender.MALE : Gender.FEMALE)
                    .profileImageUrl("https://via.placeholder.com/150")
                    .build());

            if (batch.size() >= BATCH_SIZE) {
                saveMembers(batch, allMembers);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            saveMembers(batch, allMembers);
        }
        return allMembers;
    }

    private String generateRandomBirth(Random random) {
        int year = 1970 + random.nextInt(35);
        int month = 1 + random.nextInt(12);
        int day = 1 + random.nextInt(28);
        return String.format("%04d%02d%02d", year, month, day);
    }

    @SuppressWarnings("unchecked")
    private void saveMembers(List<Member> batch, List<Member> allMembers) {
        if (memberRepository instanceof JpaRepository) {
            allMembers.addAll(((JpaRepository<Member, Long>) memberRepository).saveAll(batch));
        } else {
            for (Member m : batch) {
                allMembers.add(memberRepository.save(m));
            }
        }
    }

    private void createPostsInBatch(List<Member> members) {
        log.info("... 게시물 {}개 생성 중 (DB 저장 + Redis Geo 인덱싱)", POST_TARGET_COUNT);
        Random random = new Random();
        Category[] categories = Category.values();
        List<Post> batch = new ArrayList<>();

        for (int i = 1; i <= POST_TARGET_COUNT; i++) {
            Member author = members.get(random.nextInt(members.size()));

            double[] baseLocation = HOTSPOTS[random.nextInt(HOTSPOTS.length)];

            double lat = baseLocation[0] + (random.nextDouble() - 0.5) * 0.06;
            double lon = baseLocation[1] + (random.nextDouble() - 0.5) * 0.06;

            Post post = Post.builder()
                    .title("대용량 테스트 매물 " + i)
                    .content("성능 테스트를 위한 더미 데이터입니다. 데이터 번호: " + i)
                    .category(categories[random.nextInt(categories.length)])
                    .rentalFee(1000L * (random.nextInt(10) + 1))
                    .deposit(5000L)
                    .address("테스트 주소 " + i)
                    .authorId(author.getId())
                    .latitude(lat)
                    .longitude(lon)
                    .build();


            batch.add(post);

            if (batch.size() >= BATCH_SIZE) {
                List<Post> savedPosts = postRepository.saveAll(batch);

                for (Post saved : savedPosts) {
                    feedService.addGeoLocation(saved.getId(), saved.getLatitude(), saved.getLongitude());
                }

                if (i % 50000 == 0) {
                    log.info(" -> {}만 개 생성 및 인덱싱 완료...", i / 10000);
                }
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            List<Post> savedPosts = postRepository.saveAll(batch);
            for (Post saved : savedPosts) {
                feedService.addGeoLocation(saved.getId(), saved.getLatitude(), saved.getLongitude());
            }
        }
    }
}