package com.nextdoor.nextdoor.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class MemberCommandDto {

    private String authProvider;
    private String nickname;
    private String providerId;
    private String profileImageUrl;
}
