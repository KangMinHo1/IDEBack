package com.myide.backend.service;

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
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public AuthDto.TokenResponse login(AuthDto.LoginRequest request) {
        // 1. 유저 찾기
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("가입되지 않은 이메일입니다."));

        // 2. 비밀번호 검증 (BCrypt)
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 3. 비밀번호가 맞으면 JWT 토큰 발급
        String token = jwtProvider.createToken(user.getId());

        return new AuthDto.TokenResponse(token, user.getId());
    }
}