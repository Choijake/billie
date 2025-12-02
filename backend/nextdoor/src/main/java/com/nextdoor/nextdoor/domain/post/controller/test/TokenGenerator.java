package com.nextdoor.nextdoor.domain.post.controller.test;

import com.nextdoor.nextdoor.domain.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TokenGenerator {

    private final JwtProvider jwtProvider;

    @GetMapping("/generate-tokens")
    public ResponseEntity<TokenResponse> generateTokens() {
        List<String> tokens = new ArrayList<>(7125);

        for (long i = 1; i <= 7125; i++) {
            String userId = String.valueOf(i);
            String uuid = UUID.randomUUID().toString();
            String token = jwtProvider.createDummyToken(userId, uuid);
            tokens.add(token);
        }

        return ResponseEntity.ok(TokenResponse.of(tokens));
    }
}
