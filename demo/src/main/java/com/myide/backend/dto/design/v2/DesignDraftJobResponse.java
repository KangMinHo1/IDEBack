package com.myide.backend.dto.design.v2;

/**
 * 초안 작업의 지금 상태.
 *
 * 화면이 이것을 몇 초마다 물어본다. model 과 report 는 다 만들어진 뒤에만
 * 채워지고, 그때도 문서에 바로 들어가지 않고 사용자의 검토를 거친다.
 *
 * @param status RUNNING / DONE / FAILED
 * @param stage  지금 몇 번째 단계인지(1 또는 2). 진행 문구에 쓴다.
 */
public record DesignDraftJobResponse(
        String status,
        int stage,
        DesignModelV2 model,
        DoctorReport report,
        String errorMessage
) {}
