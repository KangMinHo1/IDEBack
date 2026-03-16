package com.myide.backend.dto.ide;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class FileNode {
    private String id;
    private String name;
    private String type;      // "file", "folder", "project"
    private List<FileNode> children;

    // [New] 프론트엔드가 Git 연동 상태를 알기 위해 추가
    private String gitUrl;
}