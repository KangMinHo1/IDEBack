package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CodeNode {
    private String id;
    private String label;
    private String type;
    private String role;
    private String packageName; // 패키지(폴더) 그룹화를 위한 변수
}