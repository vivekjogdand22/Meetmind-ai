package com.meetmind.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.dto.PlanDto;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.entity.PlanTask;
import com.meetmind.backend.exception.PlanConflictException;
import com.meetmind.backend.exception.PlanNotFoundException;
import com.meetmind.backend.exception.PlanValidationException;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.PlanTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


// Tests PlanService without a database: the repositories are replaced by a
// small in-memory list that behaves like the plan_task table.

class PlanServiceTest
{
    private static final Long MEETING_ID = 7L;

    private static final double DELTA = 0.001;

    // The four tasks of the project brief, as the AI would extract them.
    private static final String ACTION_ITEMS_JSON = """
            [
              {"task": "Complete the frontend design", "owner": "Vivek", "dueDate": "Wednesday",
               "priority": "MEDIUM", "evidence": "Vivek will complete the frontend design by Wednesday."},
              {"task": "Develop the backend APIs", "owner": "Amit", "dueDate": "Friday",
               "priority": "HIGH", "evidence": "Amit will develop the backend APIs by Friday."},
              {"task": "Perform integration testing", "owner": "Team", "dueDate": "next Monday",
               "priority": "MEDIUM", "evidence": "The team will perform integration testing next Monday."},
              {"task": "Give the final demonstration", "owner": "Team", "dueDate": "Thursday",
               "priority": "MEDIUM", "evidence": "The team will give the final demonstration on Thursday."}
            ]
            """;

    // ids handed out by the fake table, in creation order
    private static final Long DESIGN = 1L;

    private static final Long BACKEND = 2L;

    private static final Long TESTING = 3L;

    private static final Long DEMO = 4L;


    private final List<PlanTask> table = new ArrayList<>();

    private long nextId;

    private Meeting meeting;

    private PlanService service;


    @BeforeEach
    void setUp()
    {
        table.clear();

        nextId = 1;

        meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setActionItems(ACTION_ITEMS_JSON);


        MeetingRepository meetingRepository = mock(MeetingRepository.class);

        when(meetingRepository.findById(MEETING_ID))
                .thenReturn(Optional.of(meeting));


        PlanTaskRepository taskRepository = mock(PlanTaskRepository.class);

        when(taskRepository.findByMeetingIdOrderBySortOrderAscIdAsc(MEETING_ID))
                .thenAnswer(call -> table.stream()
                        .sorted(Comparator
                                .comparingInt(PlanTask::getSortOrder)
                                .thenComparing(PlanTask::getId))
                        .toList());

        when(taskRepository.existsByMeetingId(MEETING_ID))
                .thenAnswer(call -> !table.isEmpty());

        when(taskRepository.saveAll(ArgumentMatchers.<PlanTask>anyList()))
                .thenAnswer(call -> {
                    List<PlanTask> tasks = call.getArgument(0);
                    tasks.forEach(this::persist);
                    return tasks;
                });

        when(taskRepository.saveAndFlush(ArgumentMatchers.any(PlanTask.class)))
                .thenAnswer(call -> persist(call.getArgument(0)));

        when(taskRepository.save(ArgumentMatchers.any(PlanTask.class)))
                .thenAnswer(call -> persist(call.getArgument(0)));

        doAnswer(call -> {
            table.remove(call.<PlanTask>getArgument(0));
            return null;
        }).when(taskRepository).delete(ArgumentMatchers.any(PlanTask.class));

        doAnswer(call -> {
            table.clear();
            return null;
        }).when(taskRepository).deleteByMeetingId(MEETING_ID);


        service = new PlanService(
                meetingRepository,
                taskRepository,
                new ObjectMapper()
        );
    }


    private PlanTask persist(PlanTask task)
    {
        if (task.getId() == null)
        {
            task.setId(nextId++);
        }

        if (!table.contains(task))
        {
            table.add(task);
        }

        return task;
    }


    // ============================================================
    // HELPERS
    // ============================================================

    private static PlanDto.TaskRequest request(
            String name,
            Double optimistic,
            Double mostLikely,
            Double pessimistic,
            Long... predecessors)
    {
        return new PlanDto.TaskRequest(
                name,
                null,
                optimistic,
                mostLikely,
                pessimistic,
                List.of(predecessors)
        );
    }


    // Creates the plan and fills in the estimates and links of the brief:
    // design (2/4/6) and backend (3/5/13) -> testing (1/2/3) -> demo (1/1/1)
    private PlanDto.PlanResponse createFullPlan()
    {
        service.createFromActionItems(MEETING_ID);

        service.updateTask(MEETING_ID, DESIGN,
                request("Complete the frontend design", 2.0, 4.0, 6.0));

        service.updateTask(MEETING_ID, BACKEND,
                request("Develop the backend APIs", 3.0, 5.0, 13.0));

        service.updateTask(MEETING_ID, TESTING,
                request("Perform integration testing", 1.0, 2.0, 3.0, DESIGN, BACKEND));

        return service.updateTask(MEETING_ID, DEMO,
                request("Give the final demonstration", 1.0, 1.0, 1.0, TESTING));
    }


    private static PlanDto.TaskResponse task(
            PlanDto.PlanResponse plan,
            Long id)
    {
        return plan.tasks()
                .stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }


    private PlanValidationException assertInvalid(Runnable action)
    {
        return assertThrows(PlanValidationException.class, action::run);
    }


    // ============================================================
    // CREATING THE PLAN FROM ACTION ITEMS
    // ============================================================

    @Test
    void createCopiesTheActionItemsAsDraftTasks()
    {
        PlanDto.PlanResponse plan = service.createFromActionItems(MEETING_ID);

        assertEquals(4, plan.tasks().size());
        assertEquals("Complete the frontend design", plan.tasks().get(0).name());
        assertEquals("Amit", plan.tasks().get(1).owner());
        assertEquals("HIGH", plan.tasks().get(1).priority());
        assertEquals("next Monday", plan.tasks().get(2).sourceDueDate());

        // no estimates yet, so there is no schedule
        assertFalse(plan.readyToSchedule());
        assertNull(plan.schedule());
        assertEquals(4, plan.tasksMissingEstimates().size());
        assertNull(plan.tasks().get(0).optimisticDays());
        assertNull(plan.tasks().get(0).slackDays());

        // the action items stored on the meeting are untouched
        assertEquals(ACTION_ITEMS_JSON, meeting.getActionItems());
    }


    @Test
    void createTwiceIsAConflict()
    {
        service.createFromActionItems(MEETING_ID);

        assertThrows(
                PlanConflictException.class,
                () -> service.createFromActionItems(MEETING_ID)
        );
    }


    @Test
    void createWithoutUsableActionItemsIsRejected()
    {
        for (String stored : new String[] {null, "", "[]", "not json at all"})
        {
            meeting.setActionItems(stored);

            assertInvalid(() -> service.createFromActionItems(MEETING_ID));
        }
    }


    @Test
    void unknownMeetingIsNotFound()
    {
        assertThrows(PlanNotFoundException.class, () -> service.getPlan(99L, null));
        assertThrows(PlanNotFoundException.class, () -> service.createFromActionItems(99L));
        assertThrows(PlanNotFoundException.class, () -> service.deletePlan(99L));
    }


    // ============================================================
    // THE SCHEDULE
    // ============================================================

    @Test
    void scheduleAppearsOnlyWhenEveryTaskHasEstimates()
    {
        service.createFromActionItems(MEETING_ID);

        PlanDto.PlanResponse partial =
                service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", 2.0, 4.0, 6.0));

        assertFalse(partial.readyToSchedule());
        assertNull(partial.schedule());
        assertEquals(3, partial.tasksMissingEstimates().size());
    }


    @Test
    void fullPlanMatchesTheHandCalculatedSchedule()
    {
        PlanDto.PlanResponse plan = createFullPlan();

        assertTrue(plan.readyToSchedule());
        assertTrue(plan.tasksMissingEstimates().isEmpty());

        assertEquals(9.0, plan.schedule().projectDurationDays(), DELTA);
        assertEquals(List.of(BACKEND, TESTING, DEMO), plan.schedule().criticalPath());
        assertEquals(1.6997, plan.schedule().projectStandardDeviation(), DELTA);

        assertEquals(2.0, task(plan, DESIGN).slackDays(), DELTA);
        assertFalse(task(plan, DESIGN).critical());
        assertEquals(6.0, task(plan, BACKEND).expectedDays(), DELTA);
        assertTrue(task(plan, BACKEND).critical());
        assertEquals(8.0, task(plan, DEMO).earliestStart(), DELTA);
        assertEquals(9.0, task(plan, DEMO).earliestFinish(), DELTA);

        // no target was asked for
        assertNull(plan.schedule().probabilityOfFinishingByTarget());
    }


    @Test
    void targetDaysAddsTheChanceOfFinishingInTime()
    {
        createFullPlan();

        PlanDto.PlanResponse plan = service.getPlan(MEETING_ID, 10.0);

        assertEquals(10.0, plan.schedule().targetDays(), DELTA);
        assertEquals(0.722, plan.schedule().probabilityOfFinishingByTarget(), 0.005);
    }


    @Test
    void targetDaysIsIgnoredWhileThePlanIsADraft()
    {
        service.createFromActionItems(MEETING_ID);

        assertNull(service.getPlan(MEETING_ID, 10.0).schedule());
    }


    // ============================================================
    // RULES FOR EDITING A TASK
    // ============================================================

    @Test
    void partialEstimatesAreRejected()
    {
        service.createFromActionItems(MEETING_ID);

        PlanValidationException error = assertInvalid(
                () -> service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", 2.0, null, null))
        );

        assertTrue(error.getMessage().contains("all three"));
    }


    @Test
    void unorderedEstimatesAreRejected()
    {
        service.createFromActionItems(MEETING_ID);

        PlanValidationException error = assertInvalid(
                () -> service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", 5.0, 3.0, 8.0))
        );

        assertTrue(error.getMessage().contains("optimistic"));
    }


    @Test
    void aDependencyCycleIsRejected()
    {
        createFullPlan();

        // design -> testing -> demo, so design cannot wait for demo
        PlanValidationException error = assertInvalid(
                () -> service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", 2.0, 4.0, 6.0, DEMO))
        );

        assertTrue(error.getMessage().contains("cycle"));
    }


    @Test
    void aDependencyCycleIsRejectedEvenWhileThePlanIsADraft()
    {
        service.createFromActionItems(MEETING_ID);

        // design waits for backend: fine
        service.updateTask(MEETING_ID, DESIGN,
                request("Complete the frontend design", null, null, null, BACKEND));

        // backend waits for design: a loop, although no task has estimates yet
        PlanValidationException error = assertInvalid(
                () -> service.updateTask(MEETING_ID, BACKEND,
                        request("Develop the backend APIs", null, null, null, DESIGN))
        );

        assertTrue(error.getMessage().contains("cycle"));
    }


    @Test
    void selfDependencyAndUnknownPredecessorAreRejected()
    {
        service.createFromActionItems(MEETING_ID);

        PlanValidationException self = assertInvalid(
                () -> service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", null, null, null, DESIGN))
        );

        assertTrue(self.getMessage().contains("itself"));


        PlanValidationException unknown = assertInvalid(
                () -> service.updateTask(MEETING_ID, DESIGN,
                        request("Complete the frontend design", null, null, null, 99L))
        );

        assertTrue(unknown.getMessage().contains("unknown"));
    }


    @Test
    void unknownTaskIsNotFound()
    {
        service.createFromActionItems(MEETING_ID);

        assertThrows(
                PlanNotFoundException.class,
                () -> service.updateTask(MEETING_ID, 99L, request("x", null, null, null))
        );

        assertThrows(
                PlanNotFoundException.class,
                () -> service.deleteTask(MEETING_ID, 99L)
        );
    }


    // ============================================================
    // ADDING AND REMOVING TASKS
    // ============================================================

    @Test
    void addedTaskGoesToTheEndAndCanDependOnOthers()
    {
        createFullPlan();

        PlanDto.PlanResponse plan =
                service.addTask(MEETING_ID,
                        request("Write the final report", 1.0, 2.0, 3.0, DEMO));

        assertEquals(5, plan.tasks().size());
        assertEquals("Write the final report", plan.tasks().get(4).name());
        assertEquals(List.of(DEMO), plan.tasks().get(4).predecessorIds());

        // the report starts after the demo and extends the project by 2 days
        assertEquals(11.0, plan.schedule().projectDurationDays(), DELTA);
    }


    @Test
    void addedTaskWithAnUnknownPredecessorIsRejected()
    {
        createFullPlan();

        assertInvalid(
                () -> service.addTask(MEETING_ID,
                        request("Orphan", null, null, null, 99L))
        );
    }


    @Test
    void removingATaskRemovesItFromTheOthersPredecessors()
    {
        createFullPlan();

        PlanDto.PlanResponse plan = service.deleteTask(MEETING_ID, BACKEND);

        assertEquals(3, plan.tasks().size());
        assertEquals(List.of(DESIGN), task(plan, TESTING).predecessorIds());

        // design (4) -> testing (2) -> demo (1)
        assertEquals(7.0, plan.schedule().projectDurationDays(), DELTA);
    }


    @Test
    void deletingThePlanRemovesEveryTask()
    {
        createFullPlan();

        service.deletePlan(MEETING_ID);

        assertTrue(service.getPlan(MEETING_ID, null).tasks().isEmpty());
    }


    // ============================================================
    // WHAT IF A TASK IS DELAYED
    // ============================================================

    @Test
    void delayingACriticalTaskDelaysTheDemonstration()
    {
        createFullPlan();

        PlanDto.WhatIfResponse result =
                service.whatIf(MEETING_ID, new PlanDto.WhatIfRequest(BACKEND, 2.0));

        assertEquals("Develop the backend APIs", result.delayedTaskName());
        assertEquals(9.0, result.baselineDurationDays(), DELTA);
        assertEquals(11.0, result.newDurationDays(), DELTA);
        assertEquals(2.0, result.projectDelayDays(), DELTA);
        assertFalse(result.absorbedBySlack());
        assertEquals(0.0, result.slackBeforeDelayDays(), DELTA);

        PlanDto.TaskShift demo =
                result.shiftedTasks()
                        .stream()
                        .filter(shift -> shift.id().equals(DEMO))
                        .findFirst()
                        .orElseThrow();

        assertEquals(9.0, demo.baselineFinish(), DELTA);
        assertEquals(11.0, demo.newFinish(), DELTA);
    }


    @Test
    void delayWithinSlackIsAbsorbedAndNothingIsSaved()
    {
        createFullPlan();

        PlanDto.WhatIfResponse result =
                service.whatIf(MEETING_ID, new PlanDto.WhatIfRequest(DESIGN, 2.0));

        assertTrue(result.absorbedBySlack());
        assertEquals(2.0, result.slackBeforeDelayDays(), DELTA);
        assertEquals(9.0, result.newDurationDays(), DELTA);
        assertNotNull(result.scenarioTasks());

        // the saved plan is unchanged
        assertEquals(9.0, service.getPlan(MEETING_ID, null).schedule().projectDurationDays(), DELTA);
        assertEquals(4.0, task(service.getPlan(MEETING_ID, null), DESIGN).expectedDays(), DELTA);
    }


    @Test
    void whatIfNeedsEstimatesForEveryTask()
    {
        service.createFromActionItems(MEETING_ID);

        PlanValidationException error = assertInvalid(
                () -> service.whatIf(MEETING_ID, new PlanDto.WhatIfRequest(DESIGN, 1.0))
        );

        assertTrue(error.getMessage().contains("Missing for"));
    }


    @Test
    void whatIfForAnUnknownTaskIsNotFound()
    {
        createFullPlan();

        assertThrows(
                PlanNotFoundException.class,
                () -> service.whatIf(MEETING_ID, new PlanDto.WhatIfRequest(99L, 1.0))
        );
    }
}
