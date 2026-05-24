package com.chat.authservice.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chat.authservice.models.RefreshToken;
import com.chat.authservice.models.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser(User user);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.token = :token")
    int deleteByTokenValue(@Param("token") String token);
}
