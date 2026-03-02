package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class CodeMapResponse {
    private List<CodeNode> nodes;
    private List<CodeEdge> edges;
}