package com.myide.backend.service;

import com.myide.backend.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * 가상 경로(화면에 보이는 C:\...)와 진짜 경로 사이의 변환을 확인한다.
 *
 * 이 검사가 필요한 이유
 * -------------------
 * 예전 코드는 "수업\쇼핑몰" 을 통째로 Path.resolve 에 넘겼다. 윈도우에서는 역슬래시가
 * 폴더 구분자라 우연히 동작했지만, 배포 서버(리눅스)에서는 역슬래시가 그냥 글자여서
 * "수업\쇼핑몰" 이라는 이름의 폴더 하나를 찾다가 실패했다. 반대 방향인 toVirtual 은
 * 리눅스에서 "C:\수업/쇼핑몰" 처럼 구분자가 뒤섞인 값을 내보냈다.
 *
 * 개발은 윈도우에서 하므로 이 버그는 손으로 눌러 보는 것으로는 영영 재현되지 않는다.
 * 그래서 아래 검사들은 전부 "OS 와 상관없이 성립해야 하는 것"만 담았다.
 */
class UserSpaceServiceTest {

    private static final long USER_ID = 42L;

    private UserSpaceService userSpaceService;

    /** 이 사용자의 개인 폴더. 화면에는 이 자리가 C:\ 로 보인다. */
    private Path userRoot;

    @BeforeEach
    void setUp(@TempDir Path usersRoot) {
        userSpaceService = new UserSpaceService();

        /* 운영에서는 ide.users-root 설정으로 주입되는 값이다. */
        ReflectionTestUtils.setField(userSpaceService, "usersRoot", usersRoot.toString());

        userRoot = userSpaceService.getUserRoot(USER_ID);
    }

    @Test
    @DisplayName("하위 폴더를 왕복시켜도 같은 자리로 돌아온다")
    void roundTripKeepsNestedFolders() throws IOException {
        Path nested = Files.createDirectories(userRoot.resolve("수업").resolve("쇼핑몰"));

        String virtual = userSpaceService.toVirtual(nested, USER_ID);

        assertEquals(nested, userSpaceService.toReal(virtual, USER_ID));
    }

    @Test
    @DisplayName("가상 경로의 역슬래시는 폴더 구분자로 해석된다")
    void backslashIsTreatedAsSeparator() {
        Path target = userSpaceService.toReal("C:\\수업\\쇼핑몰", USER_ID);

        assertEquals(userRoot.resolve("수업").resolve("쇼핑몰"), target);

        /*
         * 통째로 넘기던 시절에는 리눅스에서 마지막 조각이 "수업\쇼핑몰" 하나였다.
         * 폴더가 제대로 쪼개졌는지 이름으로 한 번 더 확인한다.
         */
        assertEquals("쇼핑몰", target.getFileName().toString());
    }

    @Test
    @DisplayName("슬래시로 적어 보낸 가상 경로도 같은 자리를 가리킨다")
    void forwardSlashIsAccepted() {
        assertEquals(
                userSpaceService.toReal("C:\\수업\\쇼핑몰", USER_ID),
                userSpaceService.toReal("C:/수업/쇼핑몰", USER_ID)
        );
    }

    @Test
    @DisplayName("화면에 돌려줄 경로에는 슬래시가 섞이지 않는다")
    void virtualPathUsesBackslashOnly() throws IOException {
        Path nested = Files.createDirectories(userRoot.resolve("수업").resolve("쇼핑몰"));

        String virtual = userSpaceService.toVirtual(nested, USER_ID);

        assertEquals("C:\\수업\\쇼핑몰", virtual);
        assertFalse(virtual.contains("/"));
    }

    @Test
    @DisplayName("최상위는 양방향 모두 개인 폴더를 가리킨다")
    void rootMapsBothWays() {
        assertEquals(UserSpaceService.VIRTUAL_ROOT, userSpaceService.toVirtual(userRoot, USER_ID));
        assertEquals(userRoot, userSpaceService.toReal(UserSpaceService.VIRTUAL_ROOT, USER_ID));
        assertEquals(userRoot, userSpaceService.toReal("C:", USER_ID));
    }

    @Test
    @DisplayName("개인 폴더 밖으로 빠져나가려는 경로는 막는다")
    void escapingTheUserRootIsRejected() {
        assertThrows(ApiException.class,
                () -> userSpaceService.toReal("C:\\..\\..\\windows", USER_ID));

        assertThrows(ApiException.class,
                () -> userSpaceService.toReal("C:\\수업\\..\\..\\밖", USER_ID));
    }

    @Test
    @DisplayName("C: 가 아닌 드라이브 표기는 받지 않는다")
    void otherDriveIsRejected() {
        assertThrows(ApiException.class,
                () -> userSpaceService.toReal("D:\\수업", USER_ID));
    }
}
