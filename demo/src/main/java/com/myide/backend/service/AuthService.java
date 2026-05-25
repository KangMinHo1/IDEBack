package com.myide.backend.service;

import com.myide.backend.domain.RefreshToken;
import com.myide.backend.domain.User;
import com.myide.backend.dto.auth.AuthDto;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthDto.TokenIssueResult login(AuthDto.LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("가입되지 않은 이메일입니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        /*
         * 기존 refresh token을 모두 무효화합니다.
         * 여러 기기 동시 로그인을 허용하려면 이 줄은 제거하면 됩니다.
         */
        refreshTokenService.revokeAllByUserId(user.getId());

        String accessToken = jwtProvider.createAccessToken(user);
        String refreshToken = refreshTokenService.issueRefreshToken(user);

        return AuthDto.TokenIssueResult.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(user)
                .build();
    }

    public AuthDto.TokenIssueResult refresh(String rawRefreshToken) {
        RefreshToken storedRefreshToken =
                refreshTokenService.validateStoredRefreshToken(rawRefreshToken);

        User user = userRepository.findById(storedRefreshToken.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        /*
         * Refresh Token Rotation:
         * 사용된 refresh token은 즉시 폐기하고 새 refresh token을 발급합니다.
         */
        refreshTokenService.revoke(storedRefreshToken);

        String nextAccessToken = jwtProvider.createAccessToken(user);
        String nextRefreshToken = refreshTokenService.issueRefreshToken(user);

        return AuthDto.TokenIssueResult.builder()
                .accessToken(nextAccessToken)
                .refreshToken(nextRefreshToken)
                .user(user)
                .build();
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeRawToken(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public AuthDto.UserSummary getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        return AuthDto.UserSummary.from(user);
    }
}