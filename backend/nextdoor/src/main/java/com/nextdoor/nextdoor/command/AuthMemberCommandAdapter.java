package com.nextdoor.nextdoor.command;

import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.auth.port.AuthMemberCommandPort;
import com.nextdoor.nextdoor.domain.auth.dto.MemberCommandDto;
import com.nextdoor.nextdoor.domain.auth.dto.MemberQueryDto;
import com.nextdoor.nextdoor.domain.member.domain.model.Member;
import com.nextdoor.nextdoor.domain.member.domain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Adapter
public class AuthMemberCommandAdapter implements AuthMemberCommandPort {

    private final MemberRepository memberRepository;

    @Override
    public MemberQueryDto save(MemberCommandDto command) {
        Member member = memberRepository.save(Member.builder()
                .authProvider(command.getAuthProvider())
                .nickname(command.getNickname())
                .providerId(command.getProviderId())
                .profileImageUrl(command.getProfileImageUrl())
                .build());
        return new MemberQueryDto(member.getId(), member.getUuid(), member.getProviderId());
    }
}
