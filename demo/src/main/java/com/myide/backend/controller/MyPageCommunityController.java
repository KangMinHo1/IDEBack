package com.myide.backend.controller;

import com.myide.backend.dto.mypage.MyPageCommunityResponse;
import com.myide.backend.service.CurrentUserService;
import com.myide.backend.service.MyPageCommunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/community")
@RequiredArgsConstructor
public class MyPageCommunityController {

    private final MyPageCommunityService myPageCommunityService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<MyPageCommunityResponse> getMyCommunityActivity() {
        Long currentUserId = currentUserService.getCurrentUserId();

        return ResponseEntity.ok(
                myPageCommunityService.getMyCommunityActivity(
                        currentUserId
                )
        );
    }
}
