package com.nextdoor.nextdoor.domain.post.controller.test;

import com.nextdoor.nextdoor.domain.post.search.messaging.producer.PostIndexingProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@Profile("api")
@RequiredArgsConstructor
public class IndexController {

    private final PostIndexingProducer postIndexingProducer;

    @PostMapping("/index-all")
    public ResponseEntity<String> triggerReindexAll() {
        postIndexingProducer.requestReindexAll();
        return ResponseEntity.ok("전체 인덱싱 요청을 대기 큐로 전송했습니다.");
    }
}