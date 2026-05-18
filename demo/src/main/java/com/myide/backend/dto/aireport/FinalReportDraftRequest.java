package com.myide.backend.dto.aireport;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class FinalReportDraftRequest {

    private ProjectInfo project;
    private List<DevlogInfo> devlogs = new ArrayList<>();
    private List<RequirementInfo> requirements = new ArrayList<>();
    private List<ApiSpecInfo> apiSpecs = new ArrayList<>();
    private List<ErdTableInfo> erdTables = new ArrayList<>();
    private List<FlowNodeInfo> flowNodes = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ProjectInfo {
        private String name;
        private String description;
        private String type;
        private String language;
        private List<String> stack = new ArrayList<>();
        private Integer progress;
        private Integer doneScheduleCount;
        private Integer scheduleTotalCount;
        private Integer devlogCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class DevlogInfo {
        private String title;
        private String date;
        private String projectName;
        private String summary;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RequirementInfo {
        private String category;
        private String name;
        private String description;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ApiSpecInfo {
        private String method;
        private String endpoint;
        private String description;
        private String request;
        private String response;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ErdTableInfo {
        private String name;
        private List<ErdColumnInfo> columns = new ArrayList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ErdColumnInfo {
        private String name;
        private String type;
        private Boolean pk;
        private Boolean fk;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class FlowNodeInfo {
        private String label;
        private String type;
        private String techStack;
    }
}