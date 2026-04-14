package com.myide.backend.controller;

import com.myide.backend.dto.auth.AuthDto;
import com.myide.backend.dto.user.UserDto;
import com.myide.backend.service.AuthService;
import com.myide.backend.service.UserService;
import com.myide.backend.service.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;
    private final AuthService authService; // 💡 로그인 로직 처리를 위해 주입
    private final CurrentUserService currentUserService; // ✅ 현재 로그인 사용자 식별용

    //로그인
    // 💡 [핵심] URL은 /api/users/login 이지만, 실제 일은 authService가 합니다.
    @PostMapping("/login")
    public ResponseEntity<AuthDto.TokenResponse> login(@RequestBody @Valid AuthDto.LoginRequest request) {
        try {
            return ResponseEntity.ok(authService.login(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    //회원가입
    @PostMapping
    public ResponseEntity<UserDto.Response> signUp(@RequestBody @Valid UserDto.CreateRequest request) {
        return ResponseEntity.ok(userService.createUser(request));
    }
    //회원조회(개인)
    @GetMapping("/{userId}")
    public ResponseEntity<UserDto.Response> getProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }
    //회원수정
    @PutMapping("/{userId}")
    public ResponseEntity<UserDto.Response> updateProfile(@PathVariable Long userId, @RequestBody @Valid UserDto.UpdateRequest request) {
        return ResponseEntity.ok(userService.updateUser(userId, request));
    }
    //회원탈퇴
    @DeleteMapping("/{userId}")
    public ResponseEntity<String> withdraw(@PathVariable Long userId) {
        userService.deleteUser(userId);
        return ResponseEntity.ok("회원 탈퇴가 완료되었습니다.");
    }
    // ---------------------------
    // ✅ 마이페이지 전용 me API
    // 프론트는 userId를 몰라도 토큰만 있으면 됨
    // ---------------------------

    @GetMapping("/me")
    public ResponseEntity<UserDto.Response> getMyProfile() {
        Long userId = currentUserService.getCurrentUserId();
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto.Response> updateMyProfile(
            @RequestBody @Valid UserDto.UpdateRequest request
    ) {
        Long userId = currentUserService.getCurrentUserId();
        return ResponseEntity.ok(userService.updateUser(userId, request));
    }

    // ✅ 이메일 변경
    @PatchMapping("/me/email")
    public ResponseEntity<UserDto.Response> changeMyEmail(
            @RequestBody @Valid UserDto.ChangeEmailRequest request
    ) {
        Long userId = currentUserService.getCurrentUserId();
        return ResponseEntity.ok(userService.changeEmail(userId, request));
    }

    // ✅ 비밀번호 변경
    @PatchMapping("/me/password")
    public ResponseEntity<String> changeMyPassword(
            @RequestBody @Valid UserDto.ChangePasswordRequest request
    ) {
        Long userId = currentUserService.getCurrentUserId();
        userService.changePassword(userId, request);
        return ResponseEntity.ok("비밀번호가 변경되었습니다.");
    }

    // ✅ 내 계정 삭제
    @DeleteMapping("/me")
    public ResponseEntity<String> deleteMyAccount() {
        Long userId = currentUserService.getCurrentUserId();
        userService.deleteUser(userId);
        return ResponseEntity.ok("회원 탈퇴가 완료되었습니다.");
    }
}