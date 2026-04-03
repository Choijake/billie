package com.nextdoor.nextdoor.domain.search.benchmark;

import com.nextdoor.nextdoor.config.ElasticsearchConfig;
import com.nextdoor.nextdoor.config.RedisConfig;
import com.nextdoor.nextdoor.domain.search.outbox.SqsPublisher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 벤치마크 전용 Spring Boot 설정.
 * search 패키지만 스캔하고, 필요한 config 클래스만 Import한다.
 * RedisCoalescer 대신 PassThrough 구현을 주입하여 Coalescer를 비활성화한다.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.nextdoor.nextdoor.domain.search"
        },
        exclude = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                OAuth2ClientAutoConfiguration.class,
                MongoAutoConfiguration.class,
                MongoDataAutoConfiguration.class,
                RabbitAutoConfiguration.class,
                WebSocketServletAutoConfiguration.class,
                QuartzAutoConfiguration.class
        }
)
@EnableScheduling
@Import({ElasticsearchConfig.class, RedisConfig.class})
@EnableJpaRepositories(basePackages = {
        "com.nextdoor.nextdoor.domain.search",
        "com.nextdoor.nextdoor.domain.post"
})
@EntityScan(basePackages = {
        "com.nextdoor.nextdoor.domain.search",
        "com.nextdoor.nextdoor.domain.post"
})
public class BenchmarkTestConfig {

}
