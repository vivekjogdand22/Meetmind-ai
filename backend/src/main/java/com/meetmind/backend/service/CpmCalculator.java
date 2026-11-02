package com.meetmind.backend.service;

import com.meetmind.backend.exception.PlanValidationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


// ============================================================
// PERT + CPM CALCULATOR
//
// Pure mathematics: no database, no HTTP, no Spring.
// All durations are expressed in days.
//
// PERT: expected time = (optimistic + 4 * most likely + pessimistic) / 6
// CPM : forward pass, backward pass, slack and critical path
// ============================================================

public final class CpmCalculator
{
    // Values such as (2 + 4 * 4 + 6) / 6 are not always exact in floating
    // point, so "slack is zero" is tested with a tolerance.
    private static final double EPSILON = 1e-9;

    // Results are rounded to 4 decimal places before leaving this class.
    private static final double OUTPUT_SCALE = 10_000.0;


    private CpmCalculator()
    {
    }


    // ============================================================
    // INPUT AND OUTPUT TYPES
    // ============================================================

    public record TaskInput(
            long id,
            String name,
            double optimistic,
            double mostLikely,
            double pessimistic,
            List<Long> predecessorIds)
    {
    }

    // Start and finish values are days counted from the project start.
    public record TaskResult(
            long id,
            String name,
            double expectedTime,
            double variance,
            double earliestStart,
            double earliestFinish,
            double latestStart,
            double latestFinish,
            double slack,
            boolean critical)
    {
    }

    // tasks come back in the same order they were passed in.
    // criticalPath is one chain of critical tasks, first to last.
    public record Schedule(
            List<TaskResult> tasks,
            double projectDuration,
            List<Long> criticalPath,
            double projectVariance,
            double projectStandardDeviation)
    {
    }

    public record TaskShift(
            long id,
            String name,
            double baselineFinish,
            double newFinish,
            double shift)
    {
    }

    // scenario is the full schedule with the delay applied, so the delayed
    // task's expectedTime there already includes the extra days.
    public record WhatIfResult(
            long delayedTaskId,
            double delayDays,
            double baselineDuration,
            double newDuration,
            double projectDelay,
            boolean absorbedBySlack,
            List<TaskShift> shiftedTasks,
            Schedule scenario)
    {
    }

    private record Chain(
            List<Long> path,
            double variance)
    {
    }


    // ============================================================
    // PUBLIC API
    // ============================================================

    public static Schedule calculate(
            List<TaskInput> tasks)
    {
        return computeSchedule(
                tasks,
                null,
                0.0
        );
    }


    public static WhatIfResult whatIf(
            List<TaskInput> tasks,
            long delayedTaskId,
            double delayDays)
    {
        if (Double.isNaN(delayDays)
                || Double.isInfinite(delayDays)
                || delayDays < 0)
        {
            throw new PlanValidationException(
                    "Delay must be zero or a positive number of days"
            );
        }


        Schedule baseline =
                computeSchedule(
                        tasks,
                        null,
                        0.0
                );


        boolean taskExists =
                tasks.stream()
                        .anyMatch(task -> task.id() == delayedTaskId);

        if (!taskExists)
        {
            throw new PlanValidationException(
                    "The task to delay is not part of this plan"
            );
        }


        Schedule scenario =
                computeSchedule(
                        tasks,
                        delayedTaskId,
                        delayDays
                );


        Map<Long, TaskResult> baselineById = new HashMap<>();

        for (TaskResult result : baseline.tasks())
        {
            baselineById.put(result.id(), result);
        }


        List<TaskShift> shiftedTasks = new ArrayList<>();

        for (TaskResult result : scenario.tasks())
        {
            double oldFinish =
                    baselineById.get(result.id()).earliestFinish();

            double shift =
                    result.earliestFinish() - oldFinish;

            if (shift > EPSILON)
            {
                shiftedTasks.add(
                        new TaskShift(
                                result.id(),
                                result.name(),
                                oldFinish,
                                result.earliestFinish(),
                                round(shift)
                        )
                );
            }
        }


        double projectDelay =
                Math.max(
                        0.0,
                        scenario.projectDuration()
                                - baseline.projectDuration()
                );


        return new WhatIfResult(
                delayedTaskId,
                delayDays,
                baseline.projectDuration(),
                scenario.projectDuration(),
                round(projectDelay),
                projectDelay < EPSILON,
                shiftedTasks,
                scenario
        );
    }


