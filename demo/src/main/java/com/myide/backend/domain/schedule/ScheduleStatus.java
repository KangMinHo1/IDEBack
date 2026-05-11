package com.myide.backend.domain.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ScheduleStatus {
    TODO("todo"),
    PROGRESS("progress"),
    DONE("done"),
    DELAYED("delayed");

    private final String value;

    ScheduleStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ScheduleStatus from(String value) {
        if (value == null) {
            return TODO;
        }

        for (ScheduleStatus status : ScheduleStatus.values()) {
            if (
                    status.name().equalsIgnoreCase(value) ||
                            status.value.equalsIgnoreCase(value)
            ) {
                return status;
            }
        }

        throw new IllegalArgumentException("지원하지 않는 일정 상태입니다: " + value);
    }
}