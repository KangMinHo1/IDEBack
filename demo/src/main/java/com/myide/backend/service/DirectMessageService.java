package com.myide.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMessageService {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    public record Recipient(String id,String name) {}
    public record Source(String postId,String title) {}
    public record Message(String id,String conversationId,String senderId,String content,String createdAt,boolean mine,String readAt,String clientId) {}
    public record Conversation(String id,Recipient recipient,Source source,String lastContent,String updatedAt,long unreadCount) {}
    public record Slice<T>(List<T> items,boolean hasMore) {}
    public record StartRequest(Long recipientId,Long postId) {}
    public record SendRequest(String content,String clientId) {}
    public record ReadRequest(Long throughId) {}
    public record Changed(Long first,Long second) {}
    private record Pair(long id,long low,long high) {
        long other(long me) { return low == me ? high : low; }
    }
    private ResponseStatusException error(HttpStatus status,String text) { return new ResponseStatusException(status,text); }
    private long count(String sql,Object...args) {
        Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0:n;
    }
    private void user(Long id) {
        if(id==null || count("select count(*) from users where id=? and role='USER'",id)==0)
            throw error(HttpStatus.UNAUTHORIZED,"일반 사용자 로그인이 필요합니다.");
    }
    private Pair participant(long conversationId,Long me,boolean lock) {
        user(me);
        List<Pair> rows=jdbc.query("select id,user_low,user_high from dm_conversations where id=? and (user_low=? or user_high=?)"+(lock?" for update":""),
                (rs,n)->new Pair(rs.getLong(1),rs.getLong(2),rs.getLong(3)),conversationId,me,me);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"대화를 찾을 수 없습니다.");
        return rows.get(0);
    }
    private String time(ResultSet rs,String column) throws SQLException {
        var t=rs.getTimestamp(column); return t==null?null:t.toLocalDateTime().toString();
    }
    private Conversation conversation(long id,long me) {
        return jdbc.queryForObject("select c.*, case when c.user_low=? then c.user_high else c.user_low end recipient_id, " +
                "(select content from dm_messages m where m.conversation_id=c.id order by m.id desc limit 1) last_content, " +
                "(select count(*) from dm_messages m where m.conversation_id=c.id and m.receiver_id=? and m.read_at is null) unread_count " +
                "from dm_conversations c where c.id=?", (rs,n)-> {
            long other=rs.getLong("recipient_id");
            List<String> names=jdbc.query("select nickname from users where id=?",(r,k)->r.getString(1),other);
            Long source=rs.getObject("source_post_id",Long.class);
            return new Conversation(String.valueOf(id),new Recipient(String.valueOf(other),names.isEmpty()?"탈퇴한 사용자":names.get(0)),
                    source==null?null:new Source(String.valueOf(source),rs.getString("source_title")),rs.getString("last_content"),time(rs,"updated_at"),rs.getLong("unread_count"));
        },me,me,id);
    }
    public Slice<Conversation> list(Long me,int page) {
        user(me);
        if(page<0 || page>100000) throw error(HttpStatus.BAD_REQUEST,"페이지 값이 올바르지 않습니다.");
        List<Long> ids=jdbc.query("select id from dm_conversations where user_low=? or user_high=? order by updated_at desc,id desc limit 51 offset ?",
                (rs,n)->rs.getLong(1),me,me,(long)page*50);
        boolean more=ids.size()>50;
        return new Slice<>(ids.stream().limit(50).map(id->conversation(id,me)).toList(),more);
    }
    public Conversation detail(Long me,long id) { participant(id,me,false); return conversation(id,me); }
    public long unread(Long me) { user(me); return count("select count(*) from dm_messages where receiver_id=? and read_at is null",me); }
    @Transactional
    public Conversation start(Long me,StartRequest request) {
        user(me);
        if(request==null || request.recipientId()==null || request.recipientId()<=0 || request.recipientId().equals(me))
            throw error(HttpStatus.BAD_REQUEST,"다른 사용자를 선택해 주세요.");
        long other=request.recipientId(), low=Math.min(me,other), high=Math.max(me,other);
        // 두 사용자를 항상 같은 순서로 잠가 동시 대화 생성의 중복을 방지합니다.
        List<Long> users=jdbc.query("select id from users where id in (?,?) and role='USER' order by id for update",(rs,n)->rs.getLong(1),low,high);
        if(users.size()!=2) throw error(HttpStatus.NOT_FOUND,"대화 상대를 찾을 수 없습니다.");
        String title=null;
        if(request.postId()!=null) {
            List<String> titles=jdbc.query("select p.title from posts p where p.id=? and p.post_type<>'NOTICE' and " +
                    "(p.author_id=? or exists(select 1 from comments c where c.post_id=p.id and c.author_id=?))",(rs,n)->rs.getString(1),request.postId(),other,other);
            if(titles.isEmpty()) throw error(HttpStatus.BAD_REQUEST,"대화 상대와 관련된 게시글을 찾을 수 없습니다.");
            title=titles.get(0);
        }
        jdbc.update("insert into dm_conversations(user_low,user_high,source_post_id,source_title) values (?,?,?,?) on duplicate key update id=id",low,high,request.postId(),title);
        Long id=jdbc.queryForObject("select id from dm_conversations where user_low=? and user_high=?",Long.class,low,high);
        if(request.postId()!=null) jdbc.update("update dm_conversations set source_post_id=?,source_title=? where id=?",request.postId(),title,id);
        events.publishEvent(new Changed(me,other));
        return conversation(id,me);
    }
    private Message message(ResultSet rs,long me) throws SQLException {
        return new Message(String.valueOf(rs.getLong("id")),String.valueOf(rs.getLong("conversation_id")),String.valueOf(rs.getLong("sender_id")),
                rs.getString("content"),time(rs,"created_at"),rs.getLong("sender_id")==me,time(rs,"read_at"),rs.getString("client_id"));
    }
    public Slice<Message> history(Long me,long id,Long before) {
        participant(id,me,false);
        if(before!=null && before<=0) throw error(HttpStatus.BAD_REQUEST,"메시지 기준 값이 올바르지 않습니다.");
        List<Message> rows=jdbc.query("select * from dm_messages where conversation_id=? and id<? order by id desc limit 51",
                (rs,n)->message(rs,me),id,before==null?Long.MAX_VALUE:before);
        boolean more=rows.size()>50;
        List<Message> items=new ArrayList<>(rows.subList(0,Math.min(50,rows.size())));
        Collections.reverse(items);
        return new Slice<>(items,more);
    }
    @Transactional
    public Message send(Long me,long id,SendRequest request) {
        Pair pair=participant(id,me,true);
        String text=request==null || request.content()==null?"":request.content().trim();
        if(text.isEmpty() || text.length()>2000) throw error(HttpStatus.BAD_REQUEST,"메시지는 1~2000자로 입력해 주세요.");
        String client=request.clientId();
        try { if(client==null || !UUID.fromString(client).toString().equalsIgnoreCase(client)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw error(HttpStatus.BAD_REQUEST,"전송 식별자가 올바르지 않습니다."); }
        List<Message> prior=jdbc.query("select * from dm_messages where sender_id=? and client_id=?",(rs,n)->message(rs,me),me,client);
        if(!prior.isEmpty()) {
            Message old=prior.get(0);
            if(!old.conversationId().equals(String.valueOf(id)) || !old.content().equals(text))
                throw error(HttpStatus.CONFLICT,"전송 식별자가 이미 사용되었습니다.");
            return old;
        }
        long other=pair.other(me);
        if(count("select count(*) from users where id=? and role='USER'",other)==0) throw error(HttpStatus.NOT_FOUND,"대화 상대를 찾을 수 없습니다.");
        jdbc.update("insert into dm_messages(conversation_id,sender_id,receiver_id,client_id,content) values (?,?,?,?,?)",id,me,other,client,text);
        jdbc.update("update dm_conversations set updated_at=CURRENT_TIMESTAMP(6) where id=?",id);
        events.publishEvent(new Changed(me,other));
        return jdbc.queryForObject("select * from dm_messages where sender_id=? and client_id=?",(rs,n)->message(rs,me),me,client);
    }
    @Transactional
    public void read(Long me,long id,ReadRequest request) {
        Pair pair=participant(id,me,true);
        if(request==null || request.throughId()==null || request.throughId()<=0 ||
                count("select count(*) from dm_messages where id=? and conversation_id=?",request.throughId(),id)==0)
            throw error(HttpStatus.BAD_REQUEST,"읽은 메시지 기준을 확인해 주세요.");
        int changed=jdbc.update("update dm_messages set read_at=CURRENT_TIMESTAMP(6) where conversation_id=? and receiver_id=? and id<=? and read_at is null",id,me,request.throughId());
        if(changed>0) events.publishEvent(new Changed(me,pair.other(me)));
    }
}