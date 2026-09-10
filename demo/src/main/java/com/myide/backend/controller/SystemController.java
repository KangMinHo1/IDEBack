package com.myide.backend.controller;

import com.myide.backend.dto.system.CreateFolderRequest;
import com.myide.backend.exception.ApiException;
import com.myide.backend.service.CurrentUserService;
import com.myide.backend.service.UserSpaceService;
import com.myide.backend.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/*
 * 프로젝트를 만들 때 저장 위치를 고르는 폴더 탐색기용 API.
 *
 * 주고받는 경로는 전부 "가상 경로"다. 사용자에게는 자기 개인 폴더가 C:\ 로 보이고,
 * 진짜 경로(C:/WebIDE/users/{userId}/...)는 응답에 실리지 않는다. 변환과 검증은
 * UserSpaceService 한 곳에서만 한다.
 *
 * 사용자를 가려내야 폴더를 나눌 수 있으므로 모든 엔드포인트가 로그인을 요구한다.
 * CurrentUserService 가 Authorization 헤더를 직접 검사해 없으면 401 을 던진다.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final CurrentUserService currentUserService;
    private final UserSpaceService userSpaceService;
    private final WorkspaceService workspaceService;

    /*
     * 최상위 목록.
     *
     * 예전에는 File.listRoots() 로 서버의 모든 드라이브를 돌려주었다. 그러면 남의
     * 작업물과 OS 폴더까지 전부 노출되므로, 지금은 사용자에게 보여 줄 가짜 최상위
     * 하나만 돌려준다. 이 호출이 개인 폴더를 만드는 시점이기도 하다.
     */
    @GetMapping("/roots")
    public ResponseEntity<List<String>> getSystemRoots() {
        Long userId = currentUserService.getCurrentUserId();
        userSpaceService.getUserRoot(userId);

        return ResponseEntity.ok(List.of(UserSpaceService.VIRTUAL_ROOT));
    }

    /*
     * 특정 위치의 하위 폴더 목록.
     *
     * 숨김 폴더는 빼고 보여 준다. 사용자가 만들지 않은 것이 목록에 섞이면
     * 자기 폴더가 아닌 것처럼 느껴지기 때문이다.
     */
    @GetMapping("/folders")
    public ResponseEntity<List<Map<String, String>>> getSubFolders(@RequestParam String path) {
        Long userId = currentUserService.getCurrentUserId();
        Path directory = userSpaceService.toReal(path, userId);

        if (!Files.isDirectory(directory)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다.");
        }

        List<Map<String, String>> folders = new ArrayList<>();

        try (Stream<Path> children = Files.list(directory)) {
            children
                    .filter(Files::isDirectory)
                    .filter(this::isVisible)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, String> item = new HashMap<>();
                        item.put("name", p.getFileName().toString());
                        item.put("path", userSpaceService.toVirtual(p, userId));
                        item.put("modifiedAt", lastModifiedOf(p));
                        folders.add(item);
                    });
        } catch (IOException | UncheckedIOException e) {
            /*
             * 읽을 수 없는 폴더를 만나도 창 전체가 죽지 않도록 빈 목록으로 넘긴다.
             * 예전 구현이 listFiles() 의 null 을 빈 목록으로 처리하던 것과 같은 태도다.
             */
            return ResponseEntity.ok(List.of());
        }

        return ResponseEntity.ok(folders);
    }

    /*
     * 폴더 선택 창의 "새 폴더".
     *
     * 워크스페이스 폴더 자체는 생성할 때 이름이 자동으로 붙으므로, 여기서 만드는 것은
     * 그보다 위의 정리용 폴더(예: C:\수업\)다.
     */
    @PostMapping("/folders")
    public ResponseEntity<Map<String, String>> createFolder(
            @RequestBody @Valid CreateFolderRequest request
    ) {
        Long userId = currentUserService.getCurrentUserId();

        userSpaceService.validateFolderName(request.getName());

        Path parent = userSpaceService.toReal(request.getPath(), userId);

        if (!Files.isDirectory(parent)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "만들 위치를 찾을 수 없습니다.");
        }

        String folderName = request.getName().trim();

        /*
         * 이름을 이어 붙인 뒤 한 번 더 가상 경로로 왕복시킨다.
         * toVirtual 이 개인 폴더 밖이면 거부하므로, 이름 검사를 빠져나간 값이
         * 있더라도 실제로 폴더가 만들어지기 전에 걸린다.
         */
        Path target = parent.resolve(folderName).normalize();
        String virtualPath = userSpaceService.toVirtual(target, userId);

        if (Files.exists(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "같은 이름의 폴더가 이미 있습니다.");
        }

        try {
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "폴더를 만들지 못했습니다.");
        }

        Map<String, String> created = new HashMap<>();
        created.put("name", folderName);
        created.put("path", virtualPath);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /*
     * 폴더 선택 창의 "삭제".
     *
     * 휴지통이 없어서 한 번 지우면 되돌릴 수 없다. 그래서 두 가지를 먼저 막는다.
     * 최상위(개인 폴더 자체)와, 안에 워크스페이스가 들어 있는 폴더다. 후자는 지우면
     * DB 에만 워크스페이스가 남아 목록에는 보이는데 열면 깨지는 상태가 된다.
     */
    @DeleteMapping("/folders")
    public ResponseEntity<Void> deleteFolder(@RequestParam String path) {
        Long userId = currentUserService.getCurrentUserId();

        Path target = userSpaceService.toReal(path, userId);
        Path root = userSpaceService.getUserRoot(userId);

        if (target.equals(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "최상위 폴더는 지울 수 없습니다.");
        }

        if (!Files.isDirectory(target)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다.");
        }

        if (workspaceService.hasWorkspaceUnder(target, userId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이 폴더 안에 프로젝트가 있어 지울 수 없습니다."
            );
        }

        try {
            FileSystemUtils.deleteRecursively(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "폴더를 지우지 못했습니다.");
        }

        return ResponseEntity.noContent().build();
    }

    private boolean isVisible(Path path) {
        try {
            return !Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    /*
     * 목록의 "수정한 날짜" 열에 쓴다. 읽지 못하는 폴더가 하나 있다고 창 전체가
     * 비지 않도록 빈 문자열로 넘긴다(프론트는 값이 없으면 칸을 비워 둔다).
     */
    private String lastModifiedOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant().toString();
        } catch (IOException e) {
            return "";
        }
    }
}
