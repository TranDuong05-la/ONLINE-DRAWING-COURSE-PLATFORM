package com.drawingcourse.backend.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** UTC wall-clock representation for Cart DATETIME(6) values, independent of JVM timezone. */
@Converter
public class CartUtcInstantConverter implements AttributeConverter<Instant, LocalDateTime> {
    @Override
    public LocalDateTime convertToDatabaseColumn(Instant value) {
        if (value == null) return null;
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Cart timestamps must fit DATETIME(6) microsecond precision");
        }
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    @Override
    public Instant convertToEntityAttribute(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
