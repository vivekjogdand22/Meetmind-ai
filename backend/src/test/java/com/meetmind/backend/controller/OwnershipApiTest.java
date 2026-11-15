package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// Two real accounts. Nobody may see, change, delete, plan or ask about the
// meetings of the other: for them those meetings simply do not exist.

@SpringBootTest
@AutoConfigureMockMvc
class OwnershipApiTest
{
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MeetingRepository meetingRepository;

    private String anna;

    private String ben;

    private long annasMeeting;


    @BeforeEach
    void twoUsersAndOneMeeting() throws Exception
    {
        meetingRepository.deleteAll();

        userRepository.deleteAll();

        anna = register("anna@example.com");

        ben = register("ben@example.com");

        // the body also tries to give the meeting to user 999: that must be ignored
        String body =
                objectMapper.createObjectNode()
                        .put("title", "Anna's meeting")
                        .put("transcript", "Anna said something.")
                        .put("actionItems", "[{\"task\": \"Write it\", \"owner\": \"Anna\", \"dueDate\": \"\", \"priority\": \"HIGH\"}]")
                        .put("ownerId", 999)
                        .toString();

        String created = send(as(anna, post("/api/meetings")), body, 201);

        annasMeeting = objectMapper.readTree(created).path("id").asLong();
    }


    private String register(String email) throws Exception
    {
        String body =
                mockMvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"email\": \"" + email + "\", \"password\": \"a-good-password\", \"name\": \"X\"}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).path("token").asText();
    }


    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request)
    {
        return request.header("Authorization", "Bearer " + token);
    }


    private String send(MockHttpServletRequestBuilder request, String json, int expectedStatus) throws Exception
    {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }


    private JsonNode list(String token) throws Exception
    {
        return objectMapper.readTree(
                mockMvc.perform(as(token, get("/api/meetings")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString()
        );
    }


    @Test
    void everybodySeesOnlyTheirOwnMeetings() throws Exception
    {
        assertEquals(1, list(anna).size());
        assertEquals("Anna's meeting", list(anna).get(0).path("title").asText());
        assertEquals(0, list(ben).size());
    }


    @Test
    void theOwnerIdCannotBeSetOrSeenThroughTheApi() throws Exception
    {
        // the request above tried to give the meeting to user 999
        long annasId = userRepository.findByEmail("anna@example.com").orElseThrow().getId();

        assertEquals(annasId, meetingRepository.findById(annasMeeting).orElseThrow().getOwnerId());
        assertTrue(list(anna).get(0).path("ownerId").isMissingNode());
    }


    @Test
    void anotherUserCannotReadChangeOrDeleteAMeeting() throws Exception
    {
        mockMvc.perform(as(ben, put("/api/meetings/" + annasMeeting)).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Hijacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(as(ben, delete("/api/meetings/" + annasMeeting))).andExpect(status().isNotFound());

        assertEquals("Anna's meeting", meetingRepository.findById(annasMeeting).orElseThrow().getTitle());
    }


    @Test
    void anotherUserCannotAskAboutAMeeting() throws Exception
    {
        mockMvc.perform(as(ben, post("/api/meetings/" + annasMeeting + "/ask")).contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"What was said?\"}"))
                .andExpect(status().isNotFound());
    }


    @Test
    void anotherUserCannotUseTheProjectPlan() throws Exception
    {
        String planUrl = "/api/meetings/" + annasMeeting + "/plan";

        mockMvc.perform(as(ben, get(planUrl))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, post(planUrl))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, delete(planUrl))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, post(planUrl + "/tasks")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"optimisticDays\": 1, \"mostLikelyDays\": 2, \"pessimisticDays\": 3}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ben, delete(planUrl + "/tasks/1"))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, put(planUrl + "/tasks/1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"optimisticDays\": 1, \"mostLikelyDays\": 2, \"pessimisticDays\": 3}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ben, post(planUrl + "/what-if")).contentType(MediaType.APPLICATION_JSON).content("{\"taskId\": 1, \"delayDays\": 2}"))
                .andExpect(status().isNotFound());
    }


    @Test
    void theOwnerCanDoAllOfIt() throws Exception
    {
        mockMvc.perform(as(anna, post("/api/meetings/" + annasMeeting + "/plan"))).andExpect(status().isCreated());
        mockMvc.perform(as(anna, get("/api/meetings/" + annasMeeting + "/plan"))).andExpect(status().isOk());
        mockMvc.perform(as(anna, put("/api/meetings/" + annasMeeting)).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Renamed\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(anna, delete("/api/meetings/" + annasMeeting))).andExpect(status().isNoContent());

        assertEquals(0, list(anna).size());
    }


    @Test
    void anUnknownMeetingIsA404ForEveryone() throws Exception
    {
        mockMvc.perform(as(anna, delete("/api/meetings/987654"))).andExpect(status().isNotFound());
    }
}
