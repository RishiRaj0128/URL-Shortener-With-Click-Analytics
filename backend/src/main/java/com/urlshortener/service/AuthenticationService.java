package com.urlshortener.service;

import com.urlshortener.dto.AuthRequest;
import com.urlshortener.dto.AuthResponse;
import com.urlshortener.entity.User;
import com.urlshortener.exception.UrlShortenerException;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Service for user authentication (signup/login).
 * 
 * Responsibilities:
 * - Validate user credentials
 * - Create new user accounts
 * - Generate JWT tokens
 * 
 * Constructor injection per Spring best practices.
 */
@Service
@Transactional
public class AuthenticationService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    
    @Autowired
    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }
    
    /**
     * Register a new user.
     * Throws exception if email already exists.
     */
    public AuthResponse signup(AuthRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UrlShortenerException(
                "Email already registered: " + request.getEmail(),
                HttpStatus.CONFLICT
            );
        }
        
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        
        User savedUser = userRepository.save(user);
        String token = tokenProvider.generateToken(savedUser.getEmail());
        
        return new AuthResponse(token, savedUser.getEmail(), savedUser.getId());
    }
    
    /**
     * Authenticate user and return JWT token.
     * Throws exception if credentials are invalid.
     */
    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new UrlShortenerException(
                "Invalid email or password",
                HttpStatus.UNAUTHORIZED
            ));
        
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UrlShortenerException(
                "Invalid email or password",
                HttpStatus.UNAUTHORIZED
            );
        }
        
        String token = tokenProvider.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getId());
    }
}
