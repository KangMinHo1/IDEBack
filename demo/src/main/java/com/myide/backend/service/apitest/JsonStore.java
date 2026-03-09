package com.myide.backend.service.apitest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.apitest.TestParamDto;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class JsonStore {

    private final ObjectMapper om;

    public JsonStore(ObjectMapper om) {
        this.om = om;
    }

    public String toJson(List<TestParamDto> list) {
        try {
            if (list == null) return "[]";
            return om.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<TestParamDto> fromJson(String json) {
        try {
            if (json == null || json.isBlank()) return Collections.emptyList();
            return om.readValue(json, new TypeReference<List<TestParamDto>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}