package com.myide.backend.dto.aiassist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAssistResponse {
    private boolean success;       // 💡 API 통신 성공 여부 (에러 처리용)
    private String explanation;    // AI의 수정 설명 또는 에러 메시지
    private String suggestedCode;  // AI가 제안한 코드 (에러 시 원본 코드 유지)
}
