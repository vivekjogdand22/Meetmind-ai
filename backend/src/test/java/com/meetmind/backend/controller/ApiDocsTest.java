package com.meetmind.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// The Swagger page needs the description of the API. Check that it is served
// and lists the endpoints people will look for.

@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsTest
{
    @Autowired
    private MockMvc mockMvc;


    @Test
    void theApiDescriptionListsTheMainEndpoints() throws Exception
    {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MeetMind AI API"))
                .andExpect(jsonPath("$.paths['/api/meetings/upload'].post.summary").exists())
                .andExpect(jsonPath("$.paths['/api/meetings/{id}/ask'].post.summary").exists())
                .andExpect(jsonPath("$.paths['/api/meetings/{meetingId}/plan'].get.summary").exists())
                .andExpect(jsonPath("$.paths['/api/meetings/{meetingId}/plan/what-if'].post.summary").exists());
    }


    @Test
    void theSwaggerPageIsAvailable() throws Exception
    {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
    }
}
