package com.myide.backend.controller;

import com.myide.backend.dto.aiassist.AiAssistRequest;
import com.myide.backend.dto.aiassist.AiAssistResponse;
import com.myide.backend.service.AiAssistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
// 기존 CORS 설정이 있다면 그대로 두셔도 됩니다.
@CrossOrigin(origins = "http://localhost:5173", allowedHeaders = "*")
public class AiController {

    private final AiAssistService aiAssistService;

    @PostMapping("/assist")
    public ResponseEntity<AiAssistResponse> assist(@RequestBody AiAssistRequest request) {
        AiAssistResponse response = aiAssistService.getAiSuggestion(request);
        return ResponseEntity.ok(response);
    }

    // 💡 [새로 추가된 기능] 고스트 텍스트(자동완성) 엔드포인트
    @PostMapping("/autocomplete")
    public ResponseEntity<String> autocomplete(@RequestBody Map<String, String> request) {
        String prefix = request.getOrDefault("prefix", "");
        String suffix = request.getOrDefault("suffix", "");

        String suggestion = aiAssistService.getAutocompleteSuggestion(prefix, suffix);
        return ResponseEntity.ok(suggestion);
    }
}