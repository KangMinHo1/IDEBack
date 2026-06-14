package com.myide.backend.controller;

import com.myide.backend.dto.mypage.ActivityHeatmapResponse;
import com.myide.backend.service.MyPageActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/activity")
@RequiredArgsConstructor
public class MyPageActivityController {

    private final MyPageActivityService myPageActivityService;

    @GetMapping("/heatmap")
    public ResponseEntity<ActivityHeatmapResponse> getMyActivityHeatmap(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(defaultValue = "49") int days
    ) {
        if (currentUserId == null) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(
                myPageActivityService.getMyActivityHeatmap(currentUserId, days)
        );
    }
}