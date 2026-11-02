package com.drawingcourse.backend.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.drawingcourse.backend.entity.ComboCheckoutValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class ComboCheckoutValidatorTest {

    @Test
    void acceptsValidSingleComboAndPreservesComponentOrder() {
        ComboDefinition combo = combo(10, 3, "Foundations", 450_000, 7, 2, 9);

        Valid result = valid(input(List.of(), List.of(selected(combo)), Set.of(), Set.of()));

        assertEquals(List.of(7, 2, 9), combo.orderedCourseIds());
        assertEquals(List.of(2, 7, 9), result.coverage().courseIds());
        assertEquals(1, result.coverage().courses().getFirst().source().componentIndex());
    }

    @Test
    void acceptsMultipleNonOverlappingCombos() {
        Valid result = valid(input(List.of(), List.of(
                selected(combo(10, 1, "A", 1, 1, 3)),
                selected(combo(20, 1, "B", 2, 2, 4))), Set.of(), Set.of()));

        assertEquals(List.of(1, 2, 3, 4), result.coverage().courseIds());
    }

    @Test
    void acceptsStandaloneCourseAndNonOverlappingCombo() {
        Valid result = valid(input(List.of(course(1)),
                List.of(selected(combo(10, 0, "Combo", 0, 2, 3))), Set.of(), Set.of()));

        assertEquals(List.of(1, 2, 3), result.coverage().courseIds());
        assertEquals(SourceType.STANDALONE_COURSE, result.coverage().courses().getFirst().source().sourceType());
    }

    @Test
    void rejectsEmptyComponentList() {
        assertFailure(input(List.of(), List.of(selected(combo(10, 0, "Empty", 0))), Set.of(), Set.of()),
                FailureCode.EMPTY_COMBO_COMPONENTS);
    }

    @Test
    void rejectsDuplicateComponentCourseWithStableCoordinates() {
        Invalid result = invalid(input(List.of(),
                List.of(selected(combo(10, 0, "Duplicate", 0, 4, 5, 4))), Set.of(), Set.of()));

        assertEquals(FailureCode.DUPLICATE_COMBO_COMPONENT, result.failure().code());
        assertEquals(4, result.failure().courseId());
        assertEquals(0, result.failure().firstSource().componentIndex());
        assertEquals(2, result.failure().conflictingSource().componentIndex());
    }

    @Test
    void rejectsInvalidComponentCourseId() {
        Invalid result = invalid(input(List.of(),
                List.of(selected(combo(10, 0, "Bad component", 0, 1, 0))), Set.of(), Set.of()));
        assertEquals(FailureCode.INVALID_COMBO_COMPONENT_COURSE_ID, result.failure().code());
        assertEquals(1, result.failure().componentIndex());
    }

    @Test
    void rejectsInvalidComboId() {
        assertFailure(input(List.of(), List.of(selected(combo(0, 0, "Bad", 0, 1))), Set.of(), Set.of()),
                FailureCode.INVALID_COMBO_ID);
    }

    @Test
    void rejectsInvalidComboQuantity() {
        assertFailure(input(List.of(), List.of(new SelectedComboLine(combo(1, 0, "Bad", 0, 1), 2)),
                Set.of(), Set.of()), FailureCode.INVALID_COMBO_QUANTITY);
    }

    @Test
    void rejectsNegativeWholeVndPrice() {
        assertFailure(input(List.of(), List.of(selected(combo(1, 0, "Bad", -1, 1))), Set.of(), Set.of()),
                FailureCode.INVALID_COMBO_PRICE);
    }

    @Test
    void rejectsInvalidNameBeforeInvalidVersion() {
        ValidationInput input = input(List.of(),
                List.of(selected(combo(1, -1, " ", 0, 1))), Set.of(), Set.of());
        assertFailure(input, FailureCode.INVALID_COMBO_NAME);

        assertFailure(input(List.of(), List.of(selected(combo(1, -1, "Named", 0, 1))), Set.of(), Set.of()),
                FailureCode.INVALID_COMBO_VERSION);
    }

    @Test
    void rejectsStandaloneToComboOverlap() {
        Invalid result = invalid(input(List.of(course(5)),
                List.of(selected(combo(10, 0, "Combo", 1, 5, 6))), Set.of(), Set.of()));

        assertEquals(FailureCode.DUPLICATE_COURSE_COVERAGE, result.failure().code());
        assertEquals(SourceType.STANDALONE_COURSE, result.failure().firstSource().sourceType());
        assertEquals(SourceType.COMBO_COMPONENT, result.failure().conflictingSource().sourceType());
    }

    @Test
    void rejectsComboToComboOverlap() {
        Invalid result = invalid(input(List.of(), List.of(
                selected(combo(10, 0, "First", 1, 1, 2)),
                selected(combo(20, 0, "Second", 1, 2, 3))), Set.of(), Set.of()));

        assertEquals(FailureCode.DUPLICATE_COURSE_COVERAGE, result.failure().code());
        assertEquals(10, result.failure().firstSource().comboId());
        assertEquals(20, result.failure().conflictingSource().comboId());
    }

    @Test
    void rejectsRepeatedStandaloneCourse() {
        Invalid result = invalid(input(List.of(course(3), course(3)), List.of(), Set.of(), Set.of()));
        assertEquals(FailureCode.DUPLICATE_COURSE_COVERAGE, result.failure().code());
        assertEquals(0, result.failure().firstSource().selectionIndex());
        assertEquals(1, result.failure().conflictingSource().selectionIndex());
    }

    @Test
    void rejectsEntireSelectionForOwnedComponent() {
        Invalid result = invalid(input(List.of(course(9)),
                List.of(selected(combo(10, 0, "Combo", 1, 2, 3))), Set.of(3), Set.of()));

        assertEquals(FailureCode.COURSE_ALREADY_OWNED, result.failure().code());
        assertEquals(3, result.failure().courseId());
        assertEquals(10, result.failure().comboId());
    }

    @Test
    void rejectsEntireSelectionForPendingComponent() {
        Invalid result = invalid(input(List.of(course(9)),
                List.of(selected(combo(10, 0, "Combo", 1, 2, 3))), Set.of(), Set.of(2)));

        assertEquals(FailureCode.COURSE_PENDING_PURCHASE, result.failure().code());
        assertEquals(2, result.failure().courseId());
        assertEquals(10, result.failure().comboId());
    }

    @Test
    void structuralFailureWinsOverDuplicateOwnershipAndPendingFailures() {
        ValidationInput input = input(List.of(course(2), course(2)),
                List.of(selected(combo(10, -1, " ", -1, 2, 2))), Set.of(2), Set.of(2));

        assertFailure(input, FailureCode.INVALID_COMBO_PRICE);
    }

    @Test
    void duplicateWinsBeforeOwnedAndOwnedWinsBeforePending() {
        assertFailure(input(List.of(course(2), course(2)), List.of(), Set.of(2), Set.of(2)),
                FailureCode.DUPLICATE_COURSE_COVERAGE);
        assertFailure(input(List.of(course(2)), List.of(), Set.of(2), Set.of(2)),
                FailureCode.COURSE_ALREADY_OWNED);
    }

    @Test
    void coverageOutputAndConflictSelectionAreDeterministic() {
        ValidationInput validInput = input(List.of(course(30)), List.of(
                selected(combo(2, 0, "Second", 0, 20, 10))), Set.of(), Set.of());
        assertEquals(List.of(10, 20, 30), valid(validInput).coverage().courseIds());

        Invalid owned = invalid(input(List.of(course(30)), List.of(
                selected(combo(2, 0, "Second", 0, 20, 10))), Set.of(20, 10), Set.of()));
        assertEquals(10, owned.failure().courseId());
    }

    @Test
    void inputCollectionsAreDefensivelyCopiedAndOutputsAreImmutable() {
        var components = new ArrayList<>(List.of(1, 2));
        var combos = new ArrayList<>(List.of(selected(new ComboDefinition(10, 0, "Combo", 1, components))));
        var standalone = new ArrayList<>(List.of(course(3)));
        var owned = new HashSet<Integer>();
        var pending = new HashSet<Integer>();
        ValidationInput input = new ValidationInput(standalone, combos, owned, pending);

        components.add(99);
        combos.clear();
        standalone.clear();
        owned.add(1);
        pending.add(2);

        Valid result = valid(input);
        assertEquals(List.of(1, 2, 3), result.coverage().courseIds());
        assertThrows(UnsupportedOperationException.class, () -> input.combos().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.combos().getFirst().combo().orderedCourseIds().add(4));
        assertThrows(UnsupportedOperationException.class, () -> result.coverage().courses().clear());
    }

    @Test
    void acceptsLargeBoundedComponentSetWithoutEmbeddingAProductionLimit() {
        List<Integer> components = IntStream.rangeClosed(1, 2_000).boxed().toList();
        ComboDefinition combo = new ComboDefinition(1, 0, "Large trusted Combo", 0, components);

        Valid result = valid(input(List.of(), List.of(selected(combo)), Set.of(), Set.of()));

        assertEquals(2_000, result.coverage().courses().size());
        assertEquals(1, result.coverage().courseIds().getFirst());
        assertEquals(2_000, result.coverage().courseIds().getLast());
    }

    @Test
    void validatesStandaloneIdentityAndQuantity() {
        assertFailure(input(List.of(new SelectedStandaloneCourseLine(0, 1)), List.of(), Set.of(), Set.of()),
                FailureCode.INVALID_STANDALONE_COURSE_ID);
        assertFailure(input(List.of(new SelectedStandaloneCourseLine(1, 2)), List.of(), Set.of(), Set.of()),
                FailureCode.INVALID_STANDALONE_QUANTITY);
    }

    private static ComboDefinition combo(int id, long version, String name, long price, Integer... courses) {
        return new ComboDefinition(id, version, name, price, List.of(courses));
    }

    private static SelectedComboLine selected(ComboDefinition combo) {
        return new SelectedComboLine(combo, 1);
    }

    private static SelectedStandaloneCourseLine course(int id) {
        return new SelectedStandaloneCourseLine(id, 1);
    }

    private static ValidationInput input(List<SelectedStandaloneCourseLine> courses, List<SelectedComboLine> combos,
                                         Set<Integer> owned, Set<Integer> pending) {
        return new ValidationInput(courses, combos, owned, pending);
    }

    private static Valid valid(ValidationInput input) {
        return assertInstanceOf(Valid.class, ComboCheckoutValidator.validate(input));
    }

    private static Invalid invalid(ValidationInput input) {
        return assertInstanceOf(Invalid.class, ComboCheckoutValidator.validate(input));
    }

    private static void assertFailure(ValidationInput input, FailureCode code) {
        assertEquals(code, invalid(input).failure().code());
    }
}
