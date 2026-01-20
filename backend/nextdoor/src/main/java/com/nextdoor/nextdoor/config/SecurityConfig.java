package com.nextdoor.nextdoor.config;

import com.nextdoor.nextdoor.domain.auth.oauth2.OAuth2AuthorizationRequestBasedOnCookieRepository;
import com.nextdoor.nextdoor.domain.auth.oauth2.OAuth2SuccessHandler;
import com.nextdoor.nextdoor.domain.auth.filter.JwtAuthenticationFilter;
import com.nextdoor.nextdoor.domain.auth.filter.RedirectUrlCookieFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.CorsFilter;

@RequiredArgsConstructor
@Configuration
@EnableWebSecurity
@Profile("dev")
public class SecurityConfig {

    private final OAuth2AuthorizationRequestBasedOnCookieRepository oAuth2AuthorizationRequestBasedOnCookieRepository;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RedirectUrlCookieFilter redirectUrlCookieFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(httpRequest -> httpRequest
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/generate-tokens").permitAll()
                        .requestMatchers("/index-all").permitAll()
                        .requestMatchers("/api/v1/auth").permitAll()
                        .requestMatchers("/api/v1/posts/**").permitAll()
                        .requestMatchers("/api/reservations/**").permitAll()
                        .anyRequest().authenticated())

                .oauth2Login(oauth2 -> oauth2
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/api/v1/auth/oauth2/code/*"))
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/api/v1/auth/oauth2/authorization")
                                .authorizationRequestRepository(oAuth2AuthorizationRequestBasedOnCookieRepository))
                        .successHandler(oAuth2SuccessHandler))
                .oauth2Client(Customizer.withDefaults())
                .addFilterAfter(jwtAuthenticationFilter, CorsFilter.class)
                .addFilterBefore(redirectUrlCookieFilter, OAuth2AuthorizationRequestRedirectFilter.class)
                .build();
    }
}