package com.myide.backend.service.design.codegen;

import com.myide.backend.dto.design.v2.ApiSpecV2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표준 CRUD 판별.
 *
 * 이 판별이 틀리면 엉뚱한 자리에 엉뚱한 몸통이 들어간다. 실제로 처음 구현에서
 * POST /api/users/login 을 "회원 생성" 으로 보아 로그인 자리에 save 를 넣었다.
 * 그래서 "아닌 것을 아니라고 하는" 경우를 특히 촘촘히 둔다.
 */
class CrudShapeTest {

    private ApiSpecV2 api(String method, String endpoint, List<String> tableIds) {
        return new ApiSpecV2("api_1", method, endpoint, "", "", "",
                false, "R", List.of(), List.of(), tableIds);
    }

    private ApiSpecV2 api(String method, String endpoint) {
        return api(method, endpoint, List.of("tbl_user"));
    }

    @Test
    @DisplayName("묶음 경로와 개별 경로를 CRUD 로 알아본다")
    void recognizesStandardShapes() {
        assertThat(CrudShape.of(api("GET", "/api/users")).kind())
                .isEqualTo(CrudShape.Kind.FIND_ALL);
        assertThat(CrudShape.of(api("GET", "/api/users/{id}")).kind())
                .isEqualTo(CrudShape.Kind.FIND_ONE);
        assertThat(CrudShape.of(api("POST", "/api/users")).kind())
                .isEqualTo(CrudShape.Kind.CREATE);
        assertThat(CrudShape.of(api("PUT", "/api/users/{id}")).kind())
                .isEqualTo(CrudShape.Kind.UPDATE);
        assertThat(CrudShape.of(api("PATCH", "/api/users/{id}")).kind())
                .isEqualTo(CrudShape.Kind.UPDATE);
        assertThat(CrudShape.of(api("DELETE", "/api/users/{id}")).kind())
                .isEqualTo(CrudShape.Kind.DELETE);
    }

    @Test
    @DisplayName("끝이 동작 이름이면 표준이 아니다")
    void actionPathIsNotStandard() {
        // 이것을 CREATE 로 보면 로그인 자리에 "회원 하나 저장" 이 들어간다.
        assertThat(CrudShape.of(api("POST", "/api/users/login")).isStandard()).isFalse();
        assertThat(CrudShape.of(api("POST", "/api/orders/{id}/cancel")).isStandard()).isFalse();
        assertThat(CrudShape.of(api("GET", "/api/users/{id}/posts")).isStandard()).isFalse();
    }

    @Test
    @DisplayName("경로 변수가 둘 이상이면 표준이 아니다")
    void nestedPathIsNotStandard() {
        assertThat(CrudShape.of(api("GET", "/api/posts/{postId}/comments/{commentId}")).isStandard())
                .isFalse();
    }

    @Test
    @DisplayName("테이블 연결이 없으면 표준으로 보지 않는다")
    void withoutTableLinkIsNotStandard() {
        // 연결이 값을 갖는 지점이 여기다. 이어야 몸통이 채워진다.
        assertThat(CrudShape.of(api("GET", "/api/users/{id}", List.of())).isStandard()).isFalse();
        assertThat(CrudShape.of(api("GET", "/api/users", List.of())).isStandard()).isFalse();
    }

    @Test
    @DisplayName("경로가 비어 있으면 표준이 아니다")
    void blankEndpointIsNotStandard() {
        assertThat(CrudShape.of(api("GET", "")).isStandard()).isFalse();
        assertThat(CrudShape.of(null).isStandard()).isFalse();
    }
}
