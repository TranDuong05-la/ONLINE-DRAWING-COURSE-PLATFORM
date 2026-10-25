package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import java.time.*;

@Converter
public class VoucherUtcInstantConverter implements AttributeConverter<Instant, LocalDateTime> {
    public LocalDateTime convertToDatabaseColumn(Instant value) {
        if (value == null) return null;
        if (value.getNano() % 1000 != 0) throw new IllegalArgumentException("Voucher timestamps require DATETIME(6) precision");
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
    public Instant convertToEntityAttribute(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
