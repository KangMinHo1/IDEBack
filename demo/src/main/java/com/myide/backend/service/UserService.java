package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.dto.user.UserDto;
import com.myide.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserDto.Response createUser(UserDto.CreateRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        if (userRepository.existsByNickname(request.getNickname())) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .build();

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    public UserDto.Response getUser(Long userId) {
        User user = findUserById(userId);
        return mapToResponse(user);
    }

    @Transactional
    public UserDto.Response updateUser(Long userId, UserDto.UpdateRequest request) {
        User user = findUserById(userId);

        if (!user.getNickname().equals(request.getNickname())
                && userRepository.existsByNickname(request.getNickname())) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }

        user.updateProfile(request.getNickname(), request.getProfileImageUrl());
        return mapToResponse(user);
    }

    // 마이페이지 계정 탭 - 이메일 변경
    @Transactional
    public UserDto.Response changeEmail(Long userId, UserDto.ChangeEmailRequest request) {
        User user = findUserById(userId);

        if (user.getEmail().equals(request.getEmail())) {
            return mapToResponse(user);
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        user.updateEmail(request.getEmail());
        return mapToResponse(user);
    }

    // 마이페이지 계정 탭 - 비밀번호 변경
    @Transactional
    public void changePassword(Long userId, UserDto.ChangePasswordRequest request) {
        User user = findUserById(userId);

        // 1. 현재 비밀번호 확인
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }

        // 2. 새 비밀번호 길이 확인
        if (request.getNewPassword().length() < 8) {
            throw new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다.");
        }

        // 3. 기존 비밀번호와 같은지 확인
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new IllegalArgumentException("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }

        // 4. 새 비밀번호 암호화 후 저장
        String encodedNewPassword = passwordEncoder.encode(request.getNewPassword());
        user.updatePassword(encodedNewPassword);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = findUserById(userId);
        userRepository.delete(user);
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));
    }

    private UserDto.Response mapToResponse(User user) {
        return UserDto.Response.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .profileImageUrl(user.getProfileImageUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }
}