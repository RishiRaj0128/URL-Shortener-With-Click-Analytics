package com.urlshortener.repository;

import com.urlshortener.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository interface for User entity.
 * Spring Data JPA provides standard CRUD operations plus custom queries.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * Find a user by email address.
     * Used during login to validate credentials.
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Check if a user with given email exists.
     * Used during signup to prevent duplicate accounts.
     */
    boolean existsByEmail(String email);
}
