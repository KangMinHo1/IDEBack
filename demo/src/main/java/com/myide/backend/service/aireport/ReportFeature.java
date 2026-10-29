package com.myide.backend.service.aireport;

/**
 * 보고서 자료에 들어간 기능 하나.
 *
 * 서버가 정리한 사실을 AI 응답 검사에서도 다시 쓰려고 글과 따로 들고 있는다.
 * 특히 evidence 는 "설계가 이어졌다"와 "실제로 만들었다"를 가르는 유일한
 * 근거라서, 응답이 근거 없는 기능을 구현했다고 쓰는지 볼 때 쓴다.
 */
public record ReportFeature(String label, String name, boolean required, Evidence evidence) {

    public enum Evidence {
        /** 개발일지에 구현했다는 기록이 있다. */
        RECORDED,
        /** 개발일지에 언급은 있으나 미완성·보류로 적혀 있다. */
        PARTIAL,
        /** 개발일지에 기록이 없다. 미구현이 아니라 "구현 여부 확인 필요"다. */
        NONE
    }
}
