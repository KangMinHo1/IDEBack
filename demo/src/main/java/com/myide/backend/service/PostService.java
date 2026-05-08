package com.myide.backend.service;

import com.myide.backend.domain.post.*;
import com.myide.backend.dto.PostDto;
import com.myide.backend.repository.post.CommentRepository;
import com.myide.backend.repository.post.LikeRepository;
import com.myide.backend.repository.post.PostRepository;
import com.myide.backend.repository.post.ScrapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final LikeRepository likeRepository;
    private final ScrapRepository scrapRepository;
    private final CommentRepository commentRepository;

    // 💡 [핵심] 인메모리 캐시 장부 (키: "게시글ID_식별자", 값: 마지막 조회 시간 밀리초)
    private final ConcurrentHashMap<String, Long> viewCache = new ConcurrentHashMap<>();

    // 💡 더블 렌더링 및 악성 F5 방지용 쿨타임 (5초)
    private static final long VIEW_COOLDOWN_MS = 5000;

    // ==========================================
    // 1. 게시글 목록 조회
    // ==========================================
    public Page<PostDto.ListResponse> getPosts(String category, String keyword, Pageable pageable) {
        Page<Post> posts = postRepository.searchPosts(category, keyword, pageable);

        return posts.map(post -> {
            String snippet = post.getContent().length() > 100
                    ? post.getContent().substring(0, 100) + "..."
                    : post.getContent();

            String previewImg = post.getAttachments().stream()
                    .filter(a -> "image".equals(a.getType()))
                    .findFirst()
                    .map(Attachment::getUrl)
                    .orElse(null);

            return PostDto.ListResponse.builder()
                    .id(post.getId())
                    .title(post.getTitle())
                    .contentSnippet(snippet)
                    .category(post.getCategory())
                    .tags(post.getTags())
                    .authorId(post.getAuthorId())
                    .authorName(post.getAuthorName())
                    .views(post.getViews())
                    .likeCount(post.getLikeCount())
                    .scrapCount(post.getScrapCount())
                    .previewImageUrl(previewImg)
                    .createdAt(post.getCreatedAt())
                    .build();
        });
    }

    // ==========================================
    // 2. 게시글 상세 조회 (인메모리 캐시 적용)
    // 💡 파라미터가 (Long, Long, String) 으로 일치하게 수정됨!
    // ==========================================
    @Transactional
    public PostDto.DetailResponse getPostDetail(Long postId, Long currentUserId, String clientIp) {

        // 1) 유저 식별키 생성 (로그인 유저는 ID 우선, 비로그인은 IP)
        String userIdentifier = (currentUserId != null) ? "USER_" + currentUserId : "IP_" + clientIp;
        String cacheKey = postId + "_" + userIdentifier;

        long currentTime = System.currentTimeMillis();
        Long lastViewTime = viewCache.get(cacheKey);

        // 2) 조건: 장부에 없거나, 마지막으로 읽은 지 5초가 지났을 때만 DB 조회수 증가
        if (lastViewTime == null || (currentTime - lastViewTime) > VIEW_COOLDOWN_MS) {
            postRepository.incrementViews(postId);
            viewCache.put(cacheKey, currentTime); // 장부 갱신
        }

        // 3) 게시글 엔티티 조회
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        // 4) 첨부파일 변환
        List<PostDto.AttachmentResponse> attachments = post.getAttachments().stream()
                .map(a -> PostDto.AttachmentResponse.builder()
                        .id(a.getId())
                        .name(a.getName())
                        .type(a.getType())
                        .url(a.getUrl())
                        .build())
                .collect(Collectors.toList());

        boolean isLiked = currentUserId != null && likeRepository.existsByPostIdAndUserId(postId, currentUserId);
        boolean isScrapped = currentUserId != null && scrapRepository.existsByPostIdAndUserId(postId, currentUserId);

        return PostDto.DetailResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .category(post.getCategory())
                .tags(post.getTags())
                .authorId(post.getAuthorId())
                .authorName(post.getAuthorName())
                // 💡 JPA가 영속성 컨텍스트에 캐시된 이전 뷰 카운트를 가져올 수 있으므로,
                // 증가 로직이 수행되었다면 뷰 카운트를 +1 해서 반환해줍니다.
                .views(lastViewTime == null || (currentTime - lastViewTime) > VIEW_COOLDOWN_MS ? post.getViews() + 1 : post.getViews())
                .likeCount(post.getLikeCount())
                .scrapCount(post.getScrapCount())
                .liked(isLiked)
                .scrapped(isScrapped)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .attachments(attachments)
                .build();
    }

    // ==========================================
    // 3. 게시글 생성
    // ==========================================
    @Transactional
    public Long createPost(PostDto.CreateRequest request, Long authorId, String authorName) {
        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .category(request.getCategory())
                .tags(request.getTags())
                .authorId(authorId)
                .authorName(authorName)
                .build();

        if (request.getAttachments() != null) {
            for (PostDto.AttachmentRequest fileReq : request.getAttachments()) {
                Attachment attachment = Attachment.builder()
                        .post(post)
                        .name(fileReq.getName())
                        .type(fileReq.getType())
                        .url(fileReq.getUrl())
                        .build();
                post.getAttachments().add(attachment);
            }
        }

        return postRepository.save(post).getId();
    }

    // ==========================================
    // 4. 게시글 수정
    // ==========================================
    @Transactional
    public void updatePost(Long postId, PostDto.UpdateRequest request, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        if (!post.getAuthorId().equals(currentUserId)) {
            throw new IllegalArgumentException("수정 권한이 없습니다.");
        }

        post.update(request.getTitle(), request.getContent(), request.getCategory(), request.getTags());

        post.getAttachments().clear();
        if (request.getAttachments() != null) {
            for (PostDto.AttachmentRequest fileReq : request.getAttachments()) {
                Attachment attachment = Attachment.builder()
                        .post(post)
                        .name(fileReq.getName())
                        .type(fileReq.getType())
                        .url(fileReq.getUrl())
                        .build();
                post.getAttachments().add(attachment);
            }
        }
    }

    // ==========================================
    // 5. 게시글 삭제
    // ==========================================
    @Transactional
    public void deletePost(Long postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        if (!post.getAuthorId().equals(currentUserId)) {
            throw new IllegalArgumentException("삭제 권한이 없습니다.");
        }

        commentRepository.deleteByPostId(postId);
        likeRepository.deleteByPostId(postId);
        scrapRepository.deleteByPostId(postId);

        postRepository.delete(post);
    }

    // ==========================================
    // 6. 좋아요 토글
    // ==========================================
    @Transactional
    public PostDto.InteractionResponse toggleLike(Long postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        boolean isLiked = likeRepository.existsByPostIdAndUserId(postId, currentUserId);

        if (isLiked) {
            likeRepository.deleteByPostIdAndUserId(postId, currentUserId);
            post.decreaseLikeCount();
        } else {
            likeRepository.save(PostLike.builder().post(post).userId(currentUserId).build());
            post.increaseLikeCount();
        }

        return PostDto.InteractionResponse.builder()
                .active(!isLiked)
                .count(post.getLikeCount())
                .build();
    }

    // ==========================================
    // 7. 스크랩 토글
    // ==========================================
    @Transactional
    public PostDto.InteractionResponse toggleScrap(Long postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        boolean isScrapped = scrapRepository.existsByPostIdAndUserId(postId, currentUserId);

        if (isScrapped) {
            scrapRepository.deleteByPostIdAndUserId(postId, currentUserId);
            post.decreaseScrapCount();
        } else {
            scrapRepository.save(PostScrap.builder().post(post).userId(currentUserId).build());
            post.increaseScrapCount();
        }

        return PostDto.InteractionResponse.builder()
                .active(!isScrapped)
                .count(post.getScrapCount())
                .build();
    }

    // ==========================================
    // 8. 댓글 목록 조회
    // ==========================================
    public Page<PostDto.CommentResponse> getComments(Long postId, Pageable pageable) {
        Page<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId, pageable);
        return comments.map(c -> PostDto.CommentResponse.builder()
                .id(c.getId())
                .postId(c.getPost().getId())
                .content(c.getContent())
                .authorId(c.getAuthorId())
                .authorName(c.getAuthorName())
                .createdAt(c.getCreatedAt())
                .build());
    }

    // ==========================================
    // 9. 댓글 작성
    // ==========================================
    @Transactional
    public PostDto.CommentResponse createComment(Long postId, PostDto.CommentRequest request, Long authorId, String authorName) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글을 찾을 수 없습니다."));

        Comment comment = Comment.builder()
                .post(post)
                .content(request.getContent())
                .authorId(authorId)
                .authorName(authorName)
                .build();

        Comment savedComment = commentRepository.save(comment);

        return PostDto.CommentResponse.builder()
                .id(savedComment.getId())
                .postId(post.getId())
                .content(savedComment.getContent())
                .authorId(savedComment.getAuthorId())
                .authorName(savedComment.getAuthorName())
                .createdAt(savedComment.getCreatedAt())
                .build();
    }
}