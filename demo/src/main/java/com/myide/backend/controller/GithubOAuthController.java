package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true") // 🚀 [핵심] 프론트엔드의 접근을 허락합니다!
public class GithubOAuthController {

    @Value("${github.client.id}")
    private String clientId;

    @Value("${github.client.secret}")
    private String clientSecret;

    private final UserRepository userRepository;

    @PostMapping("/link")
    public ResponseEntity<String> linkGithubToken(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId) {

        String code = body.get("code");
        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body("깃허브 코드가 없습니다.");
        }

        RestTemplate restTemplate = new RestTemplate();
        String githubTokenUrl = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        Map<String, String> requestBody = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(githubTokenUrl, request, Map.class);
            Map<String, String> responseBody = response.getBody();

            if (responseBody != null && responseBody.containsKey("access_token")) {
                String accessToken = responseBody.get("access_token");

                // DB에 진짜 토큰 저장!
                User user = userRepository.findById(currentUserId)
                        .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다."));
                user.updateGithubAccessToken(accessToken);
                userRepository.save(user);

                return ResponseEntity.ok("깃허브 연동이 완료되었습니다!");
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("깃허브 토큰 발급 실패 (코드가 만료되었거나 잘못되었습니다.)");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("깃허브 연동 중 서버 오류: " + e.getMessage());
        }
    }
}