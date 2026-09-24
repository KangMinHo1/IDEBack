package com.myide.backend.dto.apitest;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssertionDto {

    private String id;

    // status | responseTime | jsonBody
    private String type;

    // equals | notEquals | lessThan | greaterThan | contains | exists
    private String operator;

    private String path;
    private String expected;
    private Boolean enabled;
}
