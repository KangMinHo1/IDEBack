package com.myide.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FileNode {
    private String name;     // 파일 또는 폴더 이름 (예: Main.java)
    private String type;     // "FILE" 또는 "DIRECTORY"
    private String path;     // 상대 경로 (예: src/utils/Helper.java) -> 클릭 시 서버에 요청할 ID 역할
    private List<FileNode> children; // 하위 파일 목록 (폴더인 경우에만 존재)
}