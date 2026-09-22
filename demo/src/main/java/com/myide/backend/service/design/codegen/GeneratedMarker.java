package com.myide.backend.service.design.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 파일이 설계 관리의 코드 생성으로 만들어졌는지 알아본다.
 *
 * 코드 생성은 무엇을 만들었는지 따로 기록하지 않는다. DB 이력도, 목록 파일도
 * 없다. 대신 생성기마다 파일 머리에 "설계 관리에서 생성…" 주석을 남기므로,
 * 그 문구가 있으면 생성 파일로 본다. 코드맵이 이것으로 AI 생성 표시를 붙인다.
 *
 * 기록 대신 주석을 쓰는 데는 이유가 있다. 이미 만들어 둔 파일에도 바로
 * 통하고, 파일과 함께 git 으로 움직여서 브랜치를 병합하거나 팀원이 받아도
 * 표시가 따라간다. 사람이 고쳐도 주석은 남으므로 "고쳐도 AI 생성으로 둔다"는
 * 결정과도 맞는다. 반대로 주석을 지우면 표시도 사라지는데, 그건 사람이
 * 일부러 한 일이라 받아들인다.
 *
 * <b>아래 문구 목록은 생성기 템플릿과 짝이다.</b> 템플릿 문구를 바꾸면 코드맵
 * 표시가 에러 없이 조용히 사라진다. GeneratedMarkerTest 가 모든 생성기의
 * 파일을 여기에 통과시켜 그런 어긋남을 잡는다. 새 생성기를 만들면 목록을
 * 늘리지 말고 템플릿에 {@link #PHRASE} 를 넣을 것.
 */
public final class GeneratedMarker {

    /** 새 생성기 템플릿이 반드시 넣어야 하는 문구. */
    public static final String PHRASE = "설계 관리에서 생성";

    /**
     * 판별에 쓰는 문구들.
     *
     * 첫 번째 외의 둘은 이미 그 문구 없이 나가고 있는 템플릿 때문이다.
     * DTO record(JsonStubShaper)와 Service(SpringServiceGenerator)가 그렇다.
     * 템플릿을 고쳐 PHRASE 로 통일하면 이미 생성해 둔 파일이 다음 미리보기에서
     * 전부 "충돌"로 보이게 되므로, 판별 쪽에서 받아 준다.
     */
    private static final List<String> PHRASES = List.of(
            PHRASE,
            "설계 관리의 API 명세에 적힌 예시에서 만들어졌습니다",
            "설계에서 이 표에 연결된 API 중 CRUD 모양인 것만 만들었습니다"
    );

    /**
     * 파일 앞부분만 본다.
     *
     * Java 파일은 주석이 package/import 뒤 Javadoc 안에 있어서 맨 앞 몇 줄만으로는
     * 못 찾는다. 그렇다고 코드맵을 그릴 때마다 모든 파일을 통째로 읽을 필요는 없다.
     */
    static final int HEAD_BYTES = 8 * 1024;

    private GeneratedMarker() {
    }

    public static boolean isGenerated(String head) {
        if (head == null || head.isEmpty()) {
            return false;
        }

        return PHRASES.stream().anyMatch(head::contains);
    }

    /** 디스크에서 읽을 때와 같은 범위(앞 {@link #HEAD_BYTES} 바이트)로 자른다. */
    static String head(String content) {
        if (content == null) {
            return "";
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (bytes.length <= HEAD_BYTES) {
            return content;
        }

        return new String(Arrays.copyOf(bytes, HEAD_BYTES), StandardCharsets.UTF_8);
    }

    /**
     * 파일을 읽어 생성 파일인지 본다.
     *
     * 읽지 못하면 false 다. 표시 하나 때문에 코드맵 전체가 실패하면 안 된다.
     */
    public static boolean readsAsGenerated(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }

        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(HEAD_BYTES);
            return isGenerated(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
