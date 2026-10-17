package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 화면의 한글 입력 칸에서 받은 글자를 창에 대신 치는 명령을 확인한다.
 */
class DesktopTypingTest {

    @Test
    @DisplayName("글자는 셸을 거치지 않고 xdotool 인자로 그대로 넘긴다")
    void passesTextAsArgument() {
        assertThat(DesktopTyping.command("홍길동'; rm -rf /"))
                .containsExactly("xdotool", "type", "--delay", "12", "--", "홍길동'; rm -rf /");
    }

    @Test
    @DisplayName("줄바꿈·탭 같은 제어 문자는 뺀다")
    void stripsControlCharacters() {
        assertThat(DesktopTyping.command("가\r\n나\t다\u0000"))
                .endsWith("가나다");
    }

    @Test
    @DisplayName("너무 긴 글은 앞부분만 친다")
    void limitsLength() {
        String[] command = DesktopTyping.command("가".repeat(DesktopTyping.MAX_LENGTH + 10));

        assertThat(command[command.length - 1]).hasSize(DesktopTyping.MAX_LENGTH);
    }

    @Test
    @DisplayName("칠 글자가 없으면 명령을 만들지 않는다")
    void emptyText() {
        assertThat(DesktopTyping.command(null)).isNull();
        assertThat(DesktopTyping.command("\n\t")).isNull();
    }
}
