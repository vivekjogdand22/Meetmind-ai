package com.meetmind.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.exception.AiProcessingException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


// Talks to a tiny stand-in for the Python AI service that runs inside the
// test, so no real AI service (and no Ollama) is needed.

class AiServiceClientTest
{
    private HttpServer standIn;


    @AfterEach
    void stopStandIn()
    {
        if (standIn != null)
        {
            standIn.stop(0);
        }
    }


    // Starts the stand-in for one endpoint (for example "/summarize")
    // and returns its address.
    private String startStandIn(String path, int status, String body) throws IOException
    {
        standIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        standIn.createContext(path, exchange ->
        {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");

            exchange.sendResponseHeaders(status, bytes.length);

            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(bytes);
            }
        });

        standIn.start();

        return "http://127.0.0.1:" + standIn.getAddress().getPort();
    }


    private static AiServiceClient clientFor(String address)
    {
        return new AiServiceClient(address, new ObjectMapper());
    }


    private static final String ANSWER = """
            {
              "summary": "A short meeting.",
              "keyDecisions": ["Use React"],
              "actionItems": [
                {"task": "Complete the design", "owner": "Vivek", "dueDate": "next Monday",
                 "priority": "MEDIUM", "evidence": "Vivek will complete the design next Monday."}
              ],
              "analysisSource": "%s"
            }
            """;


    // ============================================================
    // THE ANALYSIS
    // ============================================================

    @Test
    void readsWhichAnalyzerProducedTheResult() throws IOException
    {
        String address = startStandIn("/summarize", 200, ANSWER.formatted("FALLBACK"));

        MeetingAnalysis analysis = clientFor(address).analyze("transcript");

        assertEquals("FALLBACK", analysis.analysisSource());
        assertEquals("A short meeting.", analysis.summary());
        assertEquals(1, analysis.actionItems().size());
        assertEquals("next Monday", analysis.actionItems().get(0).dueDate());
    }


    @Test
    void anOlderAiServiceWithoutTheFieldStillWorks() throws IOException
    {
        String address = startStandIn("/summarize", 200, """
                {"summary": "Old service.", "keyDecisions": [], "actionItems": []}
                """);

        MeetingAnalysis analysis = clientFor(address).analyze("transcript");

        assertEquals("Old service.", analysis.summary());
        assertNull(analysis.analysisSource());
    }


    @Test
    void anEmptySummaryIsRejectedWithAClearMessage() throws IOException
    {
        String address = startStandIn("/summarize", 200, """
                {"summary": "", "keyDecisions": [], "actionItems": []}
                """);

        AiProcessingException error =
                assertThrows(AiProcessingException.class, () -> clientFor(address).analyze("transcript"));

        assertEquals("The AI service returned no summary", error.getMessage());
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
    }


    // ============================================================
    // THE TRANSCRIPTION AND ITS FAILURES
    // ============================================================

    @Test
    void aRecordingWithoutSpeechIsExplainedWithTheReasonFromTheAiService(@TempDir Path folder) throws IOException
    {
        Path recording = Files.write(folder.resolve("silence.wav"), new byte[] {1, 2, 3});

        String address = startStandIn(
                "/transcribe",
                422,
                "{\"detail\": \"No speech could be detected in the uploaded recording\"}"
        );

        AiProcessingException error =
                assertThrows(AiProcessingException.class, () -> clientFor(address).transcribe(recording.toFile()));

        assertEquals("No speech could be detected in the uploaded recording", error.getMessage());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
    }


    @Test
    void aServerErrorFromTheAiServiceKeepsItsReason(@TempDir Path folder) throws IOException
    {
        Path recording = Files.write(folder.resolve("talk.mp3"), new byte[] {1, 2, 3});

        String address = startStandIn(
                "/transcribe",
                500,
                "{\"detail\": \"Transcription failed: out of memory\"}"
        );

        AiProcessingException error =
                assertThrows(AiProcessingException.class, () -> clientFor(address).transcribe(recording.toFile()));

        assertEquals("Transcription failed: out of memory", error.getMessage());
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
    }


    @Test
    void anErrorWithoutAReasonStillGetsAReadableMessage(@TempDir Path folder) throws IOException
    {
        Path recording = Files.write(folder.resolve("talk.mp3"), new byte[] {1, 2, 3});

        String address = startStandIn("/transcribe", 500, "not json at all");

        AiProcessingException error =
                assertThrows(AiProcessingException.class, () -> clientFor(address).transcribe(recording.toFile()));

        assertEquals("The AI service could not transcribe the recording", error.getMessage());
    }


    @Test
    void anAiServiceThatIsNotRunningIsExplained(@TempDir Path folder) throws IOException
    {
        Path recording = Files.write(folder.resolve("talk.mp3"), new byte[] {1, 2, 3});

        // nothing listens on port 1
        AiServiceClient client = clientFor("http://127.0.0.1:1");

        AiProcessingException error =
                assertThrows(AiProcessingException.class, () -> client.transcribe(recording.toFile()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertTrue(error.getMessage().contains("not reachable"), error.getMessage());
        assertTrue(error.getMessage().contains("http://127.0.0.1:1"), error.getMessage());
    }
}