    // Chance (0 to 1) that the whole project finishes within targetDays.
    // The total duration is treated as a normal distribution whose mean is
    // the CPM duration and whose variance is the sum of the variances of the
    // tasks on the critical path (the classic PERT assumption).
    public static double probabilityOfFinishingBy(
            Schedule schedule,
            double targetDays)
    {
        if (Double.isNaN(targetDays) || Double.isInfinite(targetDays))
        {
            throw new PlanValidationException(
                    "Target duration must be a number of days"
            );
        }


        double expected = schedule.projectDuration();

        double deviation = schedule.projectStandardDeviation();


        if (deviation < EPSILON)
        {
            return targetDays >= expected - EPSILON ? 1.0 : 0.0;
        }


        return normalCdf((targetDays - expected) / deviation);
    }


    public static double expectedTime(
            double optimistic,
            double mostLikely,
            double pessimistic)
    {
        return (optimistic + 4.0 * mostLikely + pessimistic) / 6.0;
    }


    public static double variance(
            double optimistic,
            double pessimistic)
    {
        double standardDeviation = (pessimistic - optimistic) / 6.0;

        return standardDeviation * standardDeviation;
    }


    // Standard normal cumulative distribution, Abramowitz and Stegun 26.2.17
    // (absolute error below 7.5e-8, plenty for a planning probability).
    static double normalCdf(double z)
    {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));

        double density =
                Math.exp(-z * z / 2.0) / Math.sqrt(2.0 * Math.PI);

        double polynomial =
                t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));

        double upperTail = density * polynomial;

        return z >= 0 ? 1.0 - upperTail : upperTail;
    }


    // ============================================================
    // SCHEDULE CALCULATION
    // ============================================================

    private static Schedule computeSchedule(
            List<TaskInput> tasks,
            Long delayedTaskId,
            double delayDays)
    {
        Map<Long, TaskInput> byId = indexAndValidate(tasks);

        Map<Long, List<Long>> successors = successorsOf(byId);

        List<Long> order = topologicalOrder(byId, successors);


        // PERT: expected duration of every task
        Map<Long, Double> duration = new HashMap<>();

        for (TaskInput task : tasks)
        {
            double expected =
                    expectedTime(
                            task.optimistic(),
                            task.mostLikely(),
                            task.pessimistic()
                    );

            if (delayedTaskId != null && delayedTaskId == task.id())
            {
                expected += delayDays;
            }

            duration.put(task.id(), expected);
        }


        // FORWARD PASS: a task can start once all its predecessors finish
        Map<Long, Double> earliestStart = new HashMap<>();
        Map<Long, Double> earliestFinish = new HashMap<>();

        double projectDuration = 0.0;

        for (Long id : order)
        {
            double start = 0.0;

            for (Long predecessorId : predecessorsOf(byId.get(id)))
            {
                start = Math.max(start, earliestFinish.get(predecessorId));
            }

            double finish = start + duration.get(id);

            earliestStart.put(id, start);
            earliestFinish.put(id, finish);

            projectDuration = Math.max(projectDuration, finish);
        }


        // BACKWARD PASS: a task must finish before its successors must start
        Map<Long, Double> latestStart = new HashMap<>();
        Map<Long, Double> latestFinish = new HashMap<>();

        for (int position = order.size() - 1; position >= 0; position--)
        {
            Long id = order.get(position);

            double finish = projectDuration;

            for (Long successorId : successors.get(id))
            {
                finish = Math.min(finish, latestStart.get(successorId));
            }

            latestFinish.put(id, finish);
            latestStart.put(id, finish - duration.get(id));
        }


        // SLACK: how long a task can slip without delaying the project
        Map<Long, Double> slack = new HashMap<>();
        Set<Long> critical = new HashSet<>();

        for (Long id : order)
        {
            double taskSlack = latestStart.get(id) - earliestStart.get(id);

            if (taskSlack < EPSILON)
            {
                taskSlack = 0.0;
                critical.add(id);
            }

            slack.put(id, taskSlack);
        }


        Chain chain =
                findCriticalChain(
                        byId,
                        order,
                        critical,
                        earliestStart,
                        earliestFinish,
                        projectDuration
                );


        List<TaskResult> results = new ArrayList<>();

        for (TaskInput task : tasks)
        {
            Long id = task.id();

            results.add(
                    new TaskResult(
                            task.id(),
                            task.name(),
                            round(duration.get(id)),
                            round(variance(task.optimistic(), task.pessimistic())),
                            round(earliestStart.get(id)),
                            round(earliestFinish.get(id)),
                            round(latestStart.get(id)),
                            round(latestFinish.get(id)),
                            round(slack.get(id)),
                            critical.contains(id)
                    )
            );
        }


        return new Schedule(
                results,
                round(projectDuration),
                chain.path(),
                round(chain.variance()),
                round(Math.sqrt(chain.variance()))
        );
    }


    // Several chains can be critical at the same time. The one with the
    // largest total variance is reported, because that is the one that
    // decides how uncertain the finish date is.
    private static Chain findCriticalChain(
            Map<Long, TaskInput> byId,
            List<Long> order,
            Set<Long> critical,
            Map<Long, Double> earliestStart,
            Map<Long, Double> earliestFinish,
            double projectDuration)
    {
        Map<Long, Double> chainVariance = new HashMap<>();
        Map<Long, Long> chainPredecessor = new HashMap<>();

        Long chainEnd = null;


        for (Long id : order)
        {
            if (!critical.contains(id))
            {
                continue;
            }


            TaskInput task = byId.get(id);

            double bestBefore = 0.0;

            Long bestPredecessor = null;


            for (Long predecessorId : predecessorsOf(task))
            {
                // the link only counts when the predecessor finishes exactly
                // when this task starts
                boolean tight =
                        critical.contains(predecessorId)
                                && Math.abs(
                                        earliestFinish.get(predecessorId)
                                                - earliestStart.get(id)
                                ) < EPSILON;

                if (tight
                        && (bestPredecessor == null
                        || chainVariance.get(predecessorId) > bestBefore))
                {
                    bestBefore = chainVariance.get(predecessorId);

                    bestPredecessor = predecessorId;
                }
            }


            double total =
                    bestBefore
                            + variance(task.optimistic(), task.pessimistic());

            chainVariance.put(id, total);
            chainPredecessor.put(id, bestPredecessor);


            boolean endsProject =
                    Math.abs(earliestFinish.get(id) - projectDuration) < EPSILON;

            if (endsProject
                    && (chainEnd == null || total > chainVariance.get(chainEnd)))
            {
                chainEnd = id;
            }
        }


        List<Long> path = new ArrayList<>();

        for (Long id = chainEnd; id != null; id = chainPredecessor.get(id))
        {
            path.add(id);
        }

        Collections.reverse(path);


        return new Chain(
                path,
                chainEnd == null ? 0.0 : chainVariance.get(chainEnd)
        );
    }


    // ============================================================
    // VALIDATION AND GRAPH HELPERS
    // ============================================================

    private static Map<Long, TaskInput> indexAndValidate(
            List<TaskInput> tasks)
    {
        if (tasks == null)
        {
            throw new PlanValidationException(
                    "A list of tasks is required"
            );
        }


        Map<Long, TaskInput> byId = new LinkedHashMap<>();

        for (TaskInput task : tasks)
        {
            if (byId.put(task.id(), task) != null)
            {
                throw new PlanValidationException(
                        "Duplicate task id: " + task.id()
                );
            }

            validateEstimates(task);
        }


        for (TaskInput task : tasks)
        {
            for (Long predecessorId : predecessorsOf(task))
            {
                if (predecessorId != null && predecessorId == task.id())
                {
                    throw new PlanValidationException(
                            "Task \"" + task.name()
                                    + "\" cannot depend on itself"
                    );
                }

                if (!byId.containsKey(predecessorId))
                {
                    throw new PlanValidationException(
                            "Task \"" + task.name()
                                    + "\" depends on an unknown task (id "
                                    + predecessorId + ")"
                    );
                }
            }
        }


        return byId;
    }


    private static void validateEstimates(TaskInput task)
    {
        double[] estimates =
                {
                        task.optimistic(),
                        task.mostLikely(),
                        task.pessimistic()
                };

        for (double estimate : estimates)
        {
            if (Double.isNaN(estimate)
                    || Double.isInfinite(estimate)
                    || estimate < 0)
            {
                throw new PlanValidationException(
                        "Estimates for \"" + task.name()
                                + "\" must be zero or positive numbers"
                );
            }
        }


        if (task.optimistic() > task.mostLikely()
                || task.mostLikely() > task.pessimistic())
        {
            throw new PlanValidationException(
                    "Estimates for \"" + task.name()
                            + "\" must satisfy optimistic <= most likely <= pessimistic"
            );
        }
    }


    private static Set<Long> predecessorsOf(TaskInput task)
    {
        if (task.predecessorIds() == null)
        {
            return Set.of();
        }

        return new LinkedHashSet<>(task.predecessorIds());
    }


    private static Map<Long, List<Long>> successorsOf(
            Map<Long, TaskInput> byId)
    {
        Map<Long, List<Long>> successors = new LinkedHashMap<>();

        for (Long id : byId.keySet())
        {
            successors.put(id, new ArrayList<>());
        }

        for (TaskInput task : byId.values())
        {
            for (Long predecessorId : predecessorsOf(task))
            {
                successors.get(predecessorId).add(task.id());
            }
        }

        return successors;
    }


    // Kahn's algorithm: repeatedly take tasks that have no unfinished
    // predecessors. If some tasks can never be taken, they wait on each
    // other in a loop (a dependency cycle).
    private static List<Long> topologicalOrder(
            Map<Long, TaskInput> byId,
            Map<Long, List<Long>> successors)
    {
        Map<Long, Integer> waitingOn = new LinkedHashMap<>();

        for (TaskInput task : byId.values())
        {
            waitingOn.put(task.id(), predecessorsOf(task).size());
        }


        Deque<Long> ready = new ArrayDeque<>();

        for (Map.Entry<Long, Integer> entry : waitingOn.entrySet())
        {
            if (entry.getValue() == 0)
            {
                ready.add(entry.getKey());
            }
        }


        List<Long> order = new ArrayList<>();

        while (!ready.isEmpty())
        {
            Long id = ready.poll();

            order.add(id);

            for (Long successorId : successors.get(id))
            {
                if (waitingOn.merge(successorId, -1, Integer::sum) == 0)
                {
                    ready.add(successorId);
                }
            }
        }


        if (order.size() < byId.size())
        {
            throw new PlanValidationException(
                    "Dependency cycle detected: "
                            + describeCycle(byId, order)
            );
        }


        return order;
    }


    // Names the tasks that form the loop, e.g. "Build → Test → Design → Build".
    // Tasks that merely sit downstream of the loop are left out.
    private static String describeCycle(
            Map<Long, TaskInput> byId,
            List<Long> ordered)
    {
        Set<Long> stuck = new LinkedHashSet<>(byId.keySet());

        stuck.removeAll(ordered);


        // Every stuck task still waits for at least one other stuck task, so
        // walking from predecessor to predecessor must eventually repeat.
        Map<Long, Integer> visitedAt = new HashMap<>();

        List<Long> walk = new ArrayList<>();

        Long current = stuck.iterator().next();

        while (!visitedAt.containsKey(current))
        {
            visitedAt.put(current, walk.size());

            walk.add(current);

            current = firstStuckPredecessor(byId.get(current), stuck);
        }


        List<Long> cycle =
                new ArrayList<>(
                        walk.subList(visitedAt.get(current), walk.size())
                );

        // the walk followed predecessors, so reverse it to read in the
        // order the tasks must be finished
        Collections.reverse(cycle);

        cycle.add(cycle.get(0));


        return cycle.stream()
                .map(id -> byId.get(id).name())
                .collect(Collectors.joining(" → "));
    }


    private static Long firstStuckPredecessor(
            TaskInput task,
            Set<Long> stuck)
    {
        for (Long predecessorId : predecessorsOf(task))
        {
            if (stuck.contains(predecessorId))
            {
                return predecessorId;
            }
        }

        throw new IllegalStateException(
                "No unresolved predecessor found for task " + task.id()
        );
    }


    private static double round(double value)
    {
        return Math.round(value * OUTPUT_SCALE) / OUTPUT_SCALE;
    }
}
