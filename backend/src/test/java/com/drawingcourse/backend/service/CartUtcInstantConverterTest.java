package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartUtcInstantConverter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CartUtcInstantConverterTest {
    private final CartUtcInstantConverter converter = new CartUtcInstantConverter();

    @Test
    void mapsUtcInstantToZoneFreeMicrosecondValueAndBack() {
        Instant instant = Instant.parse("2026-10-27T07:55:50.096998Z");

        assertThat(converter.convertToDatabaseColumn(instant))
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 7, 55, 50, 96_998_000));
        assertThat(converter.convertToEntityAttribute(LocalDateTime.of(2026, 10, 27, 7, 55, 50, 96_998_000)))
                .isEqualTo(instant);
    }

    @Test
    void rejectsValuesThatWouldBeRoundedByDatetimeSix() {
        Instant subMicrosecond = Instant.parse("2026-10-27T07:55:50.096998001Z");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> converter.convertToDatabaseColumn(subMicrosecond));
    }
}
