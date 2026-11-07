package com.smarsh.discoveryhub.caseapp.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @ParameterizedTest + @MethodSource: runs the SAME test logic once per entry in allTransitions()
// (16 combinations = every CaseStatus × CaseStatus pair) instead of writing 16 near-identical
// @Test methods — this is white-box testing (the training's "test with full knowledge of internal
// logic" definition): the test data was deliberately built to cover EVERY cell of
// CaseStateMachine's transition table, not just a couple of happy-path examples.
class CaseStateMachineTest {
    static Stream<TransitionVerdict> allTransitions() {
        return Stream.of(
                new TransitionVerdict(CaseStatus.DRAFT, CaseStatus.DRAFT, false),
                new TransitionVerdict(CaseStatus.DRAFT, CaseStatus.ACTIVE, true),
                new TransitionVerdict(CaseStatus.DRAFT, CaseStatus.UNDER_REVIEW, false),
                new TransitionVerdict(CaseStatus.DRAFT, CaseStatus.CLOSED, false),
                new TransitionVerdict(CaseStatus.ACTIVE, CaseStatus.DRAFT, false),
                new TransitionVerdict(CaseStatus.ACTIVE, CaseStatus.ACTIVE, false),
                new TransitionVerdict(CaseStatus.ACTIVE, CaseStatus.UNDER_REVIEW, true),
                new TransitionVerdict(CaseStatus.ACTIVE, CaseStatus.CLOSED, false),
                new TransitionVerdict(CaseStatus.UNDER_REVIEW, CaseStatus.DRAFT, false),
                new TransitionVerdict(CaseStatus.UNDER_REVIEW, CaseStatus.ACTIVE, false),
                new TransitionVerdict(CaseStatus.UNDER_REVIEW, CaseStatus.UNDER_REVIEW, false),
                new TransitionVerdict(CaseStatus.UNDER_REVIEW, CaseStatus.CLOSED, true),
                new TransitionVerdict(CaseStatus.CLOSED, CaseStatus.DRAFT, false),
                new TransitionVerdict(CaseStatus.CLOSED, CaseStatus.ACTIVE, false),
                new TransitionVerdict(CaseStatus.CLOSED, CaseStatus.UNDER_REVIEW, false),
                new TransitionVerdict(CaseStatus.CLOSED, CaseStatus.CLOSED, false)
        );
    }

    @ParameterizedTest
    @MethodSource("allTransitions")
    void enforcesEveryDesignTransition(TransitionVerdict verdict) {
        if (verdict.legal()) {
            assertThatCode(() -> CaseStateMachine.assertTransition(verdict.from(), verdict.to()))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> CaseStateMachine.assertTransition(verdict.from(), verdict.to()))
                    .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class);
        }
    }

    // A local record as a tiny, self-documenting test-data value object — clearer at the call site
    // than an unlabeled Object[] or a 3-tuple, and gets equals/toString for free if assertions ever need it.
    record TransitionVerdict(CaseStatus from, CaseStatus to, boolean legal) {}
}
