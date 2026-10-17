# 1. 베이스 이미지: 안정적인 Ubuntu 22.04 LTS 사용
FROM ubuntu:22.04

# 2. 메타데이터 설정
LABEL maintainer="my-web-ide-team"
LABEL description="All-in-One Execution Environment for Web IDE"

# 3. 환경 변수 설정
ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# .NET 초기 환영 메시지 및 텔레메트리 제거
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_NOLOGO=1

# 4. 기본 유틸리티, 필수 패키지 및 gdb 설치
# 💡 핵심 수정: software-properties-common을 한 줄로 합쳤습니다!
RUN apt-get update && apt-get install -y \
    locales \
    curl \
    wget \
    git \
    zip \
    unzip \
    software-properties-common \
    build-essential \
    gdb \
    && locale-gen en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

# 5. Java (OpenJDK 17) 설치
RUN apt-get update && apt-get install -y openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

# 6. Python 3 및 PIP, debugpy 설치
RUN apt-get update && apt-get install -y python3 python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install debugpy

# 7. Node.js 18 (LTS) 설치
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# 8. C# (.NET SDK 8.0) 설치
RUN wget https://packages.microsoft.com/config/ubuntu/22.04/packages-microsoft-prod.deb -O packages-microsoft-prod.deb \
    && dpkg -i packages-microsoft-prod.deb \
    && rm packages-microsoft-prod.deb \
    && apt-get update \
    && apt-get install -y dotnet-sdk-8.0 \
    && rm -rf /var/lib/apt/lists/*

# 9. 작업 디렉토리 설정
WORKDIR /app

# 10. 기본 실행 명령
CMD ["/bin/bash"]

# (기존 Dockerfile의 5번 아래에 이 내용을 추가해주세요!)docker build -t ide-execution-env .

# 5.5 Gradle (Spring Boot 빌드용) 설치
RUN wget https://services.gradle.org/distributions/gradle-8.5-bin.zip -P /tmp \
    && unzip -d /opt/gradle /tmp/gradle-8.5-bin.zip \
    && ln -s /opt/gradle/gradle-8.5/bin/gradle /usr/bin/gradle \
    && rm /tmp/gradle-8.5-bin.zip

# 11. 창(Swing) 프로그램용 가짜 모니터와 화면 중계
#
# 컨테이너에는 모니터가 없어서 Swing 창을 띄우면 HeadlessException 으로 죽는다.
#   Xvfb       화면을 메모리에만 그리는 가짜 모니터
#   openbox    창 관리자. 없으면 창에 제목 줄이 없고 글자 입력 칸에 포커스가 안 간다
#   x11vnc     그 화면을 원격 화면(VNC) 신호로 내보낸다
#   novnc      VNC 를 브라우저가 볼 수 있는 웹 페이지로 바꾼다 (IDE 의 미리보기 창에 뜬다)
#   fonts-nanum 한글 글꼴. 없으면 한글이 네모로 나온다
#   xdotool    글자를 대신 친다. 화면 중계는 누른 키만 보내 한글이 조합되지 않으므로,
#              앱 화면의 한글 입력 칸에서 받은 글자를 이것으로 창에 넣는다(DesktopTyping)
RUN apt-get update && apt-get install -y \
    xvfb \
    openbox \
    x11vnc \
    novnc \
    websockify \
    fonts-nanum \
    xdotool \
    && rm -rf /var/lib/apt/lists/*

# 화면을 켜는 스크립트. 서버(RunService)가 창을 쓰는 프로그램을 실행하기 직전에 부른다.
# 6080 은 DockerService.GUI_PORT 와 같아야 한다.
# 백그라운드로 띄우는 것들의 출력은 버린다. 붙들고 있으면 이 스크립트를 부른 쪽이 끝나지 않는다.
RUN printf '%s\n' \
    '#!/bin/sh' \
    '[ -e /tmp/.X11-unix/X99 ] && exit 0' \
    'Xvfb :99 -screen 0 1024x640x24 -nolisten tcp >/dev/null 2>&1 &' \
    'i=0' \
    'while [ ! -e /tmp/.X11-unix/X99 ] && [ "$i" -lt 50 ]; do sleep 0.1; i=$((i + 1)); done' \
    '[ -e /tmp/.X11-unix/X99 ] || exit 1' \
    'DISPLAY=:99 openbox >/dev/null 2>&1 &' \
    'x11vnc -display :99 -forever -shared -nopw -quiet -rfbport 5900 >/dev/null 2>&1 &' \
    'websockify --web /usr/share/novnc 6080 localhost:5900 >/dev/null 2>&1 &' \
    'exit 0' \
    > /usr/local/bin/ide-gui-start \
    && chmod +x /usr/local/bin/ide-gui-start

# 12. 스프링부트 실행 빨리 시작하기
#
# 실행할 때마다 새 컨테이너라 gradle 이 스프링부트 라이브러리(수백 MB)를 인터넷에서 처음부터
# 받았다. 실행 한 번에 4분 넘게 걸렸고 거의 전부가 이 다운로드였다. 이미지를 만들 때 템플릿
# (SpringBootTemplateStrategy)과 같은 build.gradle 로 한 번 빌드해 두면 라이브러리가 이미지
# 안 /root/.gradle 에 남는다. 컨테이너마다 그 사본을 쓰므로, 여러 컨테이너가 캐시 하나를 같이
# 쓸 때와 달리 누군가의 코드가 캐시를 바꿔도 다른 실행에 번지지 않는다.
# 템플릿의 스프링부트 버전을 바꾸면 여기도 같이 바꾸고 이미지를 다시 빌드해야 빨라진다.
#
# gradle 일꾼(데몬)은 실행마다 새로 켜지는데, 켜질 때 최적화를 덜 하게(TieredStopAtLevel=1)
# 하면 첫 빌드가 17초에서 12초 정도로 준다. 빌드 도구라 오래 돌지 않아서 손해가 없다.
RUN mkdir -p /root/.gradle /tmp/warm/src/main/java/com/example/demo \
    && printf '%s\n' 'org.gradle.jvmargs=-Xmx1g -XX:TieredStopAtLevel=1 -XX:+UseParallelGC' \
        > /root/.gradle/gradle.properties \
    && printf "plugins { id 'org.springframework.boot' version '3.2.0'; id 'io.spring.dependency-management' version '1.1.4'; id 'java' }\nrepositories { mavenCentral() }\ndependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }\n" \
        > /tmp/warm/build.gradle \
    && printf '%s\n' 'package com.example.demo;' \
        '@org.springframework.boot.autoconfigure.SpringBootApplication' \
        'public class DemoApplication { public static void main(String[] a) { org.springframework.boot.SpringApplication.run(DemoApplication.class, a); } }' \
        > /tmp/warm/src/main/java/com/example/demo/DemoApplication.java \
    && cd /tmp/warm && gradle compileJava bootJar --no-daemon -q \
    && cd / && rm -rf /tmp/warm /root/.gradle/daemon
