package com.myide.backend.service;

import com.myide.backend.dto.PostDto;
import com.myide.backend.dto.ReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityInteractionService {
    private final JdbcTemplate jdbc;
    public record State(long likeCount, long scrapCount, boolean liked, boolean scrapped) {}
    public record CommentState(long id, long likeCount, boolean liked, boolean reported) {}

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
    private void user(Long id) {
        if (id == null || count("select count(*) from users where id=?", id) == 0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }
    private void lockPost(Long id) {
        List<String> types = jdbc.query("select post_type from posts where id=? for update", (rs,n) -> rs.getString(1), id);
        if (types.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        if ("NOTICE".equals(types.get(0))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공지사항에는 사용할 수 없습니다.");
    }
    private Long lockComment(Long postId, Long commentId) {
        // 게시글 -> 댓글 순으로 잠금을 획득하여 게시글 삭제와 순서를 맞춥니다.
        lockPost(postId);
        List<Long> ids = jdbc.query("select author_id from comments where id=? and post_id=? for update", (rs,n) -> rs.getLong(1), commentId, postId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 게시글의 댓글을 찾을 수 없습니다.");
        return ids.get(0);
    }
    public State state(Long postId, Long userId) {
        return new State(count("select count(*) from post_likes where post_id=?", postId),
                count("select count(*) from post_scraps where post_id=?", postId),
                userId != null && count("select count(*) from post_likes where post_id=? and user_id=?", postId, userId) > 0,
                userId != null && count("select count(*) from post_scraps where post_id=? and user_id=?", postId, userId) > 0);
    }
    @Transactional
    public PostDto.InteractionResponse togglePost(Long postId, Long userId, boolean scrap) {
        user(userId); lockPost(postId);
        // 테이블/컬럼은 서버 상수만 사용합니다. 요청 문자열을 SQL 식별자로 사용하지 않습니다.
        String table = scrap ? "post_scraps" : "post_likes";
        String column = scrap ? "scrap_count" : "like_count";
        boolean active = count("select count(*) from " + table + " where post_id=? and user_id=?", postId, userId) == 0;
        if (active) jdbc.update("insert into " + table + " (post_id,user_id,created_at,updated_at) values (?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", postId, userId);
        else jdbc.update("delete from " + table + " where post_id=? and user_id=?", postId, userId);
        int total = Math.toIntExact(count("select count(*) from " + table + " where post_id=?", postId));
        jdbc.update("update posts set " + column + "=? where id=?", total, postId);
        return PostDto.InteractionResponse.builder().active(active).count(total).build();
    }
    public List<CommentState> commentStates(Long postId, List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "댓글 ID는 한 번에 100개 이하로 요청해 주세요.");
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId == null ? -1L : userId); args.add(userId == null ? -1L : userId); args.add(postId); args.addAll(ids);
        return jdbc.query("select c.id, (select count(*) from comment_likes l where l.comment_id=c.id) as cnt, " +
                        "exists(select 1 from comment_likes l where l.comment_id=c.id and l.user_id=?) as liked, " +
                        "exists(select 1 from comment_reports r where r.comment_id=c.id and r.reporter_id=?) as reported " +
                        "from comments c where c.post_id=? and c.id in (" + marks + ")",
                (rs,n) -> new CommentState(rs.getLong("id"), rs.getLong("cnt"), rs.getBoolean("liked"), rs.getBoolean("reported")), args.toArray());
    }
    @Transactional
    public PostDto.InteractionResponse toggleComment(Long postId, Long commentId, Long userId) {
        user(userId); lockComment(postId, commentId);
        boolean active = count("select count(*) from comment_likes where comment_id=? and user_id=?", commentId,userId) == 0;
        if (active) jdbc.update("insert into comment_likes(comment_id,user_id) values (?,?)",commentId,userId);
        else jdbc.update("delete from comment_likes where comment_id=? and user_id=?",commentId,userId);
        return PostDto.InteractionResponse.builder().active(active)
                .count(Math.toIntExact(count("select count(*) from comment_likes where comment_id=?",commentId))).build();
    }
    @Transactional
    public Map<String,Object> reportComment(Long postId, Long commentId, Long userId, ReportDto.CreateRequest request) {
        user(userId);
        if (request == null || request.getReason() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, " 신고 사유를 선택해 주세요.");
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상세 신고 내용은 500자 이하로 입력해 주세요.");
        Long author = lockComment(postId,commentId);
        if (author.equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 댓글은 신고할 수 없습니다.");
        if (count("select count(*) from comment_reports where comment_id=? and reporter_id=?",commentId,userId)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 신고한 댓글입니다.");
        jdbc.update("insert into comment_reports(comment_id,reporter_id,reason,content,status) values (?,?,?,?, 'PENDING')",commentId,userId,request.getReason().name(),content.isEmpty()?null:content);
        return Map.of("commentId",commentId,"status","PENDING");
    }
}