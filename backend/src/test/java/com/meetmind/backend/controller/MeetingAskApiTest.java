package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.WithJwtUser;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.repository.MeetingRepository;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// "Ask Your Meeting" through the real controller, service and database. Only
// the Python AI service is replaced by a stand-in (no Whisper, no Ollama).

@SpringBootTest
@AutoConfigureMockMvc
@WithJwtUser
class MeetingAskApiTest
{
    private static final String TRANSCRIPT =
            "Alice: We will launch the beta on March 14th. Bob: I will finish the payment module by Friday.";

    private static final String GOOD_ANSWER = """
            {"answer": "The beta launches on March 14th.", "answerable": true, "unsupported": false,
             "broad": false, "model": "qwen3:8b", "seconds": 12.3, "somethingNew": "ignored",
             "sources": [{"passage": 0, "start": 0, "end": 94, "text": "Alice: We will launch the beta on March 14th. Bob: I will finish the payment module by Friday.",
                          "quote": "launch the beta on March 14th", "quoteStart": 15, "quoteEnd": 44, "score": 0.81}]}
            """;

    // what the stand-in AI service does; reset before every test
    private static volatile int askStatus;

    private static volatile String askBody;

    private static volatile String lastRequest;

    private static final AtomicInteger CALLS = new AtomicInteger();

    private static final HttpServer AI_SERVICE = startAiService();


    private static HttpServer startAiService()
    {
        try
        {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

            server.createContext("/ask", exchange ->
            {
                CALLS.incrementAndGet();

                lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                if (askStatus < 0)
                {
                    exchange.close();   // the connection drops without an answer

                    return;
                }

                byte[] bytes = askBody.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().add("Content-Type", "application/json");

                exchange.sendResponseHeaders(askStatus, bytes.length);

                try (OutputStream out = exchange.getResponseBody())
                {
                    out.write(bytes);
                }
            });

            server.start();

            return server;
        }
        catch (IOException exception)
        {
            throw new IllegalStateException(exception);
        }
    }


    @DynamicPropertySource
    static void aiServiceAddress(DynamicPropertyRegistry registry)
    {
        registry.add("ai.service.url", () -> "http://127.0.0.1:" + AI_SERVICE.getAddress().getPort());
    }


    @AfterAll
    static void stopAiService()
    {
        AI_SERVICE.stop(0);
    }


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long meetingId;


    @BeforeEach
    void setUp()
    {
        askStatus = 200;

        askBody = GOOD_ANSWER;

        lastRequest = null;

        CALLS.set(0);

        meetingRepository.deleteAll();

        meetingId = saveMeeting(TRANSCRIPT);
    }


    @AfterEach
    void cleanUp()
    {
        meetingRepository.deleteAll();
    }


    private Long saveMeeting(String transcript)
    {
        Meeting meeting = new Meeting();

        meeting.setTitle("Ask test");

        meeting.setTranscript(transcript);

        meeting.setOwnerId(WithJwtUser.DEFAULT_USER_ID);

        return meetingRepository.save(meeting).getId();
    }


