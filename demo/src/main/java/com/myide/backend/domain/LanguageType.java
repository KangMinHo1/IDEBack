package com.myide.backend.domain;

import lombok.Getter;

@Getter
public enum LanguageType {
    JAVA(
            "java -cp . Main",
            // {output}은 나중에 "프로젝트명.jar"로 바뀔 겁니다.
            "javac -encoding UTF-8 *.java && jar cfe {output} Main *.class",
            "Main.java",
            "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello Java!\");\n    }\n}",
            "{project}.jar" // 다운로드될 최종 결과물 이름 (예: MyProject.jar)
    ),
    PYTHON(
            "python3 {file}",
            null, // 스크립트 언어는 빌드 불필요
            "main.py",
            "print('Hello Python!')",
            null // 결과물 파일도 없음
    ),
    JAVASCRIPT(
            "node {file}",
            null,
            "index.js",
            "console.log('Hello JavaScript!');",
            null
    ),
    CPP(
            "./{output}",
            "g++ -o {output} *.cpp", // {output}이 "main"으로 바뀔 겁니다.
            "main.cpp",
            "#include <iostream>\n\nint main() {\n    std::cout << \"Hello C++!\" << std::endl;\n    return 0;\n}",
            "main" // 리눅스는 보통 실행파일에 확장자가 없습니다.
    ),
    C(
            "./{output}",
            "gcc -o {output} *.c",
            "main.c",
            "#include <stdio.h>\n\nint main() {\n    printf(\"Hello C!\\n\");\n    return 0;\n}",
            "main"
    ),
    CSHARP(
            "dotnet run",
            // C#은 보통 폴더 단위로 빌드되므로, 특정 폴더를 결과물로 지정
            "dotnet build -o ./build_output",
            "Program.cs",
            "using System;\n\nclass Program {\n    static void Main() {\n        Console.WriteLine(\"Hello C#!\");\n    }\n}",
            "build_output" // 캡스톤 수준에서는 폴더 이름을 리턴하도록 단순화
    ),
    HTML(
            "cat {file}",
            null,
            "index.html",
            "<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>Hello HTML</title>\n</head>\n<body>\n    <h1>Hello HTML!</h1>\n</body>\n</html>",
            null
    );

    private final String runCommand;
    private final String buildCommand;
    private final String defaultFileName;
    private final String defaultCode;
    private final String outputFileName; // [New] 빌드 결과물의 이름!

    LanguageType(String runCommand, String buildCommand, String defaultFileName, String defaultCode, String outputFileName) {
        this.runCommand = runCommand;
        this.buildCommand = buildCommand;
        this.defaultFileName = defaultFileName;
        this.defaultCode = defaultCode;
        this.outputFileName = outputFileName;
    }
}