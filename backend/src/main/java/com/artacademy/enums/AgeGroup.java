package com.artacademy.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgeGroup {
    KIDS,
    TEEN,
    ADULT,
    SENIOR;

    @JsonCreator
    public static AgeGroup fromString(String value) {
        if (value == null) return null;
        for (AgeGroup ag : values()) {
            if (ag.name().equalsIgnoreCase(value.trim())) {
                return ag;
            }
        }
        throw new IllegalArgumentException("Unknown age group: " + value);
    }
}
