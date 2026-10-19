package com.myide.backend.service.template;

import java.util.Optional;
import java.util.Set;

/**
 * 스프링부트 application.yml 의 DB 설정.
 *
 * JPA 를 넣으면 스프링부트는 시작할 때 붙을 DB 를 찾고, 설정이 없으면 서버가 뜨다가 죽는다.
 *
 * H2 를 기본으로 쓴다. 프로그램 안에 들어 있는 메모리 DB 라서 실행 컨테이너 안에서 설치하거나
 * 접속할 것이 없어, 로컬이든 AWS 든 바로 돈다. AWS 서버의 MySQL 은 IDE 자신의 데이터가 든 DB 라
 * 사용자 코드에 접속 정보를 주면 안 되고, 실행마다 MySQL 을 따로 띄우기에는 서버 메모리가 모자란다.
 *
 * H2 웹 콘솔은 켜지 않는다. 밖에서 접속하게 열면 SQL 로 컨테이너 안에서 아무 코드나 돌릴 수 있는데,
 * 미리보기 주소는 인터넷에 열려 있다.
 */
public final class SpringAppConfig {

    public static final String PATH = "src/main/resources/application.yml";

    private SpringAppConfig() {
    }

    /** 고른 의존성에 DB 설정이 필요할 때만 내용을 준다. JPA 가 없으면 DB 에 붙지 않으므로 필요 없다. */
    public static Optional<String> render(Set<SpringDependency> dependencies) {
        if (!dependencies.contains(SpringDependency.JPA)) {
            return Optional.empty();
        }
        if (dependencies.contains(SpringDependency.H2)) {
            return Optional.of(h2());
        }
        if (dependencies.contains(SpringDependency.MYSQL)) {
            return Optional.of(mysql());
        }
        return Optional.empty();
    }

    private static String h2() {
        return """
                # IDE 가 만든 DB 설정입니다. 필요하면 자유롭게 고쳐도 됩니다.
                spring:
                  datasource:
                    # H2: 프로그램 안에 들어 있는 메모리 DB 라 따로 설치할 것이 없습니다.
                    # 실행을 멈추면 데이터가 사라집니다. MODE=MySQL 은 MySQL 문법을 흉내 내 나중에 옮기기 쉽게 합니다.
                    url: jdbc:h2:mem:appdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
                    username: sa
                    password:
                  jpa:
                    hibernate:
                      # 실행할 때마다 엔티티를 보고 표를 새로 만듭니다.
                      ddl-auto: create
                    show-sql: true

                # MySQL 로 바꾸려면 build.gradle 에 runtimeOnly 'com.mysql:mysql-connector-j' 를 넣고
                # 위 datasource 를 아래처럼 바꿉니다. 실행 컨테이너 안의 localhost 는 컨테이너 자신이라
                # MySQL 서버의 실제 주소를 적어야 합니다.
                #    url: jdbc:mysql://DB서버주소:3306/DB이름?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
                #    username: 계정
                #    password: 비밀번호
                # 그리고 ddl-auto 는 update 로 바꿔야 실행할 때마다 데이터가 지워지지 않습니다.
                """;
    }

    private static String mysql() {
        return """
                # IDE 가 만든 DB 설정입니다. 접속할 MySQL 의 주소·DB 이름·계정을 채워야 실행됩니다.
                # 실행 컨테이너 안의 localhost 는 컨테이너 자신이라 MySQL 서버의 실제 주소를 적어야 합니다.
                spring:
                  datasource:
                    url: jdbc:mysql://DB서버주소:3306/DB이름?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
                    username: 계정
                    password: 비밀번호
                  jpa:
                    hibernate:
                      # 엔티티에 맞춰 표를 만들거나 고치되, 있는 데이터는 지우지 않습니다.
                      ddl-auto: update
                    show-sql: true
                """;
    }
}
