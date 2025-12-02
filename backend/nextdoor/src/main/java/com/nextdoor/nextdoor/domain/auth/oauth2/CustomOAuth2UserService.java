package com.nextdoor.nextdoor.domain.auth.oauth2;

import com.nextdoor.nextdoor.domain.auth.model.CustomOAuth2User;
import com.nextdoor.nextdoor.domain.auth.exception.UnsupportedOAuth2ProviderException;
import com.nextdoor.nextdoor.domain.auth.port.AuthFintechCommandPort;
import com.nextdoor.nextdoor.domain.auth.port.AuthMemberCommandPort;
import com.nextdoor.nextdoor.domain.auth.port.AuthMemberQueryPort;
import com.nextdoor.nextdoor.domain.auth.dto.MemberCommandDto;
import com.nextdoor.nextdoor.domain.auth.dto.MemberQueryDto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@RequiredArgsConstructor
@Slf4j
@Service
@Transactional
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AuthMemberCommandPort authMemberCommandPort;
    private final AuthMemberQueryPort authMemberQueryPort;
    private final AuthFintechCommandPort authFintechCommandPort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String authProvider = userRequest.getClientRegistration().getClientName();
        String id, nickname, profileImageUrl;
        switch (authProvider) {
            case "Kakao":
                id = oAuth2User.getAttributes().get("id").toString();
                Map<String, Object> kakaoAccount = (Map<String, Object>) oAuth2User.getAttributes().get("kakao_account");
                Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
                nickname = (String) profile.get("nickname");
                profileImageUrl = (String) profile.get("profile_image_url");
                break;
            case "Naver":
                Map<String, Object> response = (Map<String, Object>) oAuth2User.getAttributes().get("response");
                id = response.get("id").toString();
                nickname = (String) response.get("name");
                profileImageUrl = (String) response.get("profile_image");
                break;
            default:
                throw new UnsupportedOAuth2ProviderException("지원하지 않는 OAuth2 제공자입니다.");
        }
        MemberQueryDto member = authMemberQueryPort.findByIdAndAuthProvider(id, authProvider).orElseGet(() -> {
            // 새 멤버 저장
            MemberQueryDto newMember = authMemberCommandPort.save(new MemberCommandDto(authProvider, nickname, id, profileImageUrl));

            // 이벤트 발행
            long randomNumber = (long) (Math.random() * Long.MAX_VALUE);
            eventPublisher.publishEvent(new MemberCreatedEvent(newMember.getId(),
                    randomNumber + "_" +  newMember.getProviderId() + "@example.com"));

            return newMember;
        });
        return new CustomOAuth2User(member.getId().toString(), member.getUuid(), oAuth2User.getAttributes());
    }

    @EventListener
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleMemberCreatedEvent(MemberCreatedEvent event) {
        try {
            authFintechCommandPort.createUser(event.getMemberId(), event.getEmail()).block();
            log.info("Fintech 사용자 생성 성공: memberId={}", event.getMemberId());
        } catch (Exception e) {
            log.error("Fintech 사용자 생성 실패: memberId={}, 오류={}", event.getMemberId(), e.getMessage(), e);
        }
    }

    // 이벤트 클래스 정의
    @Getter
    @AllArgsConstructor
    public static class MemberCreatedEvent {
        private final Long memberId;
        private final String email;
    }
}
