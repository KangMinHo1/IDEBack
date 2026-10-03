package com.myide.backend.dto.mypage;

import java.time.LocalDateTime;
import java.util.List;

public record MyPageCommunityResponse(
        List<PostItem> posts,
        List<CommentItem> comments,
        List<PostItem> likes,
        List<PostItem> scraps
) {

    public record PostItem(
            Long id,
            String title,
            String category,
            LocalDateTime createdAt,
            int viewCount,
            int likeCount,
            long commentCount
    ) {
    }

    public record CommentItem(
            Long id,
            String content,
            Long postId,
            String postTitle,
            LocalDateTime createdAt
    ) {
    }
}
