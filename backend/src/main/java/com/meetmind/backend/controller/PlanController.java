package com.meetmind.backend.controller;

import com.meetmind.backend.dto.PlanDto;
import com.meetmind.backend.service.PlanService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/meetings/{meetingId}/plan")
public class PlanController
{
    private final PlanService planService;


    public PlanController(PlanService planService)
    {
        this.planService = planService;
    }


    // targetDays is optional: with it, the answer also says how likely the
    // project is to finish within that many days.
    @GetMapping
    public PlanDto.PlanResponse getPlan(
            @PathVariable Long meetingId,
            @RequestParam(required = false) Double targetDays)
    {
        return planService.getPlan(meetingId, targetDays);
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDto.PlanResponse createPlan(
            @PathVariable Long meetingId)
    {
        return planService.createFromActionItems(meetingId);
    }


    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(
            @PathVariable Long meetingId)
    {
        planService.deletePlan(meetingId);
    }


    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDto.PlanResponse addTask(
            @PathVariable Long meetingId,
            @Valid @RequestBody PlanDto.TaskRequest request)
    {
        return planService.addTask(meetingId, request);
    }


    @PutMapping("/tasks/{taskId}")
    public PlanDto.PlanResponse updateTask(
            @PathVariable Long meetingId,
            @PathVariable Long taskId,
            @Valid @RequestBody PlanDto.TaskRequest request)
    {
        return planService.updateTask(meetingId, taskId, request);
    }


    @DeleteMapping("/tasks/{taskId}")
    public PlanDto.PlanResponse deleteTask(
            @PathVariable Long meetingId,
            @PathVariable Long taskId)
    {
        return planService.deleteTask(meetingId, taskId);
    }


    @PostMapping("/what-if")
    public PlanDto.WhatIfResponse whatIf(
            @PathVariable Long meetingId,
            @Valid @RequestBody PlanDto.WhatIfRequest request)
    {
        return planService.whatIf(meetingId, request);
    }
}
