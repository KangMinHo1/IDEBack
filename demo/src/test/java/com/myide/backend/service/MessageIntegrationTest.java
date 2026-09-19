package com.myide.backend.service;

import com.myide.backend.dto.ReportDto;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// 실행 전 database SQL을 backend/src/test/resources/message-schema.sql로 복사합니다.
class MessageIntegrationTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    DirectMessageService dm;
    CommunityInteractionService interactions;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table users(id bigint primary key,nickname varchar(30),role varchar(20))");
        jdbc.execute("create table posts(id bigint primary key,author_id bigint,title varchar(200),post_type varchar(20),like_count int default 0,scrap_count int default 0)");
        jdbc.execute("create table comments(id bigint primary key,post_id bigint,author_id bigint)");
        for(String table:List.of("post_likes","post_scraps")) jdbc.execute("create table "+table+"(post_id bigint,user_id bigint,created_at timestamp,updated_at timestamp,unique(post_id,user_id))");
        try(var input=getClass().getResourceAsStream("/message-schema.sql")) {
            assertNotNull(input,"message-schema.sql을 test/resources에 복사하세요.");
            String sql=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(String statement:sql.replaceAll("(?m)^--.*$","").split(";")) if(!statement.isBlank())jdbc.execute(statement);
        }
        jdbc.update("insert into users values(1,'첫째','USER'),(2,'둘째','USER'),(3,'셋째','USER')");
        jdbc.update("insert into posts(id,author_id,title,post_type) values(10,2,'글','NORMAL'),(20,3,'다른 글','NORMAL')");
        jdbc.update("insert into comments values(100,10,2),(200,20,3)");
        dm=new DirectMessageService(jdbc,event->{});interactions=new CommunityInteractionService(jdbc);
    }
    long room(){return tx.execute(s->Long.valueOf(dm.start(1L,new DirectMessageService.StartRequest(2L,10L)).id()));}
    DirectMessageService.Message send(long id,String text,String key){return tx.execute(s->dm.send(1L,id,new DirectMessageService.SendRequest(text,key)));}
    @Test void pairIsUniqueAndRetryDoesNotDuplicate() {
        long id=room();long reverse=tx.execute(s->Long.valueOf(dm.start(2L,new DirectMessageService.StartRequest(1L,null)).id()));assertEquals(id,reverse);
        String key=UUID.randomUUID().toString();var first=send(id,"안녕",key);assertEquals(first.id(),send(id,"안녕",key).id());
        assertEquals(1,dm.history(2L,id,null).items().size());
        assertThrows(ResponseStatusException.class,()->send(id,"다른 내용",key));
    }
    @Test void nonParticipantCannotReadSendOrMarkRead() {
        long id=room();var m=send(id,"비공개",UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class,()->dm.detail(3L,id));
        assertThrows(ResponseStatusException.class,()->dm.history(3L,id,null));
        assertThrows(ResponseStatusException.class,()->tx.execute(s->dm.send(3L,id,new DirectMessageService.SendRequest("침입",UUID.randomUUID().toString()))));
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(s->dm.read(3L,id,new DirectMessageService.ReadRequest(Long.valueOf(m.id())))));
        assertEquals(0,dm.list(3L,0).items().size());
    }
    @Test void readOnlyMarksReceiverMessagesThroughCursor() {
        long id=room();var a=send(id,"하나",UUID.randomUUID().toString());send(id,"둘",UUID.randomUUID().toString());
        assertEquals(2,dm.unread(2L));
        tx.executeWithoutResult(s->dm.read(2L,id,new DirectMessageService.ReadRequest(Long.valueOf(a.id()))));
        assertEquals(1,dm.unread(2L));assertEquals(0,dm.unread(1L));
    }
    @Test void historyPaginationHasNoGap() {
        long id=room();for(int i=0;i<55;i++)send(id,"메시지 "+i,UUID.randomUUID().toString());
        var latest=dm.history(1L,id,null);assertEquals(50,latest.items().size());assertTrue(latest.hasMore());
        var older=dm.history(1L,id,Long.valueOf(latest.items().get(0).id()));assertEquals(5,older.items().size());assertFalse(older.hasMore());
        assertTrue(Long.parseLong(older.items().get(4).id())<Long.parseLong(latest.items().get(0).id()));
    }
    @Test void commentMustBelongToPostAndDeleteCascades() {
        assertThrows(ResponseStatusException.class,()->tx.execute(s->interactions.toggleComment(10L,200L,1L)));
        tx.execute(s->interactions.toggleComment(10L,100L,1L));
        assertEquals(1,interactions.commentStates(10L,List.of(100L),1L).get(0).likeCount());
        jdbc.update("delete from comments where id=100");
        assertEquals(0,jdbc.queryForObject("select count(*) from comment_likes",Integer.class));
    }
    @Test void simultaneousToggleSerializes() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->tx.execute(s->interactions.togglePost(10L,1L,false)));
            var b=pool.submit(()->tx.execute(s->interactions.togglePost(10L,1L,false)));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(0,interactions.state(10L,1L).likeCount());
        } finally {pool.shutdownNow();}
    }
    @Test void commentReportsRejectSelfAndDuplicate() throws Exception {
        ReportDto.CreateRequest request=new ReportDto.CreateRequest();
        var field=request.getClass().getDeclaredField("reason");field.setAccessible(true);
        field.set(request,field.getType().getEnumConstants()[0]);
        assertThrows(ResponseStatusException.class,()->tx.execute(s->interactions.reportComment(10L,100L,2L,request)));
        tx.execute(s->interactions.reportComment(10L,100L,1L,request));
        assertThrows(ResponseStatusException.class,()->tx.execute(s->interactions.reportComment(10L,100L,1L,request)));
        assertTrue(interactions.commentStates(10L,List.of(100L),1L).get(0).reported());
    }
    @Test void deletingSourceKeepsConversation() {
        long id=room();send(id,"보존",UUID.randomUUID().toString());
        jdbc.update("delete from posts where id=10");
        assertNull(dm.detail(1L,id).source());assertEquals(1,dm.history(1L,id,null).items().size());
    }
}