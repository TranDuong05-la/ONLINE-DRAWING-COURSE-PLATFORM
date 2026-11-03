package com.artacademy.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly selected, non-Spring read-only database preflight. It is deliberately not named *Test or *IT,
 * so ordinary Surefire and the V13 Failsafe profile never execute it implicitly.
 * Run only after separately authorizing a read-only connection.
 */
class CourseV13ReadOnlyPreflight {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/drawing_course_it";
    private static final Pattern MIGRATION_FILE = Pattern.compile("V([0-9]+(?:\\.[0-9]+)*)__.*\\.sql");
    static final String FLYWAY_HISTORY_QUERY = "SELECT installed_rank, version, description, type, success, checksum "
            + "FROM flyway_schema_history ORDER BY installed_rank";

    record FlywayHistoryRow(int installedRank, String version, String description,
                            String type, boolean success, Object checksum) { }

    @Test
    void inspectTargetHistoryAndVisibleConcurrentActivityReadOnly() throws Exception {
        requireEnv("MYSQL_IT_DATABASE_ACK", "drawing_course_it");
        requireEnv("MYSQL_COURSE_V13_READONLY_ACK", "inspect-drawing_course_it-read-only");
        requireEnv("MYSQL_IT_URL", URL);
        String username = nonBlankEnv("MYSQL_IT_USERNAME");
        String password = nonBlankEnv("MYSQL_IT_PASSWORD");

        Set<String> sourceVersions = sourceMigrationVersions();
        System.out.println("V13 read-only preflight source migration versions=" + sourceVersions);
        assertThat(sourceVersions).as("NO-GO: source must contain exactly V1-V13")
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 13)
                        .mapToObj(String::valueOf).toList());

        try (Connection connection = DriverManager.getConnection(URL, username, password)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("START TRANSACTION READ ONLY");
            }
            try {
                String serverHost;
                int serverPort;
                String database;
                String version;
                long connectionId;
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT @@hostname, @@port, DATABASE(), VERSION(), CONNECTION_ID()")) {
                    assertThat(result.next()).isTrue();
                    serverHost = result.getString(1);
                    serverPort = result.getInt(2);
                    database = result.getString(3);
                    version = result.getString(4);
                    connectionId = result.getLong(5);
                }
                System.out.printf("V13 read-only target endpoint=%s serverHost=%s port=%d database=%s "
                                + "version=%s connectionId=%d%n", URL, serverHost, serverPort, database,
                        version, connectionId);
                assertThat(database).isEqualTo("drawing_course_it");
                assertThat(serverPort).isEqualTo(3306);
                Matcher mysql8 = Pattern.compile("^8\\.0\\.(\\d+)(?:[-+].*)?$").matcher(version);
                assertThat(mysql8.matches()).as("MySQL 8.0.16+ is required for enforced CHECK constraints").isTrue();
                assertThat(Integer.parseInt(mysql8.group(1))).isGreaterThanOrEqualTo(16);

                boolean historyExists = scalarInt(connection, "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'") == 1;
                if (!historyExists) {
                    System.out.println("NO-GO: Flyway history is ABSENT; all V1-V13 are pending.");
                    throw new IllegalStateException("NO-GO: no Flyway history table exists on the target");
                }

                List<String> history = new ArrayList<>();
                Set<String> successfulVersions = new TreeSet<>(CourseV13ReadOnlyPreflight::compareVersions);
                List<String> failed = new ArrayList<>();
                List<String> future = new ArrayList<>();
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        FLYWAY_HISTORY_QUERY)) {
                    while (result.next()) {
                        FlywayHistoryRow row = readHistoryRow(result);
                        String migrationVersion = row.version();
                        history.add("rank=" + row.installedRank() + ",version=" + migrationVersion
                                + ",type=" + row.type() + ",success=" + row.success() + ",checksum="
                                + row.checksum());
                        if (!row.success()) failed.add(migrationVersion + ":" + row.description());
                        if (migrationVersion != null && migrationVersion.matches("[0-9]+(?:\\.[0-9]+)*")) {
                            if (row.success() && "SQL".equalsIgnoreCase(row.type())) successfulVersions.add(migrationVersion);
                            if (compareVersions(migrationVersion, "13") > 0) future.add(migrationVersion);
                        }
                    }
                } catch (SQLException historyReadFailure) {
                    if (historyReadFailure.getErrorCode() == 1054
                            || "42S22".equals(historyReadFailure.getSQLState())) {
                        throw new SQLException("NO-GO: Flyway history structure lacks a required selected column",
                                historyReadFailure);
                    }
                    throw historyReadFailure;
                }
                Set<String> pending = new TreeSet<>(CourseV13ReadOnlyPreflight::compareVersions);
                pending.addAll(sourceVersions);
                pending.removeAll(successfulVersions);
                boolean allV1To13Applied = pending.isEmpty();
                String historyDecision = historyDecision(pending, failed, future);
                System.out.println("Flyway history=" + history);
                System.out.println("Flyway current successful version=" + successfulVersions.stream()
                        .max(CourseV13ReadOnlyPreflight::compareVersions).orElse("none") + ", V1-V13 all applied="
                        + allV1To13Applied + ", failed=" + failed + ", pending migrations=" + pending
                        + ", future database versions=" + future + ", history decision=" + historyDecision);
                assertThat(historyDecision).as("NO-GO: migration history is incomplete or incompatible")
                        .startsWith("COMPLETE");
                assertThat(failed).as("NO-GO: failed Flyway entries require manual inspection").isEmpty();
                assertThat(future).as("NO-GO: database contains a version newer than V13").isEmpty();

                boolean processPrivilege = hasProcessPrivilege(connection);
                List<String> activeSessions = activeSessions(connection);
                int pendingHistoryLocks = scalarInt(connection, "SELECT COUNT(*) FROM performance_schema.metadata_locks "
                        + "WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME='flyway_schema_history' "
                        + "AND LOCK_STATUS='PENDING'");
                System.out.println("Concurrent activity visible sessions=" + activeSessions
                        + ", PROCESS privilege=" + processPrivilege + ", pending Flyway metadata locks="
                        + pendingHistoryLocks);
                System.out.println("LIMIT: idle workers and sessions hidden by privilege cannot be ruled out. "
                        + "Operator confirmation is still required; this preflight never approves migration.");
            } finally {
                try (Statement rollback = connection.createStatement()) { rollback.execute("ROLLBACK"); }
            }
        }
    }

    private static Set<String> sourceMigrationVersions() throws Exception {
        Path directory = Path.of("src/main/resources/db/migration");
        assertThat(Files.isDirectory(directory)).as("run this preflight from backend/").isTrue();
        Set<String> versions = new TreeSet<>(CourseV13ReadOnlyPreflight::compareVersions);
        try (var files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString()).forEach(name -> {
                Matcher matcher = MIGRATION_FILE.matcher(name);
                if (matcher.matches()) versions.add(matcher.group(1));
            });
        }
        return versions;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    static FlywayHistoryRow readHistoryRow(ResultSet result) throws SQLException {
        try {
            return new FlywayHistoryRow(result.getInt("installed_rank"), result.getString("version"),
                    result.getString("description"), result.getString("type"), result.getBoolean("success"),
                    result.getObject("checksum"));
        } catch (SQLException incompatibleHistory) {
            throw new SQLException("NO-GO: unexpected Flyway schema history result; required columns are "
                    + "installed_rank, version, description, type, success, checksum", incompatibleHistory);
        }
    }

    static String historyDecision(Set<String> pending, List<String> failed, List<String> future) {
        return pending.isEmpty() && failed.isEmpty() && future.isEmpty()
                ? "COMPLETE (migration still requires separate approval)"
                : "NO-GO (history is incomplete or incompatible; do not migrate)";
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static boolean hasProcessPrivilege(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW GRANTS")) {
            while (result.next()) {
                String grant = result.getString(1).toUpperCase(java.util.Locale.ROOT);
                if (grant.contains("PROCESS ON *.*") || grant.contains("ALL PRIVILEGES ON *.*")) return true;
            }
        }
        return false;
    }

    private static List<String> activeSessions(Connection connection) throws Exception {
        List<String> sessions = new ArrayList<>();
        String sql = "SELECT ID, USER, HOST, COMMAND, TIME, STATE FROM information_schema.PROCESSLIST "
                + "WHERE ID<>CONNECTION_ID() AND DB=DATABASE() AND COMMAND<>'Sleep' ORDER BY ID";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                sessions.add("id=" + result.getLong(1) + ",user=" + result.getString(2) + ",host="
                        + result.getString(3) + ",command=" + result.getString(4) + ",time=" + result.getLong(5)
                        + ",state=" + result.getString(6));
            }
        }
        return sessions;
    }

    private static void requireEnv(String key, String expected) {
        assertThat(System.getenv(key)).as("required explicit preflight acknowledgment %s", key).isEqualTo(expected);
    }

    private static String nonBlankEnv(String key) {
        String value = System.getenv(key);
        assertThat(value).as("required environment variable %s", key).isNotBlank();
        return value;
    }
}
