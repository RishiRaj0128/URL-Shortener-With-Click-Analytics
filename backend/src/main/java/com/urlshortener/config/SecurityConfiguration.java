package com.urlshortener.config;

import com.urlshortener.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security configuration for stateless JWT authentication.
 * 
 * Configuration strategy:
 * - Stateless sessions (no Spring Session)
 * - JWT token in Authorization header
 * - BCrypt password hashing
 * - Custom filter chain with JwtAuthenticationFilter
 * - CORS enabled for frontend communication
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  @Bean
  public PasswordEncoder passwordEncoder() {
    // Explicitly configure BCrypt work factor to 12 rounds for strong hash security
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public AuthenticationManager authenticationManager(
      AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

    http
        .cors(cors -> cors.configurationSource(request -> {
            var corsConfiguration = new org.springframework.web.cors.CorsConfiguration();
            corsConfiguration.setAllowedOrigins(java.util.List.of("*"));
            corsConfiguration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            corsConfiguration.setAllowedHeaders(java.util.List.of("*"));
            return corsConfiguration;
        }))
        .csrf(csrf -> csrf.disable()) // Disabled for stateless APIs
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(
                (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
            .accessDeniedHandler(
                (request, response, accessDeniedException) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
        .authorizeHttpRequests(authz -> authz
            // Public endpoints (no auth required)
            .requestMatchers(HttpMethod.GET, "/*", "/{shortCode}").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers("/api/health/**").permitAll()
            .requestMatchers("/api/system/**").permitAll()
            .requestMatchers("/actuator/**").permitAll()

            // Protected endpoints (auth required)
            .requestMatchers(HttpMethod.POST, "/api/shorten/**").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/urls/**").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/analytics/**").authenticated()

            // Any other requests require auth
            .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
