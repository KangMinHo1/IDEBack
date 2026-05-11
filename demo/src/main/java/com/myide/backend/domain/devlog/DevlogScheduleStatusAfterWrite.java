package com.myide.backend.domain.devlog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DevlogScheduleStatusAfterWrite {
    NONE("none"),
    PROGRESS("progress"),
    DONE("done");

    private final String value;

    DevlogScheduleStatusAfterWrite(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DevlogScheduleStatusAfterWrite from(String value) {
        if (value == null) {
            return NONE;
        }

        for (DevlogScheduleStatusAfterWrite status : values()) {
            if (
                    status.name().equalsIgnoreCase(value) ||
                            status.value.equalsIgnoreCase(value)
            ) {
                return status;
            }
        }

        throw new IllegalArgumentException("지원하지 않는 상태 변경 값입니다: " + value);
    }
}