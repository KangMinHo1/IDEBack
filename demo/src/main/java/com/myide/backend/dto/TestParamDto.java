package com.myide.backend.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TestParamDto {
    private String key;
    private String value;
    private String desc;
    private boolean enabled;
}