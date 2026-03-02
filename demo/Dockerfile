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
RUN apt-get update && apt-get install -y \
    locales \
    curl \
    wget \
    git \
    zip \
    unzip \
    software-properties \
    -common \
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