package com.myide.backend.service.design.codegen;

import com.myide.backend.dto.design.v2.ApiSpecV2;

import java.util.ArrayList;
import java.util.List;

/**
 * API 하나가 표준 CRUD 모양인지 가려낸다.
 *
 * 왜 이 구분이 필요한가
 * -------------------
 * 예전에는 컨트롤러 몸통을 전부 비워 두고 "TODO: 구현해 주세요" 만 남겼다. 그 판단에는
 * 이유가 있었다 - 무엇을 어떻게 저장할지는 사람이 정할 일이고, 지어낸 구현이 들어 있으면
 * 오히려 지우는 데 시간이 든다.
 *
 * 다만 그 말이 맞는 것은 비즈니스 로직뿐이다. "회원 하나 조회", "회원 목록", "회원 저장"
 * 같은 것은 기계가 확실히 알 수 있고 사람이 매번 똑같이 쓴다. 현업에서 쓰는 생성기들도
 * (JHipster, Rails scaffold 같은 것) 정확히 이 선을 긋는다 - 표준 CRUD 는 끝까지 만들고
 * 그 밖은 손대지 않는다.
 *
 * AI 로 몸통을 짓지 않는 이유도 같다. 지어낸 구현은 검토하고 지우는 비용이 직접 쓰는
 * 비용보다 크다. 이 프로젝트에는 AI 초안 기능이 따로 있어 역할도 겹친다.
 *
 * 판별을 일부러 좁게 잡는다
 * ----------------------
 * 애매하면 표준이 아니라고 본다. 잘못 표준으로 보아 엉뚱한 몸통을 넣는 것이, 스텁으로
 * 두고 사람이 채우는 것보다 훨씬 나쁘기 때문이다.
 *
 * 그래서 연결된 테이블이 없으면 무조건 아니다. 이 규칙이 "테이블을 이어 두면 몸통이
 * 채워지고, 안 이으면 스텁으로 남는다" 를 만든다 - 연결을 손으로 잇는 일이 값을 갖는
 * 지점이 여기다.
 */
public record CrudShape(Kind kind, String tableId) {

    public enum Kind {
        /** GET /api/users */
        FIND_ALL,
        /** GET /api/users/{id} */
        FIND_ONE,
        /** POST /api/users */
        CREATE,
        /** PUT|PATCH /api/users/{id} */
        UPDATE,
        /** DELETE /api/users/{id} */
        DELETE,
        /** 그 밖의 모든 것. 몸통을 만들지 않는다. */
        NONE
    }

    private static final CrudShape NOT_STANDARD = new CrudShape(Kind.NONE, null);

    public boolean isStandard() {
        return kind != Kind.NONE;
    }

    public static CrudShape of(ApiSpecV2 api) {
        if (api == null || api.endpoint().isBlank() || api.tableIds().isEmpty()) {
            return NOT_STANDARD;
        }

        List<String> segments = segmentsOf(api.endpoint());

        if (segments.isEmpty()) {
            return NOT_STANDARD;
        }

        String tableId = api.tableIds().get(0);
        String last = segments.get(segments.size() - 1);
        String resource = NameMapper.resourceOf(api.endpoint());

        long paramCount = segments.stream().filter(CrudShape::isParam).count();

        /*
         * 경로가 딱 두 모양일 때만 표준으로 본다.
         *
         *   묶음 경로 : .../users        (끝이 리소스 이름 그 자체)
         *   개별 경로 : .../users/{id}   (끝이 경로 변수이고 그 앞이 리소스 이름)
         *
         * 개수만 세면 안 된다. /api/users/login 은 경로 변수가 없어서 "묶음 조회"로
         * 보이지만 끝이 login 이라 동작 이름이다. 이것을 CREATE 로 보면 로그인 자리에
         * "회원 하나 저장" 코드가 들어간다. 실제로 그런 일이 있었다.
         *
         * /api/orders/{id}/cancel 도 경로 변수가 하나지만 끝이 cancel 이라 아니다.
         */
        boolean collectionPath = last.equals(resource);
        boolean itemPath = isParam(last)
                && segments.size() >= 2
                && segments.get(segments.size() - 2).equals(resource);

        return switch (api.method()) {
            case "GET" -> {
                if (paramCount == 0 && collectionPath) {
                    yield new CrudShape(Kind.FIND_ALL, tableId);
                }
                yield paramCount == 1 && itemPath
                        ? new CrudShape(Kind.FIND_ONE, tableId)
                        : NOT_STANDARD;
            }
            case "POST" -> paramCount == 0 && collectionPath
                    ? new CrudShape(Kind.CREATE, tableId)
                    : NOT_STANDARD;
            case "PUT", "PATCH" -> paramCount == 1 && itemPath
                    ? new CrudShape(Kind.UPDATE, tableId)
                    : NOT_STANDARD;
            case "DELETE" -> paramCount == 1 && itemPath
                    ? new CrudShape(Kind.DELETE, tableId)
                    : NOT_STANDARD;
            default -> NOT_STANDARD;
        };
    }

    private static List<String> segmentsOf(String endpoint) {
        List<String> segments = new ArrayList<>();

        for (String part : endpoint.split("/")) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }

        return segments;
    }

    private static boolean isParam(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }
}