    private JsonNode ask(Long id, String json, int expectedStatus) throws Exception
    {
        String body =
                mockMvc.perform(
                                post("/api/meetings/{id}/ask", id)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(body);
    }


    private static String question(String text)
    {
        return "{\"question\": \"" + text + "\"}";
    }


    // ============================================================
    // THE ANSWER
    // ============================================================

    @Test
    void anAnswerComesBackWithItsSources() throws Exception
    {
        JsonNode answer = ask(meetingId, question("When is the beta launch?"), 200);

        assertEquals("The beta launches on March 14th.", answer.path("answer").asText());
        assertTrue(answer.path("answerable").asBoolean());
        assertEquals("qwen3:8b", answer.path("model").asText());
        assertEquals("launch the beta on March 14th", answer.path("sources").get(0).path("quote").asText());
        assertEquals(0.81, answer.path("sources").get(0).path("score").asDouble(), 0.0001);
    }


    @Test
    void theAiServiceGetsTheTranscriptAndTheTrimmedQuestion() throws Exception
    {
        ask(meetingId, question("   When is the beta launch?   "), 200);

        JsonNode sent = objectMapper.readTree(lastRequest);

        assertEquals(TRANSCRIPT, sent.path("transcript").asText());
        assertEquals("When is the beta launch?", sent.path("question").asText());
    }


    @Test
    void theMeetingDoesNotSayIsPassedOn() throws Exception
    {
        askBody = """
                {"answer": "The meeting does not say.", "answerable": false, "unsupported": false,
                 "broad": false, "sources": [], "model": "qwen3:8b", "seconds": 5.1}
                """;

        JsonNode answer = ask(meetingId, question("What is the budget?"), 200);

        assertEquals("The meeting does not say.", answer.path("answer").asText());
        assertEquals(false, answer.path("answerable").asBoolean());
        assertEquals(0, answer.path("sources").size());
    }


    // ============================================================
    // BAD REQUESTS (the AI service is never asked)
    // ============================================================

    @Test
    void aBlankQuestionIsRefused() throws Exception
    {
        JsonNode error = ask(meetingId, question("   "), 400);

        assertEquals("Please type a question.", error.path("message").asText());
        assertEquals(0, CALLS.get());
    }


    @Test
    void aMissingQuestionIsRefused() throws Exception
    {
        ask(meetingId, "{}", 400);

        assertEquals(0, CALLS.get());
    }


    @Test
    void aTooLongQuestionIsRefused() throws Exception
    {
        JsonNode error = ask(meetingId, question("x".repeat(501)), 400);

        assertTrue(error.path("message").asText().contains("too long"));
        assertEquals(0, CALLS.get());
    }


    @Test
    void aQuestionAtTheLimitIsAccepted() throws Exception
    {
        ask(meetingId, question("x".repeat(500)), 200);

        assertEquals(1, CALLS.get());
    }


    @Test
    void anUnknownMeetingIsA404() throws Exception
    {
        JsonNode error = ask(meetingId + 1000, question("When?"), 404);

        assertTrue(error.path("message").asText().contains("was not found"));
        assertEquals(0, CALLS.get());
    }


    @Test
    void aMeetingWithoutATranscriptIsRefused() throws Exception
    {
        Long empty = saveMeeting("   ");

        JsonNode error = ask(empty, question("When?"), 400);

        assertEquals("This meeting has no transcript to ask about.", error.path("message").asText());
        assertEquals(0, CALLS.get());
    }


    // ============================================================
    // PROBLEMS IN THE AI SERVICE
    // ============================================================

    @Test
    void ollamaBeingDownReachesTheUserAsA503WithTheReason() throws Exception
    {
        askStatus = 503;

        askBody = "{\"detail\": \"Ollama is not reachable, so the question cannot be answered. Start Ollama and try again.\"}";

        JsonNode error = ask(meetingId, question("When?"), 503);

        assertTrue(error.path("message").asText().startsWith("Ollama is not reachable"));
    }


    @Test
    void aBrokenAiServiceIsA502() throws Exception
    {
        askStatus = 500;

        askBody = "{\"detail\": \"Answering the question failed: boom\"}";

        JsonNode error = ask(meetingId, question("When?"), 502);

        assertTrue(error.path("message").asText().contains("boom"));
    }


    @Test
    void aDroppedConnectionIsA503() throws Exception
    {
        askStatus = -1;

        JsonNode error = ask(meetingId, question("When?"), 503);

        assertTrue(error.path("message").asText().contains("not reachable"));
    }


    @Test
    void anEmptyAnswerFromTheAiServiceIsA502() throws Exception
    {
        askBody = "{\"answer\": \"\", \"answerable\": true, \"sources\": []}";

        ask(meetingId, question("When?"), 502);
    }
}
