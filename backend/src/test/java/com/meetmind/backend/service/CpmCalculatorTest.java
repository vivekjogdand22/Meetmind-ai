package com.meetmind.backend.service;

import com.meetmind.backend.exception.PlanValidationException;
import com.meetmind.backend.service.CpmCalculator.Schedule;
import com.meetmind.backend.service.CpmCalculator.TaskInput;
import com.meetmind.backend.service.CpmCalculator.TaskResult;
import com.meetmind.backend.service.CpmCalculator.WhatIfResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CpmCalculatorTest
{
    private static final double DELTA = 0.001;


    // ============================================================
    // HELPERS
    // ============================================================

    private static TaskInput task(
            long id,
            String name,
            double optimistic,
            double mostLikely,
            double pessimistic,
            Long... predecessors)
    {
        return new TaskInput(
                id,
                name,
                optimistic,
                mostLikely,
                pessimistic,
                List.of(predecessors)
        );
    }


    private static TaskInput fixed(
            long id,
            String name,
            double days,
            Long... predecessors)
    {
        return task(id, name, days, days, days, predecessors);
    }


    // The four-task example from the project brief:
    // frontend design and backend APIs -> integration testing -> demo
    private static List<TaskInput> fourTaskExample()
    {
        return List.of(
                task(1, "Frontend design", 2, 4, 6),
                task(2, "Backend APIs", 3, 5, 13),
                task(3, "Integration testing", 1, 2, 3, 1L, 2L),
                task(4, "Final demonstration", 1, 1, 1, 3L)
        );
    }


    private static TaskResult find(Schedule schedule, long id)
    {
        return schedule.tasks()
                .stream()
                .filter(result -> result.id() == id)
                .findFirst()
                .orElseThrow();
    }


    private static void assertTimes(
            TaskResult result,
            double earliestStart,
            double earliestFinish,
            double latestStart,
            double latestFinish,
            double slack,
            boolean critical)
    {
        String name = result.name();

        assertEquals(earliestStart, result.earliestStart(), DELTA, "earliest start of " + name);
        assertEquals(earliestFinish, result.earliestFinish(), DELTA, "earliest finish of " + name);
        assertEquals(latestStart, result.latestStart(), DELTA, "latest start of " + name);
        assertEquals(latestFinish, result.latestFinish(), DELTA, "latest finish of " + name);
        assertEquals(slack, result.slack(), DELTA, "slack of " + name);
        assertEquals(critical, result.critical(), "critical flag of " + name);
    }


    private static PlanValidationException assertRejected(List<TaskInput> tasks)
    {
        return assertThrows(
                PlanValidationException.class,
                () -> CpmCalculator.calculate(tasks)
        );
    }


    // ============================================================
    // PERT
    // ============================================================

    @Test
    void expectedTimeIsTheWeightedAverageOfTheThreeEstimates()
    {
        assertEquals(4.0, CpmCalculator.expectedTime(2, 4, 6), DELTA);
        assertEquals(6.0, CpmCalculator.expectedTime(3, 5, 13), DELTA);
        assertEquals(2.0, CpmCalculator.expectedTime(1, 2, 3), DELTA);
        assertEquals(1.0, CpmCalculator.expectedTime(1, 1, 1), DELTA);
    }


    @Test
    void varianceIsTheSquaredOneSixthOfTheRange()
    {
        assertEquals(0.4444, CpmCalculator.variance(2, 6), DELTA);
        assertEquals(2.7778, CpmCalculator.variance(3, 13), DELTA);
        assertEquals(0.0, CpmCalculator.variance(1, 1), DELTA);
    }


    // ============================================================
    // CPM
    // ============================================================

    @Test
    void fourTaskExampleMatchesTheHandCalculation()
    {
        Schedule schedule = CpmCalculator.calculate(fourTaskExample());

        assertEquals(9.0, schedule.projectDuration(), DELTA);

        assertTimes(find(schedule, 1), 0, 4, 2, 6, 2, false);
        assertTimes(find(schedule, 2), 0, 6, 0, 6, 0, true);
        assertTimes(find(schedule, 3), 6, 8, 6, 8, 0, true);
        assertTimes(find(schedule, 4), 8, 9, 8, 9, 0, true);

        assertEquals(List.of(2L, 3L, 4L), schedule.criticalPath());

        assertEquals(2.8889, schedule.projectVariance(), DELTA);
        assertEquals(1.6997, schedule.projectStandardDeviation(), DELTA);
    }


    @Test
    void textbookExampleWithFixedDurations()
    {
        // A(3) -> B(4) -> D(5) -> F(2)
        // A(3) -> C(2) -> E(1) -> F(2)
        List<TaskInput> tasks =
                List.of(
                        fixed(1, "A", 3),
                        fixed(2, "B", 4, 1L),
                        fixed(3, "C", 2, 1L),
                        fixed(4, "D", 5, 2L),
                        fixed(5, "E", 1, 3L),
                        fixed(6, "F", 2, 4L, 5L)
                );

        Schedule schedule = CpmCalculator.calculate(tasks);

        assertEquals(14.0, schedule.projectDuration(), DELTA);

        assertTimes(find(schedule, 1), 0, 3, 0, 3, 0, true);
        assertTimes(find(schedule, 2), 3, 7, 3, 7, 0, true);
        assertTimes(find(schedule, 3), 3, 5, 9, 11, 6, false);
        assertTimes(find(schedule, 4), 7, 12, 7, 12, 0, true);
        assertTimes(find(schedule, 5), 5, 6, 11, 12, 6, false);
        assertTimes(find(schedule, 6), 12, 14, 12, 14, 0, true);

        assertEquals(List.of(1L, 2L, 4L, 6L), schedule.criticalPath());
    }


    @Test
    void inputOrderDoesNotMatter()
    {
        List<TaskInput> reversed = new ArrayList<>(fourTaskExample());

        Collections.reverse(reversed);

        Schedule schedule = CpmCalculator.calculate(reversed);

        assertEquals(9.0, schedule.projectDuration(), DELTA);
        assertEquals(List.of(2L, 3L, 4L), schedule.criticalPath());
        assertTimes(find(schedule, 1), 0, 4, 2, 6, 2, false);

        // results keep the order they were passed in
        assertEquals(4L, schedule.tasks().get(0).id());
    }


    @Test
    void duplicatePredecessorsAreIgnored()
    {
        List<TaskInput> tasks =
                List.of(
                        fixed(1, "A", 2),
                        fixed(2, "B", 3, 1L, 1L)
                );

        Schedule schedule = CpmCalculator.calculate(tasks);

        assertEquals(5.0, schedule.projectDuration(), DELTA);
    }


    @Test
    void parallelCriticalPathsAreAllFlaggedAndTheMoreUncertainOneIsReported()
    {
        // A and B both take 2 days, so both are critical for C.
        // B has the wider estimate range, so it carries more variance.
        List<TaskInput> tasks =
                List.of(
                        task(1, "A", 1, 2, 3),
                        task(2, "B", 0, 2, 4),
                        task(3, "C", 1, 1, 1, 1L, 2L)
                );

        Schedule schedule = CpmCalculator.calculate(tasks);

        assertTrue(find(schedule, 1).critical());
        assertTrue(find(schedule, 2).critical());
        assertTrue(find(schedule, 3).critical());

        assertEquals(List.of(2L, 3L), schedule.criticalPath());
        assertEquals(0.4444, schedule.projectVariance(), DELTA);
    }


    @Test
    void singleTaskIsCritical()
    {
        Schedule schedule =
                CpmCalculator.calculate(List.of(task(7, "Only task", 1, 2, 3)));

        assertEquals(2.0, schedule.projectDuration(), DELTA);
        assertTimes(find(schedule, 7), 0, 2, 0, 2, 0, true);
        assertEquals(List.of(7L), schedule.criticalPath());
    }


    @Test
    void zeroDurationMilestoneIsAllowed()
    {
        List<TaskInput> tasks =
                List.of(
                        fixed(1, "Kick-off", 0),
                        fixed(2, "Build", 3, 1L)
                );

        Schedule schedule = CpmCalculator.calculate(tasks);

        assertEquals(3.0, schedule.projectDuration(), DELTA);
        assertEquals(List.of(1L, 2L), schedule.criticalPath());
    }


    @Test
    void emptyPlanHasNoDuration()
    {
        Schedule schedule = CpmCalculator.calculate(List.of());

        assertEquals(0.0, schedule.projectDuration(), DELTA);
        assertTrue(schedule.tasks().isEmpty());
        assertTrue(schedule.criticalPath().isEmpty());
    }


    // ============================================================
    // VALIDATION
    // ============================================================

    @Test
    void cycleIsRejectedAndNamesTheTasksInTheLoop()
    {
        // Design -> Build -> Test -> Design
        List<TaskInput> tasks =
                List.of(
                        fixed(1, "Design", 1, 3L),
                        fixed(2, "Build", 1, 1L),
                        fixed(3, "Test", 1, 2L)
                );

        PlanValidationException error = assertRejected(tasks);

        assertTrue(error.getMessage().contains("cycle"));
        assertTrue(error.getMessage().contains("Design"));
        assertTrue(error.getMessage().contains("Build"));
        assertTrue(error.getMessage().contains("Test"));
    }


    @Test
    void cycleMessageLeavesOutTasksThatAreOnlyDownstreamOfTheLoop()
    {
        // Kickoff feeds the loop, Release waits on it, neither is in it.
        List<TaskInput> tasks =
                List.of(
                        fixed(11, "Release", 1, 3L),
                        fixed(10, "Kickoff", 1),
                        fixed(1, "Design", 1, 3L, 10L),
                        fixed(2, "Build", 1, 1L),
                        fixed(3, "Test", 1, 2L)
                );

        PlanValidationException error = assertRejected(tasks);

        assertTrue(error.getMessage().contains("Design"));
        assertTrue(error.getMessage().contains("Build"));
        assertTrue(error.getMessage().contains("Test"));
        assertFalse(error.getMessage().contains("Release"));
        assertFalse(error.getMessage().contains("Kickoff"));
    }


    @Test
    void taskCannotDependOnItself()
    {
        PlanValidationException error =
                assertRejected(List.of(fixed(1, "Loop", 1, 1L)));

        assertTrue(error.getMessage().contains("itself"));
    }


    @Test
    void unknownPredecessorIsRejected()
    {
        PlanValidationException error =
                assertRejected(List.of(fixed(1, "A", 1, 99L)));

        assertTrue(error.getMessage().contains("unknown"));
    }


    @Test
    void duplicateTaskIdIsRejected()
    {
        assertRejected(List.of(fixed(1, "A", 1), fixed(1, "B", 1)));
    }


    @Test
    void estimatesMustBeOrdered()
    {
        PlanValidationException error =
                assertRejected(List.of(task(1, "Bad", 5, 3, 8)));

        assertTrue(error.getMessage().contains("optimistic"));

        assertRejected(List.of(task(1, "Bad", 1, 9, 8)));
    }


    @Test
    void negativeAndNonNumericEstimatesAreRejected()
    {
        assertRejected(List.of(task(1, "Negative", -1, 2, 3)));
        assertRejected(List.of(task(1, "NaN", Double.NaN, 2, 3)));
        assertRejected(List.of(task(1, "Infinite", 1, 2, Double.POSITIVE_INFINITY)));
    }


    // ============================================================
    // WHAT-IF (DELAYED TASK IMPACT)
    // ============================================================

    @Test
    void delayingACriticalTaskDelaysTheWholeProject()
    {
        WhatIfResult result = CpmCalculator.whatIf(fourTaskExample(), 2, 2);

        assertEquals(9.0, result.baselineDuration(), DELTA);
        assertEquals(11.0, result.newDuration(), DELTA);
        assertEquals(2.0, result.projectDelay(), DELTA);
        assertFalse(result.absorbedBySlack());

        // Backend APIs, Integration testing and the final demonstration move
        assertEquals(3, result.shiftedTasks().size());

        CpmCalculator.TaskShift demo =
                result.shiftedTasks()
                        .stream()
                        .filter(shift -> shift.id() == 4)
                        .findFirst()
                        .orElseThrow();

        assertEquals(9.0, demo.baselineFinish(), DELTA);
        assertEquals(11.0, demo.newFinish(), DELTA);
        assertEquals(2.0, demo.shift(), DELTA);
    }


    @Test
    void delayWithinSlackIsAbsorbed()
    {
        // Frontend design has 2 days of slack
        WhatIfResult result = CpmCalculator.whatIf(fourTaskExample(), 1, 2);

        assertEquals(9.0, result.newDuration(), DELTA);
        assertEquals(0.0, result.projectDelay(), DELTA);
        assertTrue(result.absorbedBySlack());

        // only the delayed task itself moves, and its slack is now used up
        assertEquals(1, result.shiftedTasks().size());
        assertEquals(1L, result.shiftedTasks().get(0).id());
        assertEquals(0.0, find(result.scenario(), 1).slack(), DELTA);
        assertTrue(find(result.scenario(), 1).critical());
    }


    @Test
    void delayBeyondSlackDelaysTheProjectByTheRemainder()
    {
        // 3 days of delay minus 2 days of slack = 1 day late
        WhatIfResult result = CpmCalculator.whatIf(fourTaskExample(), 1, 3);

        assertEquals(10.0, result.newDuration(), DELTA);
        assertEquals(1.0, result.projectDelay(), DELTA);
        assertFalse(result.absorbedBySlack());
        assertEquals(3, result.shiftedTasks().size());
    }


    @Test
    void whatIfDoesNotChangeTheBaseline()
    {
        List<TaskInput> tasks = fourTaskExample();

        CpmCalculator.whatIf(tasks, 2, 5);

        assertEquals(9.0, CpmCalculator.calculate(tasks).projectDuration(), DELTA);
    }


    @Test
    void whatIfRejectsBadInput()
    {
        List<TaskInput> tasks = fourTaskExample();

        assertThrows(PlanValidationException.class, () -> CpmCalculator.whatIf(tasks, 2, -1));
        assertThrows(PlanValidationException.class, () -> CpmCalculator.whatIf(tasks, 2, Double.NaN));
        assertThrows(PlanValidationException.class, () -> CpmCalculator.whatIf(tasks, 99, 1));
    }


    // ============================================================
    // DEADLINE PROBABILITY
    // ============================================================

    @Test
    void normalCdfMatchesKnownValues()
    {
        assertEquals(0.5, CpmCalculator.normalCdf(0.0), 1e-6);
        assertEquals(0.841345, CpmCalculator.normalCdf(1.0), 1e-6);
        assertEquals(0.158655, CpmCalculator.normalCdf(-1.0), 1e-6);
        assertEquals(0.975002, CpmCalculator.normalCdf(1.96), 1e-6);
        assertEquals(0.024998, CpmCalculator.normalCdf(-1.96), 1e-6);
    }


    @Test
    void probabilityOfFinishingByADeadline()
    {
        Schedule schedule = CpmCalculator.calculate(fourTaskExample());

        // the expected duration itself is a coin flip
        assertEquals(0.5, CpmCalculator.probabilityOfFinishingBy(schedule, 9), 0.001);

        // one day of buffer on a 1.70-day standard deviation
        assertEquals(0.722, CpmCalculator.probabilityOfFinishingBy(schedule, 10), 0.005);

        assertTrue(CpmCalculator.probabilityOfFinishingBy(schedule, 20) > 0.9999);
        assertTrue(CpmCalculator.probabilityOfFinishingBy(schedule, 0) < 0.001);
    }


    @Test
    void probabilityWithNoUncertaintyIsAllOrNothing()
    {
        Schedule schedule =
                CpmCalculator.calculate(
                        List.of(fixed(1, "A", 3), fixed(2, "B", 4, 1L))
                );

        assertEquals(1.0, CpmCalculator.probabilityOfFinishingBy(schedule, 7), 0.0);
        assertEquals(1.0, CpmCalculator.probabilityOfFinishingBy(schedule, 8), 0.0);
        assertEquals(0.0, CpmCalculator.probabilityOfFinishingBy(schedule, 6.9), 0.0);
    }
}
