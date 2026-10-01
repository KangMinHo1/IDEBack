package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편집 메시지만 편집으로 세는지 확인한다.
 *
 * 커서 이동이나 접속할 때 오가는 동기화까지 편집으로 세면, 파일을 열어 보기만 한
 * 사람이 작성자로 적혀 "수정 중"으로 뜨고 실행에서 엉뚱하게 제외 대상이 된다.
 */
class YjsMessageKindTest {

    private static ByteBuffer bytes(int... values) {
        byte[] raw = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            raw[i] = (byte) values[i];
        }
        return ByteBuffer.wrap(raw);
    }

    @Test
    @DisplayName("문서 편집(0, 2)은 편집이다")
    void updateIsAnEdit() {
        assertThat(YjsMessageKind.isDocumentEdit(bytes(0, 2, 5, 1, 2, 3))).isTrue();
    }

    @Test
    @DisplayName("접속할 때 오가는 동기화 1·2단계는 편집이 아니다")
    void syncStepsAreNotEdits() {
        assertThat(YjsMessageKind.isDocumentEdit(bytes(0, 0, 1, 0))).isFalse();
        assertThat(YjsMessageKind.isDocumentEdit(bytes(0, 1, 9, 9))).isFalse();
    }

    @Test
    @DisplayName("커서·접속자 정보(awareness)는 편집이 아니다")
    void awarenessIsNotAnEdit() {
        assertThat(YjsMessageKind.isDocumentEdit(bytes(1, 2, 3, 4))).isFalse();
    }

    @Test
    @DisplayName("너무 짧거나 없는 메시지는 편집이 아니다")
    void shortMessagesAreNotEdits() {
        assertThat(YjsMessageKind.isDocumentEdit(null)).isFalse();
        assertThat(YjsMessageKind.isDocumentEdit(bytes())).isFalse();
        assertThat(YjsMessageKind.isDocumentEdit(bytes(0))).isFalse();
    }

    @Test
    @DisplayName("버퍼의 읽는 위치를 건드리지 않는다")
    void doesNotConsumeTheBuffer() {
        ByteBuffer payload = bytes(0, 2, 7, 7);

        YjsMessageKind.isDocumentEdit(payload);

        // 같은 버퍼가 이어서 팀원에게 중계된다. 위치가 밀리면 잘린 메시지가 나간다.
        assertThat(payload.position()).isZero();
        assertThat(payload.remaining()).isEqualTo(4);
    }
}
