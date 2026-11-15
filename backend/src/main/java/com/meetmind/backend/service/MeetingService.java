package com.meetmind.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.dto.AskDto;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.exception.MeetingNotFoundException;
import com.meetmind.backend.repository.MeetingRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;


@Service
public class MeetingService
{
    private final MeetingRepository meetingRepository;

    private final AiServiceClient aiServiceClient;

    private final ObjectMapper objectMapper;

    private final Path uploadDirectory;


    // The folder for uploaded recordings. It is "uploads" (next to where the
    // backend runs) unless meetmind.upload-dir says otherwise.
    public MeetingService(
            MeetingRepository meetingRepository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            @Value("${meetmind.upload-dir:uploads}") String uploadDirectory)
    {
        this.meetingRepository = meetingRepository;

        this.aiServiceClient = aiServiceClient;

        this.objectMapper = objectMapper;

        this.uploadDirectory = Paths.get(uploadDirectory);
    }


    // Every method below works on the meetings of ONE user. A meeting that
    // belongs to somebody else is reported as "not found".

    public Meeting requireOwned(
            Long id,
            Long ownerId)
    {
        return meetingRepository
                .findByIdAndOwnerId(id, ownerId)
                .orElseThrow(
                        () -> new MeetingNotFoundException(
                                "Meeting " + id + " was not found."
                        )
                );
    }


    public Meeting saveMeeting(
            Meeting meeting,
            Long ownerId)
    {
        meeting.setOwnerId(ownerId);

        return meetingRepository.save(meeting);
    }


    public List<Meeting> getAllMeetings(Long ownerId)
    {
        return meetingRepository.findByOwnerIdOrderByIdAsc(ownerId);
    }


    public Meeting updateMeeting(
            Long id,
            Meeting updatedMeeting,
            Long ownerId)
    {
        Meeting existingMeeting =
                requireOwned(id, ownerId);


        existingMeeting.setTitle(
                updatedMeeting.getTitle()
        );

        existingMeeting.setTranscript(
                updatedMeeting.getTranscript()
        );

        existingMeeting.setSummary(
                updatedMeeting.getSummary()
        );

        existingMeeting.setKeyDecisions(
                updatedMeeting.getKeyDecisions()
        );

        existingMeeting.setActionItems(
                updatedMeeting.getActionItems()
        );


        return meetingRepository.save(
                existingMeeting
        );
    }


    // "Ask Your Meeting": the answer comes only from this meeting's
    // transcript. Nothing is stored.
    public static final int MAX_QUESTION_CHARS = 500;

    public AskDto.Answer askQuestion(
            Long id,
            String question,
            Long ownerId)
    {
        String cleanQuestion =
                question == null ? "" : question.strip();

        if (cleanQuestion.isEmpty())
        {
            throw new IllegalArgumentException(
                    "Please type a question."
            );
        }

        if (cleanQuestion.length() > MAX_QUESTION_CHARS)
        {
            throw new IllegalArgumentException(
                    "The question is too long. Please keep it under "
                            + MAX_QUESTION_CHARS
                            + " characters."
            );
        }


        Meeting meeting =
                requireOwned(id, ownerId);

        if (meeting.getTranscript() == null
                || meeting.getTranscript().isBlank())
        {
            throw new IllegalArgumentException(
                    "This meeting has no transcript to ask about."
            );
        }


        return aiServiceClient.ask(
                meeting.getTranscript(),
                cleanQuestion
        );
    }


    public void deleteMeeting(
            Long id,
            Long ownerId)
    {
        Meeting meeting =
                requireOwned(id, ownerId);


        if (meeting.getFileName() != null)
        {
            try
            {
                Files.deleteIfExists(
                        uploadDirectory.resolve(
                                meeting.getFileName()
                        )
                );
            }
            catch (IOException exception)
            {
                throw new RuntimeException(
                        "Unable to delete meeting file",
                        exception
                );
            }
        }


        meetingRepository.delete(meeting);
    }


    public Meeting uploadMeeting(
            String title,
            MultipartFile file,
            Long ownerId)
            throws IOException
    {
        validateUploadedFile(file);


        Files.createDirectories(
                uploadDirectory
        );


        String originalFileName =
                file.getOriginalFilename();


        String extension =
                getFileExtension(
                        originalFileName
                );


        String storedFileName =
                UUID.randomUUID()
                        + extension;


        Path destination =
                uploadDirectory.resolve(
                        storedFileName
                );


        Files.copy(
                file.getInputStream(),
                destination,
                StandardCopyOption.REPLACE_EXISTING
        );


        Meeting meeting =
                createMeetingRecord(
                        title,
                        file,
                        originalFileName,
                        storedFileName
                );


        meeting.setOwnerId(ownerId);

        meeting =
                meetingRepository.save(
                        meeting
                );


        try
        {
            File meetingFile =
                    destination.toFile();


            // ====================================================
            // STEP 1: TRANSCRIPTION
            // ====================================================

            String transcript =
                    aiServiceClient.transcribe(
                            meetingFile
                    );


            meeting.setTranscript(
                    transcript
            );

            meeting.setStatus(
                    "TRANSCRIBED"
            );


            meeting =
                    meetingRepository.save(
                            meeting
                    );


            // ====================================================
            // STEP 2: AI MEETING ANALYSIS
            // ====================================================

            MeetingAnalysis analysis =
                    aiServiceClient.analyze(
                            transcript
                    );


            meeting.setSummary(
                    analysis.summary()
            );


            meeting.setAnalysisSource(
                    analysis.analysisSource()
            );


            meeting.setKeyDecisions(
                    serializeToJson(
                            analysis.keyDecisions()
                    )
            );


            meeting.setActionItems(
                    serializeToJson(
                            analysis.actionItems()
                    )
            );


            meeting.setStatus(
                    "COMPLETED"
            );


            return meetingRepository.save(
                    meeting
            );
        }
        catch (Exception exception)
        {
            meeting.setStatus(
                    "AI_PROCESSING_FAILED"
            );


            meetingRepository.save(
                    meeting
            );


            // MeetingExceptionHandler finds the AiProcessingException inside
            // this wrapper and shows its message to the user.
            throw new RuntimeException(
                    "AI processing failed",
                    exception
            );
        }
    }


    private void validateUploadedFile(
            MultipartFile file)
    {
        if (file.isEmpty())
        {
            throw new IllegalArgumentException(
                    "Please select an audio or video file"
            );
        }


        String contentType =
                file.getContentType();


        if (contentType == null
                || (!contentType.startsWith("audio/")
                && !contentType.startsWith("video/")))
        {
            throw new IllegalArgumentException(
                    "Only audio and video files are supported"
            );
        }
    }


    private String getFileExtension(
            String originalFileName)
    {
        if (originalFileName != null
                && originalFileName.contains("."))
        {
            return originalFileName.substring(
                    originalFileName.lastIndexOf(".")
            );
        }


        return "";
    }


    private Meeting createMeetingRecord(
            String title,
            MultipartFile file,
            String originalFileName,
            String storedFileName)
    {
        Meeting meeting =
                new Meeting();


        meeting.setTitle(
                title
        );

        meeting.setFileName(
                storedFileName
        );

        meeting.setOriginalFileName(
                originalFileName
        );

        meeting.setFileType(
                file.getContentType()
        );

        meeting.setFileSize(
                file.getSize()
        );

        meeting.setStatus(
                "UPLOADED"
        );


        return meeting;
    }


    private String serializeToJson(
            Object value)
    {
        try
        {
            return objectMapper
                    .writeValueAsString(
                            value
                    );
        }
        catch (JsonProcessingException exception)
        {
            throw new RuntimeException(
                    "Unable to serialize meeting analysis",
                    exception
            );
        }
    }
}