package com.myide.backend.controller;

import com.myide.backend.domain.User;
import com.myide.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod; // 추가: GitHub 사용자 정보 조회할 때 GET 메서드 사용
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.List; // 추가: headers.setAccept(List.of(...)) 사용
import java.util.Map;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GithubOAuthController {

    @Value("${github.client.id:}")
    private String clientId;

    @Value("${github.client.secret:}")
    private String clientSecret;

    @Value("${github.redirect-uri:http://localhost:3000/auth/github/callback}")
    private String redirectUri;

    private final UserRepository userRepository;

    @PostMapping("/link")
    public ResponseEntity<String> linkGithubToken(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long currentUserId
    ) {
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("LOGIN_REQUIRED");
        }

        String code = body.get("code");

        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("깃허브 인증 코드가 없습니다.");
        }

        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.internalServerError().body("GitHub OAuth Client ID가 설정되어 있지 않습니다.");
        }

        if (clientSecret == null || clientSecret.isBlank()) {
            return ResponseEntity.internalServerError().body("GitHub OAuth Client Secret이 설정되어 있지 않습니다.");
        }

        if (redirectUri == null || redirectUri.isBlank()) {
            return ResponseEntity.internalServerError().body("GitHub OAuth redirect-uri가 설정되어 있지 않습니다.");
        }

        try {
            String githubAccessToken = exchangeCodeForAccessToken(code.trim());

            // 추가: access token으로 GitHub 사용자 login 값을 조회
            String githubUsername = fetchGithubUsername(githubAccessToken);

            User user = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다."));

            // 변경: 토큰만 저장하지 않고 GitHub username까지 함께 저장
            user.updateGithubInfo(githubAccessToken, githubUsername);

            userRepository.save(user);

            return ResponseEntity.ok("깃허브 연동이 완료되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("깃허브 연동 중 서버 오류: " + e.getMessage());
        }
    }

    private String exchangeCodeForAccessToken(String code) {
        RestTemplate restTemplate = new RestTemplate();
        String githubTokenUrl = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();

        // 변경: parseMediaTypes 대신 List.of(MediaType.APPLICATION_JSON) 사용
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("client_id", clientId);
        requestBody.add("client_secret", clientSecret);
        requestBody.add("code", code);
        requestBody.add("redirect_uri", redirectUri);

        HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                githubTokenUrl,
                request,
                Map.class
        );

        Map<?, ?> responseBody = response.getBody();

        if (responseBody == null) {
            throw new IllegalArgumentException("GitHub 토큰 응답이 비어 있습니다.");
        }

        Object error = responseBody.get("error");
        Object errorDescription = responseBody.get("error_description");

        if (error != null) {
            String message = errorDescription != null
                    ? String.valueOf(errorDescription)
                    : String.valueOf(error);

            throw new IllegalArgumentException("GitHub 토큰 발급 실패: " + message);
        }

        Object accessTokenValue = responseBody.get("access_token");

        if (accessTokenValue == null || String.valueOf(accessTokenValue).isBlank()) {
            throw new IllegalArgumentException("GitHub access token이 응답에 없습니다.");
        }

        return String.valueOf(accessTokenValue);
    }

    // 추가: access token으로 GitHub /user API를 호출해서 login 값을 가져오는 메서드
    private String fetchGithubUsername(String accessToken) {
        RestTemplate restTemplate = new RestTemplate(); // 추가: 이 메서드 안에서도 RestTemplate 필요

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28"); // 추가: GitHub API 버전 명시

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "https://api.github.com/user",
                HttpMethod.GET,
                request,
                Map.class
        );

        Map<?, ?> responseBody = response.getBody();

        if (responseBody == null) {
            throw new IllegalArgumentException("GitHub 사용자 정보 응답이 비어 있습니다.");
        }

        Object login = responseBody.get("login");

        if (login == null || String.valueOf(login).isBlank()) {
            throw new IllegalArgumentException("GitHub 사용자명(login)을 가져오지 못했습니다.");
        }

        return String.valueOf(login);
    }
}