package com.artacademy.bootstrap;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseV13FlywayHistoryRowTest {
    @Test
    void selectAndMapperIncludeFlywayMigrationTypeColumn() throws Exception {
        assertThat(CourseV13ReadOnlyPreflight.FLYWAY_HISTORY_QUERY)
                .contains("installed_rank, version, description, type, success, checksum");

        ResultSet row = mock(ResultSet.class);
        when(row.getInt("installed_rank")).thenReturn(13);
        when(row.getString("version")).thenReturn("13");
        when(row.getString("description")).thenReturn("learning notifications");
        when(row.getString("type")).thenReturn("SQL");
        when(row.getBoolean("success")).thenReturn(true);
        when(row.getObject("checksum")).thenReturn(123456);

        assertThat(CourseV13ReadOnlyPreflight.readHistoryRow(row)).isEqualTo(
                new CourseV13ReadOnlyPreflight.FlywayHistoryRow(
                        13, "13", "learning notifications", "SQL", true, 123456));
    }

    @Test
    void missingRequiredHistoryColumnFailsClosed() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getInt("installed_rank")).thenReturn(13);
        when(row.getString("version")).thenReturn("13");
        when(row.getString("description")).thenReturn("learning notifications");
        when(row.getString("type")).thenThrow(new SQLException("Column 'type' not found"));

        assertThatThrownBy(() -> CourseV13ReadOnlyPreflight.readHistoryRow(row))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("NO-GO: unexpected Flyway schema history result")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void pendingFailedOrFutureHistoryIsExplicitlyNoGo() {
        assertThat(CourseV13ReadOnlyPreflight.historyDecision(Set.of("13"), List.of(), List.of()))
                .startsWith("NO-GO");
        assertThat(CourseV13ReadOnlyPreflight.historyDecision(Set.of(), List.of("12"), List.of()))
                .startsWith("NO-GO");
        assertThat(CourseV13ReadOnlyPreflight.historyDecision(Set.of(), List.of(), List.of("14")))
                .startsWith("NO-GO");
        assertThat(CourseV13ReadOnlyPreflight.historyDecision(Set.of(), List.of(), List.of()))
                .contains("COMPLETE").contains("separate approval");
    }
}
