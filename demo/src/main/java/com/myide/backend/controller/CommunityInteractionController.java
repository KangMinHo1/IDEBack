package com.myide.backend.controller;
import com.myide.backend.dto.PostDto;
import com.myide.backend.dto.ReportDto;
import com.myide.backend.service.CommunityInteractionService;
import com.myide.backend.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class CommunityInteractionController {
    private final CommunityInteractionService service;
    private final CurrentUserService currentUser;
    @PostMapping("/{postId}/like")
    public PostDto.InteractionResponse like(@PathVariable Long postId) {
        return service.togglePost(postId,currentUser.getCurrentUserId(),false);
    }
    @PostMapping("/{postId}/scrap")
    public PostDto.InteractionResponse scrap(@PathVariable Long postId) {
        return service.togglePost(postId,currentUser.getCurrentUserId(),true);
    }
    @GetMapping("/{postId}/comments/interactions")
    public List<CommunityInteractionService.CommentState> states(@PathVariable Long postId,
                                                                 @RequestParam List<Long> ids, @AuthenticationPrincipal Long userId) {
        return service.commentStates(postId,ids,userId);
    }
    @PostMapping("/{postId}/comments/{commentId}/like")
    public PostDto.InteractionResponse commentLike(@PathVariable Long postId,@PathVariable Long commentId) {
        return service.toggleComment(postId,commentId,currentUser.getCurrentUserId());
    }
    @PostMapping("/{postId}/comments/{commentId}/reports")
    public Map<String,Object> report(@PathVariable Long postId,@PathVariable Long commentId,@RequestBody ReportDto.CreateRequest request) {
        return service.reportComment(postId,commentId,currentUser.getCurrentUserId(),request);
    }
}