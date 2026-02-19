package com.nextdoor.nextdoor.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PerformanceMonitoringAspect {

    private final MeterRegistry meterRegistry;

    @Pointcut("execution(* com.nextdoor.nextdoor..*Controller.*(..)) || " +
            "execution(* com.nextdoor.nextdoor..*Service.*(..)) || " +
            "execution(* com.nextdoor.nextdoor..*Repository.*(..))")
    public void targetMethods() {}

    @Around("targetMethods()")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        String layer = detectLayer(className);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(meterRegistry.timer("api.execution.time",
                    "layer", layer,
                    "class", className,
                    "method", methodName));
        }
    }

    private String detectLayer(String className) {
        if (className.endsWith("Controller")) {
            return "WEB";
        }
        if (className.endsWith("Repository") || className.endsWith("Adapter")) {
            return "DB";
        }
        if (className.equals("ActionService") || className.contains("Redis")) {
            return "REDIS";
        }
        return "SERVICE";
    }
}