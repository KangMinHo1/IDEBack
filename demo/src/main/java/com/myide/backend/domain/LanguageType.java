package com.myide.backend.domain;

import lombok.Getter;

@Getter
public enum LanguageType {

    JAVA("java", "Main.java",
            "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello Java World!\");\n    }\n}",
            "rm -f src/Main.class && javac -g -encoding UTF-8 src/Main.java && stdbuf -i0 -oL -eL java -cp src Main"),

    PYTHON("py", "main.py",
            "print('Hello Python World!')",
            "python3 -u src/main.py"),

    JAVASCRIPT("js", "index.js",
            "console.log('Hello Node.js World!');",
            "stdbuf -i0 -oL -eL node src/index.js"),

    CPP("cpp", "main.cpp",
            "#include <iostream>\nint main() {\n    std::cout << \"Hello C++\" << std::endl;\n    return 0;\n}",
            "rm -f src/main && g++ -g src/main.cpp -o src/main && stdbuf -i0 -oL -eL src/main"),

    C("c", "main.c",
            "#include <stdio.h>\nint main() {\n    printf(\"Hello C\");\n    return 0;\n}",
            "rm -f src/main && gcc -g src/main.c -o src/main && stdbuf -i0 -oL -eL src/main"),

    // [수정] 빌드 폴더(bin, obj) 청소 후 실행
    CSHARP("cs", "Program.cs",
            "using System;\nclass Program {\n    static void Main() {\n        Console.WriteLine(\"Hello C# World!\");\n    }\n}",
            "rm -rf src/bin src/obj && dotnet run --project src -v q");

    private final String extension;
    private final String mainFileName;
    private final String defaultCode;
    private final String runCommand;

    LanguageType(String extension, String mainFileName, String defaultCode, String runCommand) {
        this.extension = extension;
        this.mainFileName = mainFileName;
        this.defaultCode = defaultCode;
        this.runCommand = runCommand;
    }
}