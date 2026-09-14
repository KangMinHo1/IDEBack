package com.myide.backend.service.design.codegen;

import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignMetaV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ErdV2;
import com.myide.backend.dto.design.v2.PointV2;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.dto.design.v2.TechStackV2;

import java.util.List;

/**
 * 코드 생성 검사에서 함께 쓰는 설계 한 벌.
 *
 * 게시판을 아주 작게 줄인 모양이다. 사용자와 글, 그 둘을 잇는 외래키,
 * 경로 변수가 있는 화면과 API 가 하나씩 들어 있어서, 코드 생성에서 실제로
 * 틀리기 쉬운 곳을 모두 지나간다.
 */
final class CodegenFixtures {

    private CodegenFixtures() {
    }

    static DesignModelV2 board() {
        ColumnV2 userId = new ColumnV2("col_u1", "id", "BIGINT", null,
                false, true, false, "", "사용자 번호");
        ColumnV2 userEmail = new ColumnV2("col_u2", "email", "VARCHAR", 255,
                false, false, false, "", "로그인 이메일");
        ColumnV2 userJoinedAt = new ColumnV2("col_u3", "joined_at", "DATETIME", null,
                true, false, false, "", "");

        ColumnV2 postId = new ColumnV2("col_p1", "id", "BIGINT", null,
                false, true, false, "", "");
        ColumnV2 postTitle = new ColumnV2("col_p2", "title", "VARCHAR", 200,
                false, false, false, "", "제목");
        ColumnV2 postBody = new ColumnV2("col_p3", "body", "TEXT", null,
                true, false, false, "", "");
        ColumnV2 postAuthor = new ColumnV2("col_p4", "author_id", "BIGINT", null,
                false, false, true, "", "글쓴이");

        TableV2 users = new TableV2("tbl_user", "users", "User",
                "서비스를 쓰는 사람", List.of(userId, userEmail, userJoinedAt),
                new PointV2(40, 40));
        TableV2 posts = new TableV2("tbl_post", "posts", "",
                "사용자가 쓴 글", List.of(postId, postTitle, postBody, postAuthor),
                new PointV2(400, 40));

        RelationV2 relation = new RelationV2("rel_1", "tbl_post", "col_p4",
                "tbl_user", "col_u1", "1:N", "CASCADE", "글쓴이");

        RequirementV2 login = new RequirementV2("req_1", "R-01", "회원",
                "로그인", "이메일과 비밀번호로 로그인한다", "must",
                List.of("scr_login"), List.of("api_login"));
        RequirementV2 read = new RequirementV2("req_2", "R-02", "게시판",
                "글 읽기", "글 하나를 자세히 본다", "must",
                List.of("scr_detail"), List.of("api_post"));

        ScreenV2 loginScreen = new ScreenV2("scr_login", "/login", "로그인 화면",
                "이메일과 비밀번호를 넣는다", "page", true, false,
                List.of("req_1"), List.of("api_login"), new PointV2(40, 40));
        ScreenV2 detailScreen = new ScreenV2("scr_detail", "/posts/:postId", "글 상세",
                "글 하나를 본다", "page", false, true,
                List.of("req_2"), List.of("api_post"), new PointV2(400, 40));

        ScreenTransitionV2 transition = new ScreenTransitionV2("trn_1", "scr_login",
                "scr_detail", "로그인 버튼 클릭", "submit", "", List.of("api_login"));

        ApiSpecV2 loginApi = new ApiSpecV2("api_login", "POST", "/api/users/login",
                "로그인한다",
                "{\"email\": \"a@b.com\", \"password\": \"1234\"}",
                "{\"token\": \"abc\", \"userId\": 1}",
                false, "R", List.of("req_1"), List.of("scr_login"), List.of("tbl_user"));

        ApiSpecV2 postApi = new ApiSpecV2("api_post", "GET", "/api/posts/{postId}",
                "글 하나를 가져온다", "", "설명만 적혀 있고 JSON 이 아니다",
                true, "R", List.of("req_2"), List.of("scr_detail"), List.of("tbl_post"));

        /*
         * 표준 CRUD 가 아닌 API. 끝이 경로 변수가 아니라 동작 이름(like)이다.
         * 이런 것은 몸통을 만들지 않고 스텁으로 두어야 하며, 예시가 JSON 이 아닐 때
         * Map 으로 물러서는 길도 이 API 가 지킨다.
         */
        ApiSpecV2 likeApi = new ApiSpecV2("api_like", "POST", "/api/posts/{postId}/like",
                "글에 좋아요를 누른다", "", "좋아요 수를 돌려준다. JSON 이 아니다",
                true, "U", List.of("req_2"), List.of("scr_detail"), List.of("tbl_post"));

        return new DesignModelV2(2,
                new DesignMetaV2("작은 게시판",
                        new TechStackV2("Spring Boot", "React", "MySQL"), null),
                List.of(login, read),
                List.of(loginScreen, detailScreen),
                List.of(transition),
                List.of(loginApi, postApi, likeApi),
                new ErdV2(List.of(users, posts), List.of(relation)));
    }
}
