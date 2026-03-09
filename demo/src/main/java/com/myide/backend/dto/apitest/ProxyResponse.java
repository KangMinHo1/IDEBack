package com.myide.backend.dto.apitest;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProxyResponse {
    private int status;
    private Object data;     // JSON이면 Map/List, 아니면 String
    private long time;       // ms
}