package com.nextdoor.nextdoor.domain.post.controller.test;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    private List<String> tokens;

    public static TokenResponse of(List<String> tokens) {
        return new TokenResponse(tokens);
    }
}
