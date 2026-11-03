package com.drawingcourse.backend.integration.contract;

import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ComboSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure Long-ID selection validation for future integration. It neither replaces nor changes the existing
 * Integer-based production validator, Cart, Order, or Checkout behavior.
 */
public final class LongIdComboCheckoutValidator {
    private LongIdComboCheckoutValidator() { }

    public enum FailureCode {
        INPUT_REQUIRED,
        COMBO_SELECTIONS_REQUIRED,
        STANDALONE_SELECTIONS_REQUIRED,
        OWNED_COURSE_IDS_REQUIRED,
        PENDING_COURSE_IDS_REQUIRED,
        INVALID_COMBO_SELECTION,
        INVALID_COMBO_QUANTITY,
        COMBO_NOT_CHECKOUT_READY,
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

    public record SelectedStandaloneCourse(Long courseId, int quantity) { }

    public record SelectedCombo(ComboSnapshot combo, int quantity) { }

    public record ValidationInput(List<SelectedStandaloneCourse> standaloneCourses,
                                  List<SelectedCombo> combos,
                                  Set<Long> ownedCourseIds,
                                  Set<Long> activePendingCourseIds) {
        public ValidationInput {
            standaloneCourses = immutableListAllowingNull(standaloneCourses);
            combos = immutableListAllowingNull(combos);
            ownedCourseIds = immutableSetAllowingNull(ownedCourseIds);
            activePendingCourseIds = immutableSetAllowingNull(activePendingCourseIds);
        }
    }

    public record CoverageSource(SourceType sourceType, int selectionIndex,
                                 Long comboId, Integer componentPosition) {
        public CoverageSource {
            Objects.requireNonNull(sourceType, "sourceType");
            if (selectionIndex < 0) throw new IllegalArgumentException("selectionIndex must be nonnegative");
        }
    }

    public record CoveredCourse(Long courseId, CoverageSource source) {
        public CoveredCourse {
            requirePositive(courseId, "courseId");
            Objects.requireNonNull(source, "source");
        }
    }

    public record CourseCoverage(List<CoveredCourse> courses) {
        public CourseCoverage { courses = List.copyOf(courses); }
        public List<Long> courseIds() { return courses.stream().map(CoveredCourse::courseId).toList(); }
    }

    public record ValidationFailure(FailureCode code, Long courseId, Long comboId,
                                    int selectionIndex, Integer componentPosition,
                                    CoverageSource firstSource, CoverageSource conflictingSource) { }

    public sealed interface ValidationResult permits Valid, Invalid { }

    public record Valid(CourseCoverage coverage) implements ValidationResult { }

    public record Invalid(ValidationFailure failure) implements ValidationResult { }

    public static ValidationResult validate(ValidationInput input) {
        if (input == null) return invalid(FailureCode.INPUT_REQUIRED);
        if (input.combos() == null) return invalid(FailureCode.COMBO_SELECTIONS_REQUIRED);
        for (int index = 0; index < input.combos().size(); index++) {
            SelectedCombo selection = input.combos().get(index);
            if (selection == null || selection.combo() == null)
                return invalid(FailureCode.INVALID_COMBO_SELECTION, null, null, index, null, null, null);
            if (selection.quantity() != 1)
                return invalid(FailureCode.INVALID_COMBO_QUANTITY, null, selection.combo().comboId(),
                        index, null, null, null);
            try {
                selection.combo().requireCheckoutReady();
            } catch (IllegalStateException notReady) {
                return invalid(FailureCode.COMBO_NOT_CHECKOUT_READY, null, selection.combo().comboId(),
                        index, null, null, null);
            }
        }

        if (input.standaloneCourses() == null) return invalid(FailureCode.STANDALONE_SELECTIONS_REQUIRED);
        for (int index = 0; index < input.standaloneCourses().size(); index++) {
            SelectedStandaloneCourse selection = input.standaloneCourses().get(index);
            if (selection == null)
                return invalid(FailureCode.INVALID_STANDALONE_SELECTION, null, null, index, null, null, null);
            if (selection.courseId() == null || selection.courseId() <= 0)
                return invalid(FailureCode.INVALID_STANDALONE_COURSE_ID, selection.courseId(), null,
                        index, null, null, null);
            if (selection.quantity() != 1)
                return invalid(FailureCode.INVALID_STANDALONE_QUANTITY, selection.courseId(), null,
                        index, null, null, null);
        }

        var coverage = new TreeMap<Long, CoverageSource>();
        for (int index = 0; index < input.standaloneCourses().size(); index++) {
            SelectedStandaloneCourse selection = input.standaloneCourses().get(index);
            CoverageSource source = new CoverageSource(SourceType.STANDALONE_COURSE, index, null, null);
            ValidationResult duplicate = addCoverage(coverage, selection.courseId(), source);
            if (duplicate != null) return duplicate;
        }
        for (int selectionIndex = 0; selectionIndex < input.combos().size(); selectionIndex++) {
            ComboSnapshot combo = input.combos().get(selectionIndex).combo();
            for (var component : combo.orderedComponents()) {
                CoverageSource source = new CoverageSource(SourceType.COMBO_COMPONENT, selectionIndex,
                        combo.comboId(), component.position());
                ValidationResult duplicate = addCoverage(coverage, component.course().courseId(), source);
                if (duplicate != null) return duplicate;
            }
        }

        if (input.ownedCourseIds() == null) return invalid(FailureCode.OWNED_COURSE_IDS_REQUIRED);
        Long invalidOwned = smallestInvalid(input.ownedCourseIds());
        if (invalidOwned != null)
            return invalid(FailureCode.INVALID_OWNED_COURSE_ID, invalidOwned, null, -1, null, null, null);
        for (Map.Entry<Long, CoverageSource> entry : coverage.entrySet()) {
            if (input.ownedCourseIds().contains(entry.getKey()))
                return invalid(FailureCode.COURSE_ALREADY_OWNED, entry.getKey(), entry.getValue().comboId(),
                        entry.getValue().selectionIndex(), entry.getValue().componentPosition(),
                        entry.getValue(), null);
        }

        if (input.activePendingCourseIds() == null) return invalid(FailureCode.PENDING_COURSE_IDS_REQUIRED);
        Long invalidPending = smallestInvalid(input.activePendingCourseIds());
        if (invalidPending != null)
            return invalid(FailureCode.INVALID_PENDING_COURSE_ID, invalidPending, null, -1, null, null, null);
        for (Map.Entry<Long, CoverageSource> entry : coverage.entrySet()) {
            if (input.activePendingCourseIds().contains(entry.getKey()))
                return invalid(FailureCode.COURSE_PENDING_PURCHASE, entry.getKey(), entry.getValue().comboId(),
                        entry.getValue().selectionIndex(), entry.getValue().componentPosition(),
                        entry.getValue(), null);
        }

        return new Valid(new CourseCoverage(coverage.entrySet().stream()
                .map(entry -> new CoveredCourse(entry.getKey(), entry.getValue())).toList()));
    }

    private static ValidationResult addCoverage(TreeMap<Long, CoverageSource> coverage, Long courseId,
                                                CoverageSource source) {
        CoverageSource first = coverage.putIfAbsent(courseId, source);
        return first == null ? null : invalid(FailureCode.DUPLICATE_COURSE_COVERAGE,
                courseId, source.comboId(), source.selectionIndex(), source.componentPosition(), first, source);
    }

    private static Long smallestInvalid(Set<Long> ids) {
        if (ids.contains(null)) return 0L;
        return ids.stream().filter(id -> id <= 0).min(Long::compareTo).orElse(null);
    }

    private static Invalid invalid(FailureCode code) {
        return invalid(code, null, null, -1, null, null, null);
    }

    private static Invalid invalid(FailureCode code, Long courseId, Long comboId, int selectionIndex,
                                   Integer componentPosition, CoverageSource first, CoverageSource conflicting) {
        return new Invalid(new ValidationFailure(code, courseId, comboId, selectionIndex,
                componentPosition, first, conflicting));
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static <T> List<T> immutableListAllowingNull(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <T> Set<T> immutableSetAllowingNull(Set<T> source) {
        return source == null ? null : Collections.unmodifiableSet(new HashSet<>(source));
    }
}
