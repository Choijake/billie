package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BestEffortExecutor {

    public void run(String op, Runnable runnable) {
        try {
            runnable.run();
        } catch (InfraException e) {
            log.warn("[BestEffort Infra Ignored] op={}, err={}", op, e.getMessage());
        } catch (Exception e) {
            log.warn("[BestEffort Unexpected Ignored] op={}, err={}", op, e.toString());
        }
    }
}
