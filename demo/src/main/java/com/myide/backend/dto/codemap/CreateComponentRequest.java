package com.myide.backend.dto.codemap;

import lombok.Data;

@Data
public class CreateComponentRequest {
    private String workspaceId;
    private String projectName;
    private String branchName;
    private String name; // 클래스 이름 (예: PaymentProcessor)
    private String type; // 컴포넌트 타입 (CLASS, INTERFACE, ABSTRACT, EXCEPTION)
}