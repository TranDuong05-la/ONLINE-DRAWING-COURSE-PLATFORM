package com.drawingcourse.backend.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure validation over already-resolved trusted Combo and Course facts.
 * It performs no catalog, ownership, claim, persistence, or pricing lookup.
 */
public final class ComboCheckoutValidator {
    private ComboCheckoutValidator() { }

    public enum FailureCode {
        INPUT_REQUIRED,
        COMBO_SELECTIONS_REQUIRED,
        STANDALONE_SELECTIONS_REQUIRED,
        OWNED_COURSE_IDS_REQUIRED,
        PENDING_COURSE_IDS_REQUIRED,
        INVALID_COMBO_SELECTION,
        INVALID_COMBO_DEFINITION,
        INVALID_COMBO_ID,
        INVALID_COMBO_QUANTITY,
        INVALID_COMBO_PRICE,
        INVALID_COMBO_NAME,
        INVALID_COMBO_VERSION,
        EMPTY_COMBO_COMPONENTS,
        INVALID_COMBO_COMPONENT_COURSE_ID,
        DUPLICATE_COMBO_COMPONENT,
        INVALID_STANDALONE_SELECTION,
        INVALID_STANDALONE_COURSE_ID,
        INVALID_STANDALONE_QUANTITY,
        DUPLICATE_COURSE_COVERAGE,
        INVALID_OWNED_COURSE_ID,
        COURSE_ALREADY_OWNED,
        INVALID_PENDING_COURSE_ID,
        COURSE_PENDING_PURCHASE
    }

    public enum SourceType { STANDALONE_COURSE, COMBO_COMPONENT }

    /** Trusted, versioned catalog snapshot. Components retain Member B's supplied order. */
    public record ComboDefinition(Integer comboId, long definitionVersion, String name,
                                  long unitPriceVnd, List<Integer> orderedCourseIds) {
        public ComboDefinition {
            orderedCourseIds = immutableListAllowingNull(orderedCourseIds);
        }
    }

    public record SelectedComboLine(ComboDefinition combo, int quantity) { }

    public record SelectedStandaloneCourseLine(Integer courseId, int quantity) { }

    /** Authoritative owned/pending sets are snapshots supplied by a caller, not queried here. */
    public record ValidationInput(List<SelectedStandaloneCourseLine> standaloneCourses,
                                  List<SelectedComboLine> combos,
                                  Set<Integer> ownedCourseIds,
                                  Set<Integer> activePendingCourseIds) {
        public ValidationInput {
            standaloneCourses = immutableListAllowingNull(standaloneCourses);
            combos = immutableListAllowingNull(combos);
            ownedCourseIds = immutableSetAllowingNull(ownedCourseIds);
            activePendingCourseIds = immutableSetAllowingNull(activePendingCourseIds);
        }
    }

    public record CoverageSource(SourceType sourceType, int selectionIndex,
                                 Integer comboId, Integer componentIndex) {
        public CoverageSource {
            Objects.requireNonNull(sourceType, "sourceType");
            if (selectionIndex < 0) throw new IllegalArgumentException("Selection index cannot be negative");
            if (sourceType == SourceType.STANDALONE_COURSE
                    && (comboId != null || componentIndex != null)) {
                throw new IllegalArgumentException("Standalone coverage cannot have Combo coordinates");
            }
            if (sourceType == SourceType.COMBO_COMPONENT
                    && (comboId == null || componentIndex == null || componentIndex < 0)) {
                throw new IllegalArgumentException("Combo coverage requires Combo coordinates");
            }
        }
    }

