package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GithubController {

    private final UserRepository userRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getGithubStatus(
            @AuthenticationPrincipal Long currentUserId
    ) {
        if (currentUserId == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("connected", false);
            body.put("message", "로그인이 필요합니다.");
            return ResponseEntity.status(401).body(body);
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        boolean connected =
                user.getGithubAccessToken() != null &&
                        !user.getGithubAccessToken().trim().isEmpty();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", connected);

        /*
         * User 엔티티에 githubUsername, githubEmail, githubAvatarUrl 같은 필드가 있으면
         * 아래 값을 실제 필드로 바꿔서 내려주면 됩니다.
         */
        body.put("username", null);
        body.put("email", null);
        body.put("avatarUrl", null);
        body.put("connectedAt", null);

        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/link")
    public ResponseEntity<Map<String, Object>> disconnectGithub(
            @AuthenticationPrincipal Long currentUserId
    ) {
        if (currentUserId == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "로그인이 필요합니다.");
            return ResponseEntity.status(401).body(body);
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        user.updateGithubAccessToken(null);
        userRepository.save(user);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "GitHub 연결이 해제되었습니다.");
        body.put("connected", false);

        return ResponseEntity.ok(body);
    }
}