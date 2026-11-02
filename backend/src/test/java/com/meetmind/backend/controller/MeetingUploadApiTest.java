package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// The whole upload endpoint (save the file, transcribe, analyze, store the
// result) with the real controller, service and database mappings. Only the
// Python AI service is replaced by a stand-in, so no Whisper and no Ollama.

@SpringBootTest
@AutoConfigureMockMvc
class MeetingUploadApiTest
{
    private static final Path UPLOADS = Paths.get("target/test-uploads");

    // what the stand-in AI service answers; reset before every test
    private static volatile int transcribeStatus;

    private static volatile String transcribeBody;

    private static volatile String summarizeBody;

    private static final HttpServer AI_SERVICE = startAiService();


    private static HttpServer startAiService()
    {
        try
        {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

            server.createContext("/transcribe", exchange ->
            {
                exchange.getRequestBody().readAllBytes();

                if (transcribeStatus < 0)
                {
                    exchange.close();   // the connection drops without an answer

                    return;
                }

                respond(exchange, transcribeStatus, transcribeBody);
            });

            server.createContext("/summarize", exchange ->
            {
                exchange.getRequestBody().readAllBytes();

                respond(exchange, 200, summarizeBody);
            });

            server.start();

            return server;
        }
        catch (IOException exception)
        {
            throw new IllegalStateException(exception);
        }
    }


    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
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


    @BeforeEach
    void resetStandIn()
    {
        transcribeStatus = 200;

        transcribeBody = "{\"transcript\": \"Vivek will finish the design by Friday.\"}";

        summarizeBody = """
                {"summary": "A short meeting.", "keyDecisions": ["Use React"],
                 "actionItems": [{"task": "Finish the design", "owner": "Vivek", "dueDate": "Friday",
                                  "priority": "MEDIUM", "evidence": "Vivek will finish the design by Friday."}],
                 "analysisSource": "LLM"}
                """;
    }


    @AfterEach
    void cleanUp() throws IOException
    {
        meetingRepository.deleteAll();

        if (Files.exists(UPLOADS))
        {
            try (Stream<Path> files = Files.list(UPLOADS))
            {
                for (Path file : files.toList())
                {
                    Files.deleteIfExists(file);
                }
            }
        }
    }


    // ============================================================
    // HELPERS
    // ============================================================

    private JsonNode upload(MockMultipartFile file, int expectedStatus) throws Exception
    {
        String body =
                mockMvc.perform(multipart("/api/meetings/upload").file(file).param("title", "Upload test"))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(body);
    }


    private static MockMultipartFile recording()
    {
        return new MockMultipartFile("file", "talk.mp3", "audio/mpeg", new byte[] {1, 2, 3, 4});
    }


    private long filesInTheUploadFolder() throws IOException
    {
        if (!Files.exists(UPLOADS))
        {
            return 0;
        }

        try (Stream<Path> files = Files.list(UPLOADS))
        {
            return files.count();
        }
    }


    private Meeting onlyMeeting()
    {
        List<Meeting> meetings = meetingRepository.findAll();

        assertEquals(1, meetings.size());

        return meetings.get(0);
    }


    // ============================================================
    // THE HAPPY PATH
    // ============================================================

    @Test
    void aRecordingIsStoredTranscribedAnalyzedAndSaved() throws Exception
    {
        JsonNode meeting = upload(recording(), 201);

        assertEquals("COMPLETED", meeting.path("status").asText());
        assertEquals("Vivek will finish the design by Friday.", meeting.path("transcript").asText());
        assertEquals("A short meeting.", meeting.path("summary").asText());
        assertEquals("LLM", meeting.path("analysisSource").asText());
        assertTrue(meeting.path("actionItems").asText().contains("Finish the design"));
        assertTrue(meeting.path("keyDecisions").asText().contains("Use React"));

        assertEquals(1, filesInTheUploadFolder());
    }


    @Test
    void theFallbackMarkFromTheAiServiceIsKept() throws Exception
    {
        summarizeBody = """
                {"summary": "Plain summary.", "keyDecisions": [], "actionItems": [],
                 "analysisSource": "FALLBACK"}
                """;

        assertEquals("FALLBACK", upload(recording(), 201).path("analysisSource").asText());
    }


    @Test
    void deletingTheMeetingAlsoRemovesTheUploadedFile() throws Exception
    {
        long id = upload(recording(), 201).path("id").asLong();

        assertEquals(1, filesInTheUploadFolder());

        mockMvc.perform(delete("/api/meetings/" + id)).andExpect(status().isNoContent());

        assertEquals(0, filesInTheUploadFolder());
        assertEquals(0, meetingRepository.count());
    }


    // ============================================================
    // PROBLEMS ARE EXPLAINED
    // ============================================================

    @Test
    void aRecordingWithoutSpeechGetsTheReasonFromTheAiService() throws Exception
    {
        transcribeStatus = 422;

        transcribeBody = "{\"detail\": \"No speech could be detected in the uploaded recording\"}";

        JsonNode error = upload(recording(), 422);

        assertEquals("No speech could be detected in the uploaded recording", error.path("message").asText());
        assertEquals(422, error.path("status").asInt());

        // the attempt is kept, marked as failed, so the user can see and remove it
        assertEquals("AI_PROCESSING_FAILED", onlyMeeting().getStatus());
    }


    @Test
    void aServerErrorInTheAiServiceIsReportedWithItsReason() throws Exception
    {
        transcribeStatus = 500;

        transcribeBody = "{\"detail\": \"Transcription failed: out of memory\"}";

        JsonNode error = upload(recording(), 502);

        assertEquals("Transcription failed: out of memory", error.path("message").asText());
    }


    @Test
    void anAiServiceThatDropsTheConnectionIsReportedAsNotReachable() throws Exception
    {
        transcribeStatus = -1;

        JsonNode error = upload(recording(), 503);

        assertTrue(error.path("message").asText().contains("not reachable"), error.toString());
    }


    @Test
    void aFileThatIsNotAudioOrVideoIsRefused() throws Exception
    {
        JsonNode error = upload(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()), 400);

        assertEquals("Only audio and video files are supported", error.path("message").asText());

        assertEquals(0, meetingRepository.count());
        assertEquals(0, filesInTheUploadFolder());
    }


    @Test
    void anEmptyFileIsRefused() throws Exception
    {
        JsonNode error = upload(new MockMultipartFile("file", "empty.mp3", "audio/mpeg", new byte[0]), 400);

        assertEquals("Please select an audio or video file", error.path("message").asText());
    }
}
