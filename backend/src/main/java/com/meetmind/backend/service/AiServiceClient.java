package com.meetmind.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.dto.AskDto;
import com.meetmind.backend.exception.AiProcessingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.Map;


@Service
public class AiServiceClient
{
    private final String aiServiceUrl;

    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate;

    // Questions get a time limit so that a stuck model cannot hold a request
    // open forever. Embedding the transcript plus two model calls normally
    // take well under a minute.
    private final RestTemplate askRestTemplate;

    private static final int ASK_CONNECT_TIMEOUT_MS = 5_000;

    private static final int ASK_READ_TIMEOUT_MS = 240_000;


    public AiServiceClient(
            @Value("${ai.service.url}") String aiServiceUrl,
            ObjectMapper objectMapper)
    {
        this.aiServiceUrl = aiServiceUrl;

        this.objectMapper = objectMapper;

        this.restTemplate = new RestTemplate();

        SimpleClientHttpRequestFactory askFactory =
                new SimpleClientHttpRequestFactory();

        askFactory.setConnectTimeout(ASK_CONNECT_TIMEOUT_MS);

        askFactory.setReadTimeout(ASK_READ_TIMEOUT_MS);

        this.askRestTemplate = new RestTemplate(askFactory);
    }


    // ============================================================
    // TRANSCRIPTION
    // ============================================================

    public String transcribe(File audioFile)
    {
        try
        {
            FileSystemResource fileResource =
                    new FileSystemResource(audioFile);


            HttpHeaders fileHeaders =
                    new HttpHeaders();

            fileHeaders.setContentType(
                    MediaType.APPLICATION_OCTET_STREAM
            );

            fileHeaders.setContentDispositionFormData(
                    "file",
                    audioFile.getName()
            );


            HttpEntity<FileSystemResource> fileEntity =
                    new HttpEntity<>(
                            fileResource,
                            fileHeaders
                    );


            MultiValueMap<String, Object> body =
                    new LinkedMultiValueMap<>();

            body.add(
                    "file",
                    fileEntity
            );


            HttpHeaders requestHeaders =
                    new HttpHeaders();

            requestHeaders.setContentType(
                    MediaType.MULTIPART_FORM_DATA
            );


            HttpEntity<MultiValueMap<String, Object>>
                    requestEntity =
                    new HttpEntity<>(
                            body,
                            requestHeaders
                    );


            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            aiServiceUrl + "/transcribe",
                            requestEntity,
                            String.class
                    );


            JsonNode responseJson =
                    objectMapper.readTree(
                            response.getBody()
                    );


            JsonNode transcript =
                    responseJson.path("transcript");


            if (!transcript.isTextual()
                    || transcript.asText().isBlank())
            {
                throw new AiProcessingException(
                        "The AI service returned no transcript",
                        HttpStatus.BAD_GATEWAY,
                        null
                );
            }


            return transcript.asText();
        }
        catch (Exception exception)
        {
            throw explain(
                    "The AI service could not transcribe the recording",
                    exception
            );
        }
    }


    // ============================================================
    // MEETING ANALYSIS
    // ============================================================

    public MeetingAnalysis analyze(String transcript)
    {
        try
        {
            HttpHeaders requestHeaders =
                    new HttpHeaders();

            requestHeaders.setContentType(
                    MediaType.APPLICATION_JSON
            );


            Map<String, String> body =
                    Map.of(
                            "transcript",
                            transcript
                    );


            HttpEntity<Map<String, String>>
                    requestEntity =
                    new HttpEntity<>(
                            body,
                            requestHeaders
                    );


            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            aiServiceUrl + "/summarize",
                            requestEntity,
                            String.class
                    );


            JsonNode analysis =
                    objectMapper.readTree(
                            response.getBody()
                    );


            JsonNode summary =
                    analysis.path("summary");


            if (!summary.isTextual()
                    || summary.asText().isBlank())
            {
                throw new AiProcessingException(
                        "The AI service returned no summary",
                        HttpStatus.BAD_GATEWAY,
                        null
                );
            }


            return objectMapper.treeToValue(
                    analysis,
                    MeetingAnalysis.class
            );
        }
        catch (Exception exception)
        {
            throw explain(
                    "The AI service could not analyze the meeting",
                    exception
            );
        }
    }


    // ============================================================
    // ASKING A QUESTION ABOUT A MEETING
    // ============================================================

    public AskDto.Answer ask(String transcript, String question)
    {
        try
        {
            HttpHeaders requestHeaders =
                    new HttpHeaders();

            requestHeaders.setContentType(
                    MediaType.APPLICATION_JSON
            );


            HttpEntity<Map<String, String>>
                    requestEntity =
                    new HttpEntity<>(
                            Map.of(
                                    "transcript",
                                    transcript,
                                    "question",
                                    question
                            ),
                            requestHeaders
                    );


            ResponseEntity<String> response =
                    askRestTemplate.postForEntity(
                            aiServiceUrl + "/ask",
                            requestEntity,
                            String.class
                    );


            AskDto.Answer answer =
                    objectMapper.readValue(
                            response.getBody(),
                            AskDto.Answer.class
                    );


            if (answer.answer() == null
                    || answer.answer().isBlank())
            {
                throw new AiProcessingException(
                        "The AI service returned no answer",
                        HttpStatus.BAD_GATEWAY,
                        null
                );
            }


            return answer;
        }
        catch (Exception exception)
        {
            throw explain(
                    "The AI service could not answer the question",
                    exception
            );
        }
    }


    // ============================================================
    // TURNING FAILURES INTO MESSAGES A PERSON CAN ACT ON
    // ============================================================

    private AiProcessingException explain(
            String whatFailed,
            Exception exception)
    {
        // already explained where it happened
        if (exception instanceof AiProcessingException explained)
        {
            return explained;
        }


        // The AI service answered, but with an error. It puts the reason in
        // "detail", for example "No speech could be detected ...".
        if (exception instanceof RestClientResponseException response)
        {
            String detail =
                    readDetail(
                            response.getResponseBodyAsString()
                    );

            // 503 means a model (Ollama) is unavailable: say so, it is not
            // a broken AI service.
            HttpStatus status =
                    switch (response.getStatusCode().value())
                    {
                        case 400 -> HttpStatus.BAD_REQUEST;
                        case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                        case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
                        default -> HttpStatus.BAD_GATEWAY;
                    };

            return new AiProcessingException(
                    detail != null ? detail : whatFailed,
                    status,
                    exception
            );
        }


        // The AI service could not be reached at all (not started,
        // wrong address, connection dropped, timed out).
        if (exception instanceof ResourceAccessException
                && exception.getCause() instanceof java.net.SocketTimeoutException)
        {
            return new AiProcessingException(
                    "The AI service took too long to answer. "
                            + "Try again, or ask a shorter question.",
                    HttpStatus.GATEWAY_TIMEOUT,
                    exception
            );
        }


        if (exception instanceof ResourceAccessException)
        {
            return new AiProcessingException(
                    "The AI service is not reachable at "
                            + aiServiceUrl
                            + ". Start it and try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    exception
            );
        }


        return new AiProcessingException(
                whatFailed,
                HttpStatus.BAD_GATEWAY,
                exception
        );
    }


    private String readDetail(String body)
    {
        try
        {
            JsonNode detail =
                    objectMapper.readTree(body).path("detail");

            return detail.isTextual() && !detail.asText().isBlank()
                    ? detail.asText()
                    : null;
        }
        catch (Exception exception)
        {
            return null;
        }
    }
}