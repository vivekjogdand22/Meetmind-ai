package com.meetmind.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;


// ============================================================
// REQUEST AND RESPONSE SHAPES OF THE PROJECT-PLAN API
//
// These small records are exactly the JSON the frontend sends
// and receives. Database entities are never sent directly.
// All durations are in days.
// ============================================================

public final class PlanDto
{
    private PlanDto()
    {
    }


    // ============================================================
    // REQUESTS
    // ============================================================

    // The three estimates are either all given or all left out (a draft task).
    public record TaskRequest(

            @NotBlank(message = "name is required")
            @Size(max = 500, message = "name must be at most 500 characters")
            String name,

            @Size(max = 255, message = "owner must be at most 255 characters")
            String owner,

            @PositiveOrZero(message = "optimisticDays must be zero or more")
            Double optimisticDays,

            @PositiveOrZero(message = "mostLikelyDays must be zero or more")
            Double mostLikelyDays,

            @PositiveOrZero(message = "pessimisticDays must be zero or more")
            Double pessimisticDays,

            List<Long> predecessorIds)
    {
    }


    public record WhatIfRequest(

            @NotNull(message = "taskId is required")
            Long taskId,

            @NotNull(message = "delayDays is required")
            @PositiveOrZero(message = "delayDays must be zero or more")
            Double delayDays)
    {
    }


    // ============================================================
    // RESPONSES
    // ============================================================

    // The schedule fields (expectedDays and below) are null until every
    // task in the plan has its three estimates.
    public record TaskResponse(
            Long id,
            String name,
            String owner,
            String priority,
            String sourceDueDate,
            String evidence,
            Double optimisticDays,
            Double mostLikelyDays,
            Double pessimisticDays,
            List<Long> predecessorIds,
            Double expectedDays,
            Double varianceDays,
            Double earliestStart,
            Double earliestFinish,
            Double latestStart,
            Double latestFinish,
            Double slackDays,
            Boolean critical)
    {
    }


    // targetDays and probabilityOfFinishingByTarget are only filled when the
    // caller asked for them with the targetDays query parameter.
    public record ScheduleSummary(
            double projectDurationDays,
            List<Long> criticalPath,
            double projectVariance,
            double projectStandardDeviation,
            Double targetDays,
            Double probabilityOfFinishingByTarget)
    {
    }


    // schedule is null while readyToSchedule is false.
    public record PlanResponse(
            Long meetingId,
            List<TaskResponse> tasks,
            boolean readyToSchedule,
            List<String> tasksMissingEstimates,
            ScheduleSummary schedule)
    {
    }


    public record TaskShift(
            Long id,
            String name,
            double baselineFinish,
            double newFinish,
            double shiftDays)
    {
    }


    public record ScenarioTask(
            Long id,
            String name,
            double earliestStart,
            double earliestFinish,
            double slackDays,
            boolean critical)
    {
    }


    // Nothing in this response is saved. It describes a "what if".
    public record WhatIfResponse(
            Long delayedTaskId,
            String delayedTaskName,
            double delayDays,
            double baselineDurationDays,
            double newDurationDays,
            double projectDelayDays,
            boolean absorbedBySlack,
            double slackBeforeDelayDays,
            List<TaskShift> shiftedTasks,
            List<ScenarioTask> scenarioTasks)
    {
    }
}
