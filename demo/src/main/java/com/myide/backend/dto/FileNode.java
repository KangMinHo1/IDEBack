package com.myide.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileNode {
    private String id;       //
    private String name;     // 파일/폴더 이름 (화면 표시용)
    private String type;     // "file" or "folder"
    private List<FileNode> children; // 하위 목록
}