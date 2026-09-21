package com.example.reader.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            AdminApiAuthenticationFilter adminApiAuthenticationFilter
    ) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(SecurityConfiguration::isNonBrowserAutomationRequest)
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .addFilterBefore(adminApiAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/tokens/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/admin/ingest/**").hasAnyAuthority(
                                "ROLE_ADMIN",
                                AdminApiAuthenticationFilter.SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_INGEST,
                                AdminApiAuthenticationFilter.SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_ALL
                        )
                        .requestMatchers("/api/admin/metrics/**").hasAnyAuthority(
                                "ROLE_ADMIN",
                                AdminApiAuthenticationFilter.SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_METRICS,
                                AdminApiAuthenticationFilter.SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_ALL
                        )
                        .requestMatchers("/api/admin/**").hasAnyAuthority(
                                "ROLE_ADMIN",
                                AdminApiAuthenticationFilter.SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_ALL
                        )
                        .requestMatchers("/api/user/**").authenticated()
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    private static boolean isNonBrowserAutomationRequest(HttpServletRequest request) {
        if (hasSessionCookie(request)) {
            return false;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return true;
        }
        return StringUtils.hasText(request.getHeader("X-Admin-Token"));
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            String name = cookie.getName();
            if ("SESSION".equals(name) || "JSESSIONID".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
