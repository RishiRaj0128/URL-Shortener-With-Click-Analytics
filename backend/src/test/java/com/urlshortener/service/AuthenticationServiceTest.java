package com.urlshortener.service;

import com.urlshortener.dto.AuthRequest;
import com.urlshortener.dto.AuthResponse;
import com.urlshortener.entity.User;
import com.urlshortener.exception.UrlShortenerException;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthenticationService.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private PasswordEncoder passwordEncoder;
    
    @Mock
    private JwtTokenProvider tokenProvider;
    
    private AuthenticationService authenticationService;
    
    @BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService(userRepository, passwordEncoder, tokenProvider);
    }
    
    @Test
    void testSignupSuccess() {
        AuthRequest request = new AuthRequest("test@example.com", "password123");
        
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encodedPassword");
        
        User savedUser = new User();
        savedUser.setId(1L);
        savedUser.setEmail(request.getEmail());
        savedUser.setPasswordHash("encodedPassword");
        savedUser.setCreatedAt(LocalDateTime.now());
        
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(tokenProvider.generateToken(request.getEmail())).thenReturn("jwt-token");
        
        AuthResponse response = authenticationService.signup(request);
        
        assertNotNull(response);
        assertEquals(request.getEmail(), response.getEmail());
        assertEquals("jwt-token", response.getToken());
        assertEquals(1L, response.getUserId());
    }
    
    @Test
    void testSignupDuplicateEmail() {
        AuthRequest request = new AuthRequest("existing@example.com", "password123");
        
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);
        
        assertThrows(UrlShortenerException.class, () -> {
            authenticationService.signup(request);
        });
    }
    
    @Test
    void testLoginSuccess() {
        String email = "test@example.com";
        String password = "password123";
        AuthRequest request = new AuthRequest(email, password);
        
        User user = new User();
        user.setId(1L);
        user.setEmail(email);
        user.setPasswordHash("encodedPassword");
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, user.getPasswordHash())).thenReturn(true);
        when(tokenProvider.generateToken(email)).thenReturn("jwt-token");
        
        AuthResponse response = authenticationService.login(request);
        
        assertNotNull(response);
        assertEquals(email, response.getEmail());
        assertEquals("jwt-token", response.getToken());
    }
    
    @Test
    void testLoginInvalidEmail() {
        AuthRequest request = new AuthRequest("nonexistent@example.com", "password");
        
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());
        
        assertThrows(UrlShortenerException.class, () -> {
            authenticationService.login(request);
        });
    }
    
    @Test
    void testLoginInvalidPassword() {
        String email = "test@example.com";
        AuthRequest request = new AuthRequest(email, "wrongPassword");
        
        User user = new User();
        user.setId(1L);
        user.setEmail(email);
        user.setPasswordHash("correctEncodedPassword");
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.getPassword(), user.getPasswordHash())).thenReturn(false);
        
        assertThrows(UrlShortenerException.class, () -> {
            authenticationService.login(request);
        });
    }
}
