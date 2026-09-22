package com.myide.backend.service;

import com.myide.backend.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;

/*
 * 사용자마다 하나씩 주어지는 개인 폴더와, 그 폴더를 감싸는 "가상 경로"를 다룬다.
 *
 * 왜 가상 경로가 필요한가
 * ----------------------
 * 프로젝트를 만들 때 사용자는 저장 위치를 폴더 탐색기에서 고른다. 그런데 파일이
 * 실제로 놓이는 곳은 접속자 PC 가 아니라 이 서버의 디스크다. 서버 디스크를 그대로
 * 보여 주면 남의 작업물은 물론 OS 폴더까지 전부 드러난다.
 *
 * 그래서 사용자마다 C:/WebIDE/users/{userId} 를 하나씩 주고, 화면에는 그 폴더가
 * 마치 C:\ 인 것처럼 보여 준다. 사용자는 디스크를 통째로 쓰는 것처럼 느끼지만
 * 실제로는 자기 폴더 밖으로 나갈 수 없다. FTP 서버나 웹호스팅이 오래 쓰던 방식이다.
 *
 * (아래 표기에 슬래시를 쓴 이유: 자바는 주석 안에서도 역슬래시 다음의 u 를
 *  유니코드 이스케이프로 읽어 버린다. users 앞에 역슬래시를 두면 컴파일이 깨진다.)
 *
 *   화면에 보이는 것        실제 경로
 *   C:\                 -> C:/WebIDE/users/42
 *   C:\수업\쇼핑몰        -> C:/WebIDE/users/42/수업/쇼핑몰
 *   C:\..\..\Windows    -> 거부
 *
 * 변환을 반드시 서버에서 하는 이유
 * ------------------------------
 * 프론트에서 변환하면 브라우저 개발자도구로 요청을 고쳐 진짜 경로를 그대로 보낼 수
 * 있다. 그러면 가둬 두는 의미가 사라진다. 진짜 경로는 이 클래스 밖으로 나가지 않고,
 * 클라이언트와는 가상 경로로만 주고받는다.
 */
@Service
@RequiredArgsConstructor
public class UserSpaceService {

    /* 개인 폴더들이 모여 사는 곳. 워크스페이스 기본 루트와 나란히 둔다.
     * 로컬(Windows)과 배포 서버(Linux)의 실제 경로가 다르므로 환경변수로 분리한다. */
    @Value("${ide.users-root}")
    private String usersRoot;

    /* 사용자에게 보여 줄 가짜 최상위. 이 값이 바뀌면 프론트의 기본 경로도 함께 바꿔야 한다. */
    public static final String VIRTUAL_ROOT = "C:\\";

    /*
     * 윈도우가 폴더 이름으로 허용하지 않는 것들.
     * 이름만 검사하면 되므로 경로 구분자와 상위 이동은 별도로 막는다.
     */
    private static final String FORBIDDEN_NAME_CHARS = ":*?\"<>|";

    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    /*
     * 사용자의 개인 폴더를 돌려준다. 없으면 만든다.
     *
     * 가입 시점에 미리 만들지 않고 여기서 만드는 이유는, 이 기능이 붙기 전에 가입한
     * 사용자도 처음 폴더를 열어 보는 순간 자연스럽게 자리를 갖게 하기 위해서다.
     */
    public Path getUserRoot(Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }

        Path root = Paths.get(usersRoot, String.valueOf(userId))
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "개인 폴더를 준비하지 못했습니다.");
        }

        return root;
    }

    /*
     * 화면에서 온 가상 경로를 진짜 경로로 바꾼다.
     *
     * 검증 순서는 FileService.getSecureTargetPath 와 같다. 서버가 신뢰하는 루트를
     * 먼저 계산하고, 사용자가 준 부분은 이어 붙인 뒤 normalize 해서 루트 안에
     * 남아 있는지 확인한다. ".." 로 빠져나가려는 시도는 여기서 걸린다.
     */
    public Path toReal(String virtualPath, Long userId) {
        Path root = getUserRoot(userId);
        String relative = stripVirtualRoot(virtualPath);

        Path target = relative.isBlank()
                ? root
                : root.resolve(relative).normalize();

        if (!target.startsWith(root)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "이 위치는 사용할 수 없습니다.");
        }

        return target;
    }

    /*
     * 진짜 경로를 화면에 보여 줄 가상 경로로 되돌린다.
     * 응답에 진짜 경로가 섞여 나가지 않도록 목록/생성 결과는 전부 이걸 거친다.
     */
    public String toVirtual(Path realPath, Long userId) {
        Path root = getUserRoot(userId);
        Path absolute = realPath.toAbsolutePath().normalize();

        if (!absolute.startsWith(root)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "이 위치는 사용할 수 없습니다.");
        }

        String relative = root.relativize(absolute).toString();

        return relative.isBlank() ? VIRTUAL_ROOT : VIRTUAL_ROOT + relative;
    }

    /*
     * 새로 만들 폴더 이름을 검사한다.
     *
     * 경로 구분자와 ".." 를 막는 것이 핵심이다. 이름 칸에 "..\\.." 를 넣어
     * 상위로 올라가는 것을 여기서 차단한다(toReal 이 한 번 더 막지만,
     * 잘못된 입력은 이름 단계에서 알려 주는 편이 사용자에게 친절하다).
     */
    public void validateFolderName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "폴더 이름을 입력하세요.");
        }

        String trimmed = name.trim();

        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "폴더 이름에 / 나 \\ 는 쓸 수 없습니다.");
        }

        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "쓸 수 없는 폴더 이름입니다.");
        }

        for (char c : FORBIDDEN_NAME_CHARS.toCharArray()) {
            if (trimmed.indexOf(c) >= 0) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "폴더 이름에 " + FORBIDDEN_NAME_CHARS + " 문자는 쓸 수 없습니다."
                );
            }
        }

        /* 윈도우는 CON, PRN 같은 이름을 장치로 예약해 두어 폴더로 쓸 수 없다. */
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(upper)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "윈도우가 예약한 이름이라 쓸 수 없습니다.");
        }

        /* 끝의 마침표는 윈도우가 조용히 지워 버려서 만든 이름과 실제 이름이 어긋난다. */
        if (trimmed.endsWith(".")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "폴더 이름은 마침표로 끝날 수 없습니다.");
        }
    }

    /*
     * 앞의 "C:\" 를 떼어 내고 나머지 상대경로만 돌려준다.
     *
     * 다른 드라이브(D:\ 등)를 넣으면 여기서 막힌다. 사용자에게는 C: 하나만
     * 존재하는 것으로 보이므로, 그 밖의 값은 잘못된 입력이다.
     */
    private String stripVirtualRoot(String virtualPath) {
        if (virtualPath == null || virtualPath.isBlank()) {
            return "";
        }

        String path = virtualPath.trim().replace('/', '\\');

        if (path.equalsIgnoreCase("C:") || path.equalsIgnoreCase("C:\\")) {
            return "";
        }

        if (!path.regionMatches(true, 0, "C:\\", 0, 3)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "이 위치는 사용할 수 없습니다.");
        }

        String rest = path.substring(3);

        while (rest.startsWith("\\")) {
            rest = rest.substring(1);
        }

        while (rest.endsWith("\\")) {
            rest = rest.substring(0, rest.length() - 1);
        }

        return rest;
    }
}
