package com.myide.backend.service;

import com.myide.backend.domain.post.Comment;
import com.myide.backend.domain.post.Post;
import com.myide.backend.domain.post.PostLike;
import com.myide.backend.domain.post.PostScrap;
import com.myide.backend.domain.post.PostType;
import com.myide.backend.dto.mypage.MyPageCommunityResponse;
import com.myide.backend.repository.post.CommentRepository;
import com.myide.backend.repository.post.LikeRepository;
import com.myide.backend.repository.post.PostRepository;
import com.myide.backend.repository.post.ScrapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyPageCommunityService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final ScrapRepository scrapRepository;

    public MyPageCommunityResponse getMyCommunityActivity(Long userId) {
        List<Post> myPosts =
                postRepository.findByAuthorIdAndPostTypeOrderByCreatedAtDesc(
                        userId,
                        PostType.NORMAL
                );

        List<Comment> myComments =
                commentRepository.findMyCommentsOrderByCreatedAtDesc(userId);

        List<PostLike> myLikes =
                likeRepository.findMyLikesOrderByCreatedAtDesc(userId);

        List<PostScrap> myScraps =
                scrapRepository.findMyScrapsOrderByCreatedAtDesc(userId);

        /*
         * 같은 게시글이 "내가 쓴 글"과 "좋아요" 등에 동시에 포함될 수 있으므로
         * 게시글별 댓글 수 / 좋아요 수 조회 결과를 요청 안에서 캐싱한다.
         */
        Map<Long, Long> commentCountCache = new HashMap<>();
        Map<Long, Long> likeCountCache = new HashMap<>();

        List<MyPageCommunityResponse.PostItem> posts =
                myPosts.stream()
                        .map(post -> toPostItem(
                                post,
                                commentCountCache,
                                likeCountCache
                        ))
                        .toList();

        List<MyPageCommunityResponse.CommentItem> comments =
                myComments.stream()
                        .map(this::toCommentItem)
                        .toList();

        List<MyPageCommunityResponse.PostItem> likes =
                myLikes.stream()
                        .map(PostLike::getPost)
                        .filter(this::isNormalPost)
                        .map(post -> toPostItem(
                                post,
                                commentCountCache,
                                likeCountCache
                        ))
                        .toList();

        List<MyPageCommunityResponse.PostItem> scraps =
                myScraps.stream()
                        .map(PostScrap::getPost)
                        .filter(this::isNormalPost)
                        .map(post -> toPostItem(
                                post,
                                commentCountCache,
                                likeCountCache
                        ))
                        .toList();

        return new MyPageCommunityResponse(
                posts,
                comments,
                likes,
                scraps
        );
    }

    private MyPageCommunityResponse.PostItem toPostItem(
            Post post,
            Map<Long, Long> commentCountCache,
            Map<Long, Long> likeCountCache
    ) {
        Long postId = post.getId();

        long commentCount =
                commentCountCache.computeIfAbsent(
                        postId,
                        commentRepository::countByPost_Id
                );

        long actualLikeCount =
                likeCountCache.computeIfAbsent(
                        postId,
                        likeRepository::countByPost_Id
                );

        return new MyPageCommunityResponse.PostItem(
                postId,
                post.getTitle(),
                post.getCategory(),
                post.getCreatedAt(),
                post.getViewCount(),
                Math.toIntExact(actualLikeCount),
                commentCount
        );
    }

    private MyPageCommunityResponse.CommentItem toCommentItem(
            Comment comment
    ) {
        Post post = comment.getPost();

        return new MyPageCommunityResponse.CommentItem(
                comment.getId(),
                comment.getContent(),
                post.getId(),
                post.getTitle(),
                comment.getCreatedAt()
        );
    }

    private boolean isNormalPost(Post post) {
        return post != null && post.getPostType() == PostType.NORMAL;
    }
}
