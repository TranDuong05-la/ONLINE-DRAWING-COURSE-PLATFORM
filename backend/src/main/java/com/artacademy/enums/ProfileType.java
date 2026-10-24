package com.artacademy.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ProfileType {
    LEARNER,
    INSTRUCTOR;

    @JsonCreator
    public static ProfileType fromString(String value) {
        if (value == null) return null;
        for (ProfileType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown profile type: " + value);
    }
}
