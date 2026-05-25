package com.myide.backend.service;

import com.myide.backend.domain.RefreshToken;
import com.myide.backend.domain.User;
import com.myide.backend.repository.RefreshTokenRepository;
import com.myide.backend.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;

    public String issueRefreshToken(User user) {
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hash(refreshToken))
                .expiresAt(LocalDateTime.now().plusSeconds(jwtProvider.getRefreshTokenExpirationMs() / 1000))
                .revoked(false)
                .build();

        refreshTokenRepository.save(entity);

        return refreshToken;
    }

    public RefreshToken validateStoredRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required.");
        }

        if (!jwtProvider.validateRefreshToken(rawRefreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token.");
        }

        String tokenHash = hash(rawRefreshToken);

        RefreshToken storedToken = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Refresh token was not found."));

        if (!storedToken.isUsable()) {
            storedToken.revoke();
            throw new IllegalArgumentException("Refresh token is expired or revoked.");
        }

        Long userIdFromJwt = jwtProvider.getUserIdFromToken(rawRefreshToken);

        if (!storedToken.getUserId().equals(userIdFromJwt)) {
            storedToken.revoke();
            throw new IllegalArgumentException("Refresh token owner mismatch.");
        }

        return storedToken;
    }

    public void revoke(RefreshToken refreshToken) {
        if (refreshToken == null) {
            return;
        }

        refreshToken.revoke();
    }

    public void revokeRawToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        String tokenHash = hash(rawRefreshToken);

        refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .ifPresent(RefreshToken::revoke);
    }

    public void revokeAllByUserId(Long userId) {
        if (userId == null) {
            return;
        }

        List<RefreshToken> tokens = refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId);

        for (RefreshToken token : tokens) {
            token.revoke();
        }
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedHash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash refresh token.", e);
        }
    }
}