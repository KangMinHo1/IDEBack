package com.myide.backend.service.design.codegen;

/**
 * 무엇을 만들 것인가.
 *
 * 언어를 늘리지 않는다. 시연 대상이 Spring Boot 와 React 두 가지이고,
 * 어설프게 여러 언어를 지원하면 어느 것도 실제로 컴파일되지 않는다.
 */
public enum CodegenTarget {

    /*
     * 선언 순서가 곧 생성 순서이자 미리보기의 계층별 표시 순서다.
     * 서비스는 리포지토리를 쓰고 컨트롤러가 서비스를 쓰므로 그 사이에 둔다.
     */
    SPRING_ENTITY(ProjectStack.SPRING, "Entity"),
    SPRING_REPOSITORY(ProjectStack.SPRING, "Repository"),
    SPRING_SERVICE(ProjectStack.SPRING, "Service"),
    SPRING_CONTROLLER_DTO(ProjectStack.SPRING, "Controller / DTO"),
    DDL(ProjectStack.SPRING, "테이블 생성 SQL"),

    REACT_ROUTE(ProjectStack.REACT, "라우트"),
    REACT_PAGE(ProjectStack.REACT, "화면 뼈대"),
    REACT_API_CLIENT(ProjectStack.REACT, "API 호출 함수");

    private final ProjectStack stack;
    private final String label;

    CodegenTarget(ProjectStack stack, String label) {
        this.stack = stack;
        this.label = label;
    }

    public ProjectStack stack() {
        return stack;
    }

    public String label() {
        return label;
    }
}
