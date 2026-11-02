package com.meetmind.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.dto.PlanDto;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.entity.PlanTask;
import com.meetmind.backend.exception.PlanConflictException;
import com.meetmind.backend.exception.PlanNotFoundException;
import com.meetmind.backend.exception.PlanValidationException;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.PlanTaskRepository;
import com.meetmind.backend.service.CpmCalculator.TaskInput;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


// ============================================================
// PROJECT PLAN (PERT / CPM) FOR ONE MEETING
//
// Stores the tasks and their estimates, checks every change, and
// asks CpmCalculator for the schedule. Every saved state is valid:
// a change that breaks a rule throws, and the transaction is rolled
// back, so a bad request leaves the plan exactly as it was.
// ============================================================

@Service
public class PlanService
{
    private static final int MAX_NAME_LENGTH = 500;

    private static final int MAX_TEXT_LENGTH = 255;


    private final MeetingRepository meetingRepository;

    private final PlanTaskRepository taskRepository;

    private final ObjectMapper objectMapper;


    public PlanService(
            MeetingRepository meetingRepository,
            PlanTaskRepository taskRepository,
            ObjectMapper objectMapper)
    {
        this.meetingRepository = meetingRepository;

        this.taskRepository = taskRepository;

        this.objectMapper = objectMapper;
    }


    // ============================================================
    // READ THE PLAN
    // ============================================================

    @Transactional(readOnly = true)
    public PlanDto.PlanResponse getPlan(
            Long meetingId,
            Double targetDays)
    {
        requireMeeting(meetingId);

        return buildResponse(
                meetingId,
                loadTasks(meetingId),
                targetDays
        );
    }


    // ============================================================
    // CREATE AND DELETE THE WHOLE PLAN
    // ============================================================

    // Copies the AI action items into plan tasks. The action items stored
    // on the meeting are only read, never changed.
    @Transactional
    public PlanDto.PlanResponse createFromActionItems(Long meetingId)
    {
        Meeting meeting = requireMeeting(meetingId);

        if (taskRepository.existsByMeetingId(meetingId))
        {
            throw new PlanConflictException(
                    "This meeting already has a plan. "
                            + "Delete it first to start again."
            );
        }


        List<PlanTask> tasks = new ArrayList<>();

        for (MeetingAnalysis.ActionItem item : readActionItems(meeting))
        {
            if (item.task() == null || item.task().isBlank())
            {
                continue;
            }

            PlanTask task = new PlanTask();

            task.setMeeting(meeting);
            task.setName(truncate(item.task().trim(), MAX_NAME_LENGTH));
            task.setOwner(truncate(blankToNull(item.owner()), MAX_TEXT_LENGTH));
            task.setPriority(truncate(blankToNull(item.priority()), MAX_TEXT_LENGTH));
            task.setSourceDueDate(truncate(blankToNull(item.dueDate()), MAX_TEXT_LENGTH));
            task.setEvidence(blankToNull(item.evidence()));
            task.setSortOrder(tasks.size());

            tasks.add(task);
        }


        if (tasks.isEmpty())
        {
            throw new PlanValidationException(
                    "This meeting has no action items to copy. "
                            + "You can still add tasks one by one."
            );
        }


        taskRepository.saveAll(tasks);

        return buildResponse(meetingId, tasks, null);
    }


    @Transactional
    public void deletePlan(Long meetingId)
    {
        requireMeeting(meetingId);

        taskRepository.deleteByMeetingId(meetingId);
    }


    // ============================================================
    // ADD, EDIT AND REMOVE ONE TASK
    // ============================================================

    @Transactional
    public PlanDto.PlanResponse addTask(
            Long meetingId,
            PlanDto.TaskRequest request)
    {
        Meeting meeting = requireMeeting(meetingId);

        List<PlanTask> tasks = loadTasks(meetingId);


        PlanTask task = new PlanTask();

        task.setMeeting(meeting);

        task.setSortOrder(
                tasks.stream()
                        .mapToInt(PlanTask::getSortOrder)
                        .max()
                        .orElse(-1) + 1
        );

        applyRequest(task, request);


        // Saving first gives the new task its id, which the dependency
        // check needs. If the check fails, the rollback removes the row.
        taskRepository.saveAndFlush(task);

        tasks.add(task);

        checkDependencies(tasks);


        return buildResponse(meetingId, tasks, null);
    }


    @Transactional
    public PlanDto.PlanResponse updateTask(
            Long meetingId,
            Long taskId,
            PlanDto.TaskRequest request)
    {
        requireMeeting(meetingId);

        List<PlanTask> tasks = loadTasks(meetingId);

        PlanTask task = findTask(tasks, taskId);


        applyRequest(task, request);

        checkDependencies(tasks);

        taskRepository.save(task);


        return buildResponse(meetingId, tasks, null);
    }


