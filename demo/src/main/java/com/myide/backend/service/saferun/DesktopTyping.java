// 경로: src/main/java/com/myide/backend/service/saferun/DesktopTyping.java
package com.myide.backend.service.saferun;

/**
 * 앱 화면(Swing 화면 중계)에 한글을 대신 쳐 주는 명령을 만든다.
 *
 * 화면 중계(noVNC → x11vnc)는 조합된 글자가 아니라 누른 키만 보낸다. 컨테이너에는 한글 입력기가
 * 없어 "한"을 치면 g·k·s 가 그대로 들어간다. 그래서 브라우저의 입력 칸에서 사용자 PC 의 입력기로
 * 조합을 끝낸 글자를 받아, 컨테이너 안에서 xdotool 로 지금 포커스가 있는 칸에 쳐 넣는다.
 */
public final class DesktopTyping {

    /** 한 번에 칠 수 있는 최대 글자 수. 글자마다 지연을 주므로 길면 그만큼 오래 붙들고 있다. */
    public static final int MAX_LENGTH = 500;

    /** 글자 사이 지연(ms). 너무 빠르면 Swing 이 일부 글자를 놓친다. */
    private static final String DELAY_MS = "12";

    private DesktopTyping() {
    }

    /**
     * 컨테이너에서 실행할 명령. 칠 글자가 없으면 null.
     *
     * 사용자가 보낸 글자라 셸(sh -c)에 넣지 않고 인자 배열로 넘긴다. 줄바꿈 같은 제어 문자는
     * 뺀다 — Enter·Tab 은 앱 화면에서 직접 누르면 된다.
     */
    public static String[] command(String text) {
        if (text == null) {
            return null;
        }

        String cleaned = text.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(MAX_LENGTH)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        if (cleaned.isEmpty()) {
            return null;
        }

        return new String[]{"xdotool", "type", "--delay", DELAY_MS, "--", cleaned};
    }
}
