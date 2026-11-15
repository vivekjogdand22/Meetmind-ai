package com.meetmind.backend.controller;

import com.meetmind.backend.dto.PlanDto;
import com.meetmind.backend.service.PlanService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import com.meetmind.backend.security.CurrentUser;
import com.meetmind.backend.service.MeetingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


// ============================================================
// PROJECT-PLAN (PERT / CPM) ENDPOINTS OF ONE MEETING
//
// GET    /api/meetings/{meetingId}/plan                  read the plan and its schedule
// POST   /api/meetings/{meetingId}/plan                  copy the AI action items into a plan
// DELETE /api/meetings/{meetingId}/plan                  remove the whole plan
// POST   /api/meetings/{meetingId}/plan/tasks            add a task
// PUT    /api/meetings/{meetingId}/plan/tasks/{taskId}   edit a task
// DELETE /api/meetings/{meetingId}/plan/tasks/{taskId}   remove a task
// POST   /api/meetings/{meetingId}/plan/what-if          simulate a delayed task (saves nothing)
// ============================================================

@RestController
@Tag(name = "Project plan", description = "PERT/CPM planning for the action items of one meeting")
@RequestMapping("/api/meetings/{meetingId}/plan")
public class PlanController
{
    private final PlanService planService;

    private final MeetingService meetingService;


    public PlanController(
            PlanService planService,
            MeetingService meetingService)
    {
        this.planService = planService;

        this.meetingService = meetingService;
    }


    // A plan belongs to a meeting, so it belongs to the meeting's owner.
    // Somebody else's meeting is "not found".
    private void checkOwner(Jwt jwt, Long meetingId)
    {
        meetingService.requireOwned(meetingId, CurrentUser.id(jwt));
    }


    // targetDays is optional: with it, the answer also says how likely the
    // project is to finish within that many days.
    @Operation(summary = "Get the plan, schedule and critical path (optionally the chance of finishing within targetDays)")
    @GetMapping
    public PlanDto.PlanResponse getPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId,
            @RequestParam(required = false) Double targetDays)
    {
        checkOwner(jwt, meetingId);

        return planService.getPlan(meetingId, targetDays);
    }


    @Operation(summary = "Create the plan from the meeting's action items")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDto.PlanResponse createPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId)
    {
        checkOwner(jwt, meetingId);

        return planService.createFromActionItems(meetingId);
    }


    @Operation(summary = "Delete the whole plan")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId)
    {
        checkOwner(jwt, meetingId);

        planService.deletePlan(meetingId);
    }


    @Operation(summary = "Add a task with time estimates and dependencies")
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDto.PlanResponse addTask(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId,
            @Valid @RequestBody PlanDto.TaskRequest request)
    {
        checkOwner(jwt, meetingId);

        return planService.addTask(meetingId, request);
    }


    @Operation(summary = "Change a task")
    @PutMapping("/tasks/{taskId}")
    public PlanDto.PlanResponse updateTask(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId,
            @PathVariable Long taskId,
            @Valid @RequestBody PlanDto.TaskRequest request)
    {
        checkOwner(jwt, meetingId);

        return planService.updateTask(meetingId, taskId, request);
    }


    @Operation(summary = "Remove a task")
    @DeleteMapping("/tasks/{taskId}")
    public PlanDto.PlanResponse deleteTask(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId,
            @PathVariable Long taskId)
    {
        checkOwner(jwt, meetingId);

        return planService.deleteTask(meetingId, taskId);
    }


    @Operation(summary = "Simulate a delay of one task (nothing is saved)")
    @PostMapping("/what-if")
    public PlanDto.WhatIfResponse whatIf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long meetingId,
            @Valid @RequestBody PlanDto.WhatIfRequest request)
    {
        checkOwner(jwt, meetingId);

        return planService.whatIf(meetingId, request);
    }
}