    @Transactional
    public PlanDto.PlanResponse deleteTask(
            Long meetingId,
            Long taskId)
    {
        requireMeeting(meetingId);

        List<PlanTask> tasks = loadTasks(meetingId);

        PlanTask task = findTask(tasks, taskId);


        // The other tasks must stop waiting for the removed one.
        for (PlanTask other : tasks)
        {
            other.getPredecessorIds().remove(taskId);
        }

        tasks.remove(task);

        taskRepository.delete(task);


        return buildResponse(meetingId, tasks, null);
    }


    // ============================================================
    // WHAT IF A TASK IS DELAYED?  (nothing is saved)
    // ============================================================

    @Transactional(readOnly = true)
    public PlanDto.WhatIfResponse whatIf(
            Long meetingId,
            PlanDto.WhatIfRequest request)
    {
        requireMeeting(meetingId);

        List<PlanTask> tasks = loadTasks(meetingId);

        PlanTask delayed = findTask(tasks, request.taskId());

        requireEstimates(tasks);


        List<TaskInput> inputs = toInputs(tasks);

        CpmCalculator.Schedule baseline = CpmCalculator.calculate(inputs);

        CpmCalculator.WhatIfResult result =
                CpmCalculator.whatIf(
                        inputs,
                        request.taskId(),
                        request.delayDays()
                );


        double slackBefore =
                baseline.tasks()
                        .stream()
                        .filter(task -> task.id() == delayed.getId())
                        .findFirst()
                        .orElseThrow()
                        .slack();


        List<PlanDto.TaskShift> shifts =
                result.shiftedTasks()
                        .stream()
                        .map(shift -> new PlanDto.TaskShift(
                                shift.id(),
                                shift.name(),
                                shift.baselineFinish(),
                                shift.newFinish(),
                                shift.shift()
                        ))
                        .toList();

        List<PlanDto.ScenarioTask> scenario =
                result.scenario()
                        .tasks()
                        .stream()
                        .map(task -> new PlanDto.ScenarioTask(
                                task.id(),
                                task.name(),
                                task.earliestStart(),
                                task.earliestFinish(),
                                task.slack(),
                                task.critical()
                        ))
                        .toList();


        return new PlanDto.WhatIfResponse(
                delayed.getId(),
                delayed.getName(),
                result.delayDays(),
                result.baselineDuration(),
                result.newDuration(),
                result.projectDelay(),
                result.absorbedBySlack(),
                slackBefore,
                shifts,
                scenario
        );
    }


    // ============================================================
    // LOADING AND LOOKUP
    // ============================================================

    private Meeting requireMeeting(Long meetingId)
    {
        return meetingRepository
                .findById(meetingId)
                .orElseThrow(
                        () -> new PlanNotFoundException(
                                "Meeting " + meetingId + " was not found"
                        )
                );
    }


    private List<PlanTask> loadTasks(Long meetingId)
    {
        return new ArrayList<>(
                taskRepository.findByMeetingIdOrderBySortOrderAscIdAsc(
                        meetingId
                )
        );
    }


    private PlanTask findTask(
            List<PlanTask> tasks,
            Long taskId)
    {
        return tasks.stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow(
                        () -> new PlanNotFoundException(
                                "Task " + taskId
                                        + " was not found in this meeting's plan"
                        )
                );
    }


    private List<MeetingAnalysis.ActionItem> readActionItems(
            Meeting meeting)
    {
        String json = meeting.getActionItems();

        if (json == null || json.isBlank())
        {
            return List.of();
        }

        try
        {
            return objectMapper.readValue(
                    json,
                    new TypeReference<List<MeetingAnalysis.ActionItem>>()
                    {
                    }
            );
        }
        catch (JsonProcessingException exception)
        {
            throw new PlanValidationException(
                    "The saved action items of this meeting could not be read"
            );
        }
    }


    // ============================================================
    // VALIDATION
    // ============================================================

    // Copies the request onto the task after checking the estimates.
    private void applyRequest(
            PlanTask task,
            PlanDto.TaskRequest request)
    {
        validateEstimates(request);


        task.setName(request.name().trim());

        task.setOwner(blankToNull(request.owner()));

        task.setOptimisticDays(request.optimisticDays());

        task.setMostLikelyDays(request.mostLikelyDays());

        task.setPessimisticDays(request.pessimisticDays());


        task.getPredecessorIds().clear();

        if (request.predecessorIds() != null)
        {
            for (Long predecessorId : request.predecessorIds())
            {
                if (predecessorId != null)
                {
                    task.getPredecessorIds().add(predecessorId);
                }
            }
        }
    }


    private void validateEstimates(PlanDto.TaskRequest request)
    {
        Double optimistic = request.optimisticDays();
        Double mostLikely = request.mostLikelyDays();
        Double pessimistic = request.pessimisticDays();


        int given =
                (optimistic != null ? 1 : 0)
                        + (mostLikely != null ? 1 : 0)
                        + (pessimistic != null ? 1 : 0);

        if (given == 0)
        {
            return;
        }

        if (given < 3)
        {
            throw new PlanValidationException(
                    "Enter all three estimates (optimistic, most likely, "
                            + "pessimistic) or leave all three empty"
            );
        }

        if (optimistic > mostLikely || mostLikely > pessimistic)
        {
            throw new PlanValidationException(
                    "Estimates must satisfy "
                            + "optimistic <= most likely <= pessimistic"
            );
        }
    }


    // The links between tasks only need their ids, so every task gets
    // placeholder estimates here. CpmCalculator rejects unknown
    // predecessors, tasks that depend on themselves, and cycles.
    private void checkDependencies(List<PlanTask> tasks)
    {
        List<TaskInput> links =
                tasks.stream()
                        .map(task -> new TaskInput(
                                task.getId(),
                                task.getName(),
                                0,
                                0,
                                0,
                                List.copyOf(task.getPredecessorIds())
                        ))
                        .toList();

        CpmCalculator.calculate(links);
    }


    private void requireEstimates(List<PlanTask> tasks)
    {
        List<String> missing = namesWithoutEstimates(tasks);

        if (!missing.isEmpty())
        {
            throw new PlanValidationException(
                    "Enter all three estimates for every task first. "
                            + "Missing for: " + String.join(", ", missing)
            );
        }
    }


    // ============================================================
    // BUILDING THE RESPONSE
    // ============================================================

    private PlanDto.PlanResponse buildResponse(
            Long meetingId,
            List<PlanTask> tasks,
            Double targetDays)
    {
        List<String> missing = namesWithoutEstimates(tasks);

        boolean ready = !tasks.isEmpty() && missing.isEmpty();


        CpmCalculator.Schedule schedule = null;

        Map<Long, CpmCalculator.TaskResult> results = new HashMap<>();

        if (ready)
        {
            schedule = CpmCalculator.calculate(toInputs(tasks));

            for (CpmCalculator.TaskResult result : schedule.tasks())
            {
                results.put(result.id(), result);
            }
        }


        List<PlanDto.TaskResponse> taskResponses =
                tasks.stream()
                        .map(task -> toTaskResponse(task, results.get(task.getId())))
                        .toList();


        PlanDto.ScheduleSummary summary = null;

        if (schedule != null)
        {
            Double probability =
                    targetDays == null
                            ? null
                            : CpmCalculator.probabilityOfFinishingBy(
                                    schedule,
                                    targetDays
                            );

            summary = new PlanDto.ScheduleSummary(
                    schedule.projectDuration(),
                    schedule.criticalPath(),
                    schedule.projectVariance(),
                    schedule.projectStandardDeviation(),
                    targetDays,
                    probability
            );
        }


        return new PlanDto.PlanResponse(
                meetingId,
                taskResponses,
                ready,
                missing,
                summary
        );
    }


    private PlanDto.TaskResponse toTaskResponse(
            PlanTask task,
            CpmCalculator.TaskResult result)
    {
        return new PlanDto.TaskResponse(
                task.getId(),
                task.getName(),
                task.getOwner(),
                task.getPriority(),
                task.getSourceDueDate(),
                task.getEvidence(),
                task.getOptimisticDays(),
                task.getMostLikelyDays(),
                task.getPessimisticDays(),
                List.copyOf(task.getPredecessorIds()),
                result == null ? null : result.expectedTime(),
                result == null ? null : result.variance(),
                result == null ? null : result.earliestStart(),
                result == null ? null : result.earliestFinish(),
                result == null ? null : result.latestStart(),
                result == null ? null : result.latestFinish(),
                result == null ? null : result.slack(),
                result == null ? null : result.critical()
        );
    }


    private List<TaskInput> toInputs(List<PlanTask> tasks)
    {
        return tasks.stream()
                .map(task -> new TaskInput(
                        task.getId(),
                        task.getName(),
                        task.getOptimisticDays(),
                        task.getMostLikelyDays(),
                        task.getPessimisticDays(),
                        List.copyOf(task.getPredecessorIds())
                ))
                .toList();
    }


    private List<String> namesWithoutEstimates(List<PlanTask> tasks)
    {
        return tasks.stream()
                .filter(task -> !task.hasEstimates())
                .map(PlanTask::getName)
                .toList();
    }


    // ============================================================
    // SMALL TEXT HELPERS
    // ============================================================

    private String blankToNull(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        return value.trim();
    }


    private String truncate(
            String value,
            int maxLength)
    {
        if (value == null || value.length() <= maxLength)
        {
            return value;
        }

        return value.substring(0, maxLength);
    }
}
