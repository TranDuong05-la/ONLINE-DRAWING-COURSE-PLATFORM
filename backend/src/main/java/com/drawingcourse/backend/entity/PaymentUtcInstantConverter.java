package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import java.time.*;

/** Pair with the verified direct-Java-time JDBC strategy for UTC DATETIME(6). */
@Converter
public class PaymentUtcInstantConverter implements AttributeConverter<Instant,LocalDateTime> {
    public LocalDateTime convertToDatabaseColumn(Instant value) {
        if(value==null) return null;
        if(value.getNano()%1000!=0) throw new IllegalArgumentException("Payment timestamps require microsecond precision");
        return LocalDateTime.ofInstant(value,ZoneOffset.UTC);
    }
    public Instant convertToEntityAttribute(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC); }
}