    public record CoveredCourse(int courseId, CoverageSource source) {
        public CoveredCourse {
            if (courseId <= 0) throw new IllegalArgumentException("Covered Course ID must be positive");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Sorted by Course ID so downstream lock acquisition and diagnostics are deterministic. */
    public record CourseCoverage(List<CoveredCourse> courses) {
        public CourseCoverage {
            courses = List.copyOf(courses);
        }

        public List<Integer> courseIds() {
            return courses.stream().map(CoveredCourse::courseId).toList();
        }
    }

    public record ValidationFailure(FailureCode code, Integer courseId, Integer comboId,
                                    int selectionIndex, Integer componentIndex,
                                    CoverageSource firstSource, CoverageSource conflictingSource) {
        public ValidationFailure {
            Objects.requireNonNull(code, "code");
            if (selectionIndex < -1) throw new IllegalArgumentException("Invalid selection index");
        }
    }

    public sealed interface ValidationResult permits Valid, Invalid { }

    public record Valid(CourseCoverage coverage) implements ValidationResult {
        public Valid { Objects.requireNonNull(coverage, "coverage"); }
    }

    public record Invalid(ValidationFailure failure) implements ValidationResult {
        public Invalid { Objects.requireNonNull(failure, "failure"); }
    }

    /**
     * Returns the first failure in the documented stage order. Input list order is used within a stage;
     * ownership/pending conflicts and valid output are ordered by ascending Course ID.
     */
    public static ValidationResult validate(ValidationInput input) {
        if (input == null) return invalid(FailureCode.INPUT_REQUIRED);
        if (input.combos() == null) return invalid(FailureCode.COMBO_SELECTIONS_REQUIRED);

        ValidationFailure failure = validateCombos(input.combos());
        if (failure != null) return new Invalid(failure);

        if (input.standaloneCourses() == null) return invalid(FailureCode.STANDALONE_SELECTIONS_REQUIRED);
        failure = validateStandaloneCourses(input.standaloneCourses());
        if (failure != null) return new Invalid(failure);

        TreeMap<Integer, CoverageSource> coverage = new TreeMap<>();
        failure = expandCoverage(input.standaloneCourses(), input.combos(), coverage);
        if (failure != null) return new Invalid(failure);

        if (input.ownedCourseIds() == null) return invalid(FailureCode.OWNED_COURSE_IDS_REQUIRED);
        failure = validateAuthoritativeSet(input.ownedCourseIds(), FailureCode.INVALID_OWNED_COURSE_ID);
        if (failure != null) return new Invalid(failure);
        for (var entry : coverage.entrySet()) {
            if (input.ownedCourseIds().contains(entry.getKey())) {
                return new Invalid(new ValidationFailure(FailureCode.COURSE_ALREADY_OWNED,
                        entry.getKey(), entry.getValue().comboId(), entry.getValue().selectionIndex(),
                        entry.getValue().componentIndex(), entry.getValue(), null));
            }
        }

        if (input.activePendingCourseIds() == null) return invalid(FailureCode.PENDING_COURSE_IDS_REQUIRED);
        failure = validateAuthoritativeSet(input.activePendingCourseIds(), FailureCode.INVALID_PENDING_COURSE_ID);
        if (failure != null) return new Invalid(failure);
        for (var entry : coverage.entrySet()) {
            if (input.activePendingCourseIds().contains(entry.getKey())) {
                return new Invalid(new ValidationFailure(FailureCode.COURSE_PENDING_PURCHASE,
                        entry.getKey(), entry.getValue().comboId(), entry.getValue().selectionIndex(),
                        entry.getValue().componentIndex(), entry.getValue(), null));
            }
        }

        List<CoveredCourse> courses = coverage.entrySet().stream()
                .map(entry -> new CoveredCourse(entry.getKey(), entry.getValue()))
                .toList();
        return new Valid(new CourseCoverage(courses));
    }

    private static ValidationFailure validateCombos(List<SelectedComboLine> combos) {
        for (int selectionIndex = 0; selectionIndex < combos.size(); selectionIndex++) {
            SelectedComboLine line = combos.get(selectionIndex);
            if (line == null) return failure(FailureCode.INVALID_COMBO_SELECTION, null, null,
                    selectionIndex, null, null, null);
            ComboDefinition combo = line.combo();
            if (combo == null) return failure(FailureCode.INVALID_COMBO_DEFINITION, null, null,
                    selectionIndex, null, null, null);
            if (combo.comboId() == null || combo.comboId() <= 0)
                return failure(FailureCode.INVALID_COMBO_ID, null, combo.comboId(), selectionIndex,
                        null, null, null);
            if (line.quantity() != 1)
                return failure(FailureCode.INVALID_COMBO_QUANTITY, null, combo.comboId(), selectionIndex,
                        null, null, null);
            if (combo.unitPriceVnd() < 0)
                return failure(FailureCode.INVALID_COMBO_PRICE, null, combo.comboId(), selectionIndex,
                        null, null, null);
            if (combo.name() == null || combo.name().isBlank())
                return failure(FailureCode.INVALID_COMBO_NAME, null, combo.comboId(), selectionIndex,
                        null, null, null);
            if (combo.definitionVersion() < 0)
                return failure(FailureCode.INVALID_COMBO_VERSION, null, combo.comboId(), selectionIndex,
                        null, null, null);
            if (combo.orderedCourseIds() == null || combo.orderedCourseIds().isEmpty())
                return failure(FailureCode.EMPTY_COMBO_COMPONENTS, null, combo.comboId(), selectionIndex,
                        null, null, null);

            Map<Integer, Integer> firstPositions = new LinkedHashMap<>();
            for (int componentIndex = 0; componentIndex < combo.orderedCourseIds().size(); componentIndex++) {
                Integer courseId = combo.orderedCourseIds().get(componentIndex);
                if (courseId == null || courseId <= 0)
                    return failure(FailureCode.INVALID_COMBO_COMPONENT_COURSE_ID, courseId, combo.comboId(),
                            selectionIndex, componentIndex, null, null);
                Integer firstPosition = firstPositions.putIfAbsent(courseId, componentIndex);
                if (firstPosition != null) {
                    var first = new CoverageSource(SourceType.COMBO_COMPONENT, selectionIndex,
                            combo.comboId(), firstPosition);
                    var duplicate = new CoverageSource(SourceType.COMBO_COMPONENT, selectionIndex,
                            combo.comboId(), componentIndex);
                    return failure(FailureCode.DUPLICATE_COMBO_COMPONENT, courseId, combo.comboId(),
                            selectionIndex, componentIndex, first, duplicate);
                }
            }
        }
        return null;
    }

    private static ValidationFailure validateStandaloneCourses(List<SelectedStandaloneCourseLine> courses) {
        for (int selectionIndex = 0; selectionIndex < courses.size(); selectionIndex++) {
            SelectedStandaloneCourseLine line = courses.get(selectionIndex);
            if (line == null) return failure(FailureCode.INVALID_STANDALONE_SELECTION, null, null,
                    selectionIndex, null, null, null);
            if (line.courseId() == null || line.courseId() <= 0)
                return failure(FailureCode.INVALID_STANDALONE_COURSE_ID, line.courseId(), null,
                        selectionIndex, null, null, null);
            if (line.quantity() != 1)
                return failure(FailureCode.INVALID_STANDALONE_QUANTITY, line.courseId(), null,
                        selectionIndex, null, null, null);
        }
        return null;
    }

    private static ValidationFailure expandCoverage(List<SelectedStandaloneCourseLine> standalone,
                                                    List<SelectedComboLine> combos,
                                                    TreeMap<Integer, CoverageSource> coverage) {
        for (int selectionIndex = 0; selectionIndex < standalone.size(); selectionIndex++) {
            var line = standalone.get(selectionIndex);
            var source = new CoverageSource(SourceType.STANDALONE_COURSE, selectionIndex, null, null);
            ValidationFailure duplicate = addCoverage(coverage, line.courseId(), source);
            if (duplicate != null) return duplicate;
        }
        for (int selectionIndex = 0; selectionIndex < combos.size(); selectionIndex++) {
            ComboDefinition combo = combos.get(selectionIndex).combo();
            for (int componentIndex = 0; componentIndex < combo.orderedCourseIds().size(); componentIndex++) {
                Integer courseId = combo.orderedCourseIds().get(componentIndex);
                var source = new CoverageSource(SourceType.COMBO_COMPONENT, selectionIndex,
                        combo.comboId(), componentIndex);
                ValidationFailure duplicate = addCoverage(coverage, courseId, source);
                if (duplicate != null) return duplicate;
            }
        }
        return null;
    }

    private static ValidationFailure addCoverage(TreeMap<Integer, CoverageSource> coverage, Integer courseId,
                                                 CoverageSource source) {
        CoverageSource first = coverage.putIfAbsent(courseId, source);
        return first == null ? null : failure(FailureCode.DUPLICATE_COURSE_COVERAGE, courseId,
                source.comboId(), source.selectionIndex(), source.componentIndex(), first, source);
    }

    private static ValidationFailure validateAuthoritativeSet(Set<Integer> ids, FailureCode invalidCode) {
        if (ids.contains(null)) return failure(invalidCode, null, null, -1, null, null, null);
        Integer smallestInvalid = ids.stream().filter(id -> id <= 0).min(Integer::compareTo).orElse(null);
        return smallestInvalid == null ? null
                : failure(invalidCode, smallestInvalid, null, -1, null, null, null);
    }

    private static Invalid invalid(FailureCode code) {
        return new Invalid(failure(code, null, null, -1, null, null, null));
    }

    private static ValidationFailure failure(FailureCode code, Integer courseId, Integer comboId,
                                             int selectionIndex, Integer componentIndex,
                                             CoverageSource first, CoverageSource conflicting) {
        return new ValidationFailure(code, courseId, comboId, selectionIndex, componentIndex, first, conflicting);
    }

    private static <T> List<T> immutableListAllowingNull(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <T> Set<T> immutableSetAllowingNull(Set<T> source) {
        return source == null ? null : Collections.unmodifiableSet(new HashSet<>(source));
    }
}
