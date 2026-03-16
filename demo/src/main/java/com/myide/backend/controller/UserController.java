package com.myide.backend.controller;

import com.myide.backend.dto.auth.AuthDto;
import com.myide.backend.dto.user.UserDto;
import com.myide.backend.service.AuthService;
import com.myide.backend.service.UserService;
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
}