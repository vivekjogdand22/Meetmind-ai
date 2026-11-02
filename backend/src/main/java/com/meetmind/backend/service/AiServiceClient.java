package com.meetmind.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.exception.AiProcessingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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


    public AiServiceClient(
            @Value("${ai.service.url}") String aiServiceUrl,
            ObjectMapper objectMapper)
    {
        this.aiServiceUrl = aiServiceUrl;

        this.objectMapper = objectMapper;

        this.restTemplate = new RestTemplate();
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

            HttpStatus status =
                    response.getStatusCode().value() == 422
                            ? HttpStatus.UNPROCESSABLE_ENTITY
                            : HttpStatus.BAD_GATEWAY;

            return new AiProcessingException(
                    detail != null ? detail : whatFailed,
                    status,
                    exception
            );
        }


        // The AI service could not be reached at all (not started,
        // wrong address, connection dropped, timed out).
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