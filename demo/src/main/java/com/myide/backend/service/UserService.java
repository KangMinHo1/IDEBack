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
    public UserDto.Response updateUser(
            Long userId,
            UserDto.UpdateRequest request
    ) {
        User user = findUserById(userId);

        if (
                !user.getNickname().equals(request.getNickname())
                        && userRepository.existsByNickname(request.getNickname())
        ) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }

        user.updateProfile(
                request.getNickname(),
                request.getProfileImageUrl()
        );

        return mapToResponse(user);
    }

    /*
     * =========================================================
     * 마이페이지 - 사용자명 변경
     * =========================================================
     */
    @Transactional
    public UserDto.Response changeNickname(
            Long userId,
            String nickname
    ) {
        User user = findUserById(userId);

        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException("사용자명을 입력해주세요.");
        }

        String nextNickname = nickname.trim();

        if (nextNickname.length() > 30) {
            throw new IllegalArgumentException(
                    "사용자명은 30자 이하로 입력해주세요."
            );
        }

        /*
         * 현재 닉네임과 동일하면 변경 없이 현재 사용자 반환
         */
        if (user.getNickname().equals(nextNickname)) {
            return mapToResponse(user);
        }

        /*
         * 다른 사용자가 이미 사용 중인지 확인
         */
        if (userRepository.existsByNickname(nextNickname)) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 사용자명입니다."
            );
        }

        /*
         * 기존 프로필 이미지는 유지하고
         * 닉네임만 변경
         *
         * User 엔티티에 이미 updateProfile()이 있기 때문에
         * 별도의 Setter를 추가할 필요 없음.
         */
        user.updateProfile(
                nextNickname,
                user.getProfileImageUrl()
        );

        return mapToResponse(user);
    }

    /*
     * =========================================================
     * 마이페이지 - 이메일 변경
     * =========================================================
     */
    @Transactional
    public UserDto.Response changeEmail(
            Long userId,
            UserDto.ChangeEmailRequest request
    ) {
        User user = findUserById(userId);

        if (user.getEmail().equals(request.getEmail())) {
            return mapToResponse(user);
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 이메일입니다."
            );
        }

        user.updateEmail(request.getEmail());

        return mapToResponse(user);
    }

    /*
     * =========================================================
     * 마이페이지 - 비밀번호 변경
     * =========================================================
     */
    @Transactional
    public void changePassword(
            Long userId,
            UserDto.ChangePasswordRequest request
    ) {
        User user = findUserById(userId);

        if (
                !passwordEncoder.matches(
                        request.getCurrentPassword(),
                        user.getPassword()
                )
        ) {
            throw new IllegalArgumentException(
                    "현재 비밀번호가 일치하지 않습니다."
            );
        }

        if (request.getNewPassword().length() < 8) {
            throw new IllegalArgumentException(
                    "새 비밀번호는 8자 이상이어야 합니다."
            );
        }

        if (
                passwordEncoder.matches(
                        request.getNewPassword(),
                        user.getPassword()
                )
        ) {
            throw new IllegalArgumentException(
                    "새 비밀번호는 현재 비밀번호와 달라야 합니다."
            );
        }

        String encodedNewPassword =
                passwordEncoder.encode(
                        request.getNewPassword()
                );

        user.updatePassword(
                encodedNewPassword
        );
    }

    /*
     * =========================================================
     * 회원 탈퇴
     * =========================================================
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = findUserById(userId);

        userRepository.delete(user);
    }

    private User findUserById(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "존재하지 않는 회원입니다."
                                )
                );
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