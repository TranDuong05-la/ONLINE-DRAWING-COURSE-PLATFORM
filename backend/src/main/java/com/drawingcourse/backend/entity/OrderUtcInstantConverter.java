package com.drawingcourse.backend.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Order-only UTC wall-clock representation for MySQL DATETIME(6), independent of JVM timezone. */
@Converter
public class OrderUtcInstantConverter implements AttributeConverter<Instant, LocalDateTime> {
    @Override
    public LocalDateTime convertToDatabaseColumn(Instant value) {
        if (value == null) return null;
        if (value.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Order timestamps must fit DATETIME(6) microsecond precision");
        }
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    @Override
    public Instant convertToEntityAttribute(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
