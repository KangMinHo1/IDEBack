package com.myide.backend.domain;

import lombok.Getter;

@Getter
public enum LanguageType {
    // {file} 은 DockerService에서 실제 파일명(예: main.c)으로 치환됩니다.

    JAVA("javac -encoding UTF-8 *.java && java -cp . Main"), // Java는 보통 Main 클래스 실행
    PYTHON("python3 {file}"),
    JAVASCRIPT("node {file}"), // 기존 index.js 고정에서 -> {file}로 변경
    CPP("g++ -o main *.cpp && ./main"),
    C("gcc -o main *.c && ./main"),
    // C#은 dotnet run을 쓰면 프로젝트 파일을 알아서 찾음 (csproj 필요)
    CSHARP("dotnet run"),
    // HTML은 터미널에서 결과만 출력
    HTML("cat {file}");

    private final String runCommand;

    LanguageType(String runCommand) {
        this.runCommand = runCommand;
    }
}