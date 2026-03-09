package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor; // 💡 추가
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor // 💡 Jackson 역직렬화를 위해 반드시 필요!
public class CodeMapResponse {
    private List<CodeNode> nodes;
    private List<CodeEdge> edges;
}