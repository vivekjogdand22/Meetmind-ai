package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.PlanTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// The project-plan API over HTTP, with the real controllers, service,
// exception handler and database mappings, on an in-memory database.
// It mirrors docs/plan_api_smoke_test.py, which runs against real PostgreSQL.

@SpringBootTest
@AutoConfigureMockMvc
class PlanApiIntegrationTest
{
    private static final String ACTION_ITEMS = """
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

    private static final double DELTA = 0.001;


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private PlanTaskRepository planTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;


    private Long meetingId;

    private String plan;


    @BeforeEach
    void createMeeting()
    {
        planTaskRepository.deleteAll();

        meetingRepository.deleteAll();

        Meeting meeting = new Meeting();

        meeting.setTitle("Plan API test");
        meeting.setActionItems(ACTION_ITEMS);
        meeting.setStatus("COMPLETED");

        meetingId = meetingRepository.save(meeting).getId();

        plan = "/api/meetings/" + meetingId + "/plan";
    }


    // ============================================================
    // HELPERS
    // ============================================================

    private JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception
    {
        String body =
                mockMvc.perform(request)
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return body.isEmpty() ? null : objectMapper.readTree(body);
    }


    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, Object body) throws Exception
    {
        return request
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }


    private static Map<String, Object> task(
            String name,
            Double optimistic,
            Double mostLikely,
            Double pessimistic,
            Long... predecessors)
    {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("name", name);
        body.put("optimisticDays", optimistic);
        body.put("mostLikelyDays", mostLikely);
        body.put("pessimisticDays", pessimistic);
        body.put("predecessorIds", List.of(predecessors));

        return body;
    }


    // Creates the plan and fills in the brief's estimates and dependencies.
    // Returns the ids of design, backend, testing and demo.
    private List<Long> createFullPlan() throws Exception
    {
        JsonNode created = call(post(plan), 201);

        List<Long> ids = new ArrayList<>();

        created.path("tasks").forEach(node -> ids.add(node.path("id").asLong()));

        Long design = ids.get(0);
        Long backend = ids.get(1);
        Long testing = ids.get(2);
        Long demo = ids.get(3);

        call(withJson(put(plan + "/tasks/" + design), task("Complete the frontend design", 2.0, 4.0, 6.0)), 200);
        call(withJson(put(plan + "/tasks/" + backend), task("Develop the backend APIs", 3.0, 5.0, 13.0)), 200);
        call(withJson(put(plan + "/tasks/" + testing), task("Perform integration testing", 1.0, 2.0, 3.0, design, backend)), 200);
        call(withJson(put(plan + "/tasks/" + demo), task("Give the final demonstration", 1.0, 1.0, 1.0, testing)), 200);

        return ids;
    }


    // ============================================================
    // THE PLAN AND ITS SCHEDULE
    // ============================================================

    @Test
    void copiesTheActionItemsAndSchedulesTheBriefExample() throws Exception
    {
        List<Long> ids = createFullPlan();

        JsonNode result = call(get(plan), 200);

        assertTrue(result.path("readyToSchedule").asBoolean());
        assertEquals(9.0, result.path("schedule").path("projectDurationDays").asDouble(), DELTA);
        assertEquals(
                List.of(ids.get(1), ids.get(2), ids.get(3)),
                List.of(
                        result.path("schedule").path("criticalPath").get(0).asLong(),
                        result.path("schedule").path("criticalPath").get(1).asLong(),
                        result.path("schedule").path("criticalPath").get(2).asLong()
                )
        );
        assertEquals(2.0, result.path("tasks").get(0).path("slackDays").asDouble(), DELTA);
        assertEquals("Develop the backend APIs", result.path("tasks").get(1).path("name").asText());
    }


    @Test
    void theDeadlineChanceIsReturnedWhenATargetIsGiven() throws Exception
    {
        createFullPlan();

        JsonNode result = call(get(plan + "?targetDays=10"), 200);

        assertEquals(0.722, result.path("schedule").path("probabilityOfFinishingByTarget").asDouble(), 0.005);
    }


    @Test
    void whatIfSimulatesADelayAndSavesNothing() throws Exception
    {
        List<Long> ids = createFullPlan();

        JsonNode result =
                call(withJson(post(plan + "/what-if"), Map.of("taskId", ids.get(1), "delayDays", 2)), 200);

        assertEquals(11.0, result.path("newDurationDays").asDouble(), DELTA);
        assertEquals(2.0, result.path("projectDelayDays").asDouble(), DELTA);
        assertFalse(result.path("absorbedBySlack").asBoolean());

        assertEquals(9.0, call(get(plan), 200).path("schedule").path("projectDurationDays").asDouble(), DELTA);
    }


    // ============================================================
    // MISTAKES ARE REFUSED AND CHANGE NOTHING
    // ============================================================

    @Test
    void aRefusedChangeIsRolledBackInTheDatabase() throws Exception
    {
        List<Long> ids = createFullPlan();

        // the design cannot wait for the demonstration, which waits for the design
        JsonNode error =
                call(withJson(put(plan + "/tasks/" + ids.get(0)),
                        task("Complete the frontend design", 2.0, 4.0, 6.0, ids.get(3))), 400);

        assertTrue(error.path("message").asText().contains("cycle"));

        JsonNode after = call(get(plan), 200);

        assertEquals(0, after.path("tasks").get(0).path("predecessorIds").size());
        assertEquals(9.0, after.path("schedule").path("projectDurationDays").asDouble(), DELTA);
    }


    @Test
    void errorsAreClearAndUseTheRightStatus() throws Exception
    {
        call(post(plan), 201);

        assertTrue(call(post(plan), 409).path("message").asText().contains("already has a plan"));

        assertTrue(call(withJson(post(plan + "/tasks"), task("   ", null, null, null)), 400)
                .path("message").asText().contains("name is required"));

        assertTrue(call(withJson(post(plan + "/tasks"), task("Half", 1.0, null, null)), 400)
                .path("message").asText().contains("all three"));

        assertTrue(call(withJson(post(plan + "/tasks"), task("Backwards", 5.0, 3.0, 8.0)), 400)
                .path("message").asText().contains("optimistic"));

        assertTrue(call(withJson(post(plan + "/tasks"), task("Negative", -1.0, 2.0, 3.0)), 400)
                .path("message").asText().contains("zero or more"));

        call(post(plan + "/tasks").contentType(MediaType.APPLICATION_JSON).content("{ not json"), 400);

        call(delete(plan + "/tasks/abc"), 400);

        call(withJson(put(plan + "/tasks/999999"), task("Ghost", null, null, null)), 404);

        call(get("/api/meetings/999999/plan"), 404);

        assertEquals(404, call(get("/api/meetings/999999/plan"), 404).path("status").asInt());
    }


    // ============================================================
    // DELETING
    // ============================================================

    @Test
    void deletingAMeetingAlsoDeletesItsPlanInTheDatabase() throws Exception
    {
        createFullPlan();

        Integer tasksBefore = jdbcTemplate.queryForObject("select count(*) from plan_task", Integer.class);
        Integer linksBefore = jdbcTemplate.queryForObject("select count(*) from plan_task_predecessor", Integer.class);

        assertEquals(4, tasksBefore);
        assertEquals(3, linksBefore);

        call(delete("/api/meetings/" + meetingId), 204);

        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from plan_task", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from plan_task_predecessor", Integer.class));
    }


    @Test
    void removingATaskRemovesItFromTheOtherTasksPredecessors() throws Exception
    {
        List<Long> ids = createFullPlan();

        JsonNode result = call(delete(plan + "/tasks/" + ids.get(1)), 200);

        assertEquals(3, result.path("tasks").size());
        assertEquals(1, result.path("tasks").get(1).path("predecessorIds").size());
        assertEquals(7.0, result.path("schedule").path("projectDurationDays").asDouble(), DELTA);
    }


    // ============================================================
    // BROWSER ACCESS (CORS) AND THE ANALYSIS SOURCE
    // ============================================================

    @Test
    void theFrontendMayCallTheApiFromLocalhostAndFromTheLoopbackAddress() throws Exception
    {
        for (String origin : List.of("http://localhost:5173", "http://127.0.0.1:5173"))
        {
            for (String method : List.of("GET", "POST", "PUT", "DELETE"))
            {
                mockMvc.perform(
                                options(plan + "/tasks/1")
                                        .header("Origin", origin)
                                        .header("Access-Control-Request-Method", method)
                        )
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", origin));
            }
        }

        // the older meeting endpoints are covered by the same rule
        mockMvc.perform(
                        options("/api/meetings")
                                .header("Origin", "http://127.0.0.1:5173")
                                .header("Access-Control-Request-Method", "POST")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
    }


    @Test
    void otherWebsitesAreNotAllowed() throws Exception
    {
        mockMvc.perform(
                        options(plan)
                                .header("Origin", "http://evil.example")
                                .header("Access-Control-Request-Method", "GET")
                )
                .andExpect(status().isForbidden());
    }


    @Test
    void theAnalysisSourceIsStoredAndReturnedWithTheMeeting() throws Exception
    {
        call(withJson(post("/api/meetings"), Map.of("title", "Fallback meeting", "analysisSource", "FALLBACK")), 201);

        JsonNode meetings = call(get("/api/meetings"), 200);

        boolean found = false;

        for (JsonNode meeting : meetings)
        {
            if ("Fallback meeting".equals(meeting.path("title").asText()))
            {
                found = true;

                assertEquals("FALLBACK", meeting.path("analysisSource").asText());
            }
        }

        assertTrue(found);
    }
}
