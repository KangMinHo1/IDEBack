package com.myide.backend.domain;

import lombok.Getter;

@Getter
public enum LanguageType {
    JAVA(
            "javac -encoding UTF-8 *.java && java -cp . $(basename {file} .java)",
            "javac -encoding UTF-8 *.java && jar cfe {output} Main *.class",
            "Main.java",
            "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello Java!\");\n    }\n}",
            "{project}.jar"
    ),
    PYTHON(
            "python3 -u {file}",
            null,
            "main.py",
            "print('Hello Python!')",
            null
    ),
    JAVASCRIPT(
            "node {file}",
            null,
            "index.js",
            "console.log('Hello JavaScript!');",
            null
    ),
    CPP(
            "g++ -o $(basename {file} .cpp) *.cpp && ./$(basename {file} .cpp)",
            "g++ -o {output} *.cpp",
            "main.cpp",
            "#include <iostream>\n\nint main() {\n    std::cout << \"Hello C++!\" << std::endl;\n    return 0;\n}",
            "{project}.exe" // 💡 기존 "main" 대신 이렇게 변경!
    ),
    C(
            "gcc -o $(basename {file} .c) *.c && ./$(basename {file} .c)",
            "gcc -o {output} *.c",
            "main.c",
            "#include <stdio.h>\n\nint main() {\n    printf(\"Hello C!\\n\");\n    return 0;\n}",
            "{project}.exe" // 💡 기존 "main" 대신 이렇게 변경!
    ),
    CSHARP(
            "dotnet run",
            "dotnet build -o ./build_output",
            "Program.cs",
            "using System;\n\nclass Program {\n    static void Main() {\n        Console.WriteLine(\"Hello C#!\");\n    }\n}",
            "build_output"
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
    private final String outputFileName;

    LanguageType(String runCommand, String buildCommand, String defaultFileName, String defaultCode, String outputFileName) {
        this.runCommand = runCommand;
        this.buildCommand = buildCommand;
        this.defaultFileName = defaultFileName;
        this.defaultCode = defaultCode;
        this.outputFileName = outputFileName;
    }
}