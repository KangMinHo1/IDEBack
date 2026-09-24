package com.myide.backend.dto.apitest;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlEncodedItemDto {
    private String id;
    private String key;
    private String value;
    private Boolean enabled;
}
