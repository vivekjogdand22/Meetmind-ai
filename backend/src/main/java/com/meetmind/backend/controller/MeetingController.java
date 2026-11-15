package com.meetmind.backend.controller;

import com.meetmind.backend.dto.AskDto;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.security.CurrentUser;
import com.meetmind.backend.service.MeetingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

// Every endpoint works only on the meetings of the logged-in user.
@RestController
@Tag(name = "Meetings", description = "Upload recordings, read results, delete, and ask questions")
@RequestMapping("/api/meetings")
public class MeetingController
{
    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService)
    {
        this.meetingService = meetingService;
    }

    @Operation(summary = "Create a meeting record directly (without a recording)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Meeting createMeeting(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Meeting meeting)
    {
        return meetingService.saveMeeting(meeting, CurrentUser.id(jwt));
    }

    @Operation(summary = "List your meetings, oldest first")
    @GetMapping
    public List<Meeting> getAllMeetings(
            @AuthenticationPrincipal Jwt jwt)
    {
        return meetingService.getAllMeetings(CurrentUser.id(jwt));
    }

    @Operation(summary = "Update a meeting's title, summary, decisions or action items")
    @PutMapping("/{id}")
    public Meeting updateMeeting(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody Meeting meeting)
    {
        return meetingService.updateMeeting(id, meeting, CurrentUser.id(jwt));
    }

    @Operation(summary = "Delete a meeting with its recording, results and plan")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeeting(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id)
    {
        meetingService.deleteMeeting(id, CurrentUser.id(jwt));
    }

    @Operation(summary = "Ask a question answered only from this meeting's transcript")
    @PostMapping("/{id}/ask")
    public AskDto.Answer askMeeting(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody AskDto.Request request)
    {
        return meetingService.askQuestion(
                id,
                request.question(),
                CurrentUser.id(jwt)
        );
    }

    @Operation(summary = "Upload an audio or video recording: transcribe it and analyze it with AI")
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Meeting uploadMeeting(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("title") String title,
            @RequestParam("file") MultipartFile file)
            throws IOException
    {
        return meetingService.uploadMeeting(title, file, CurrentUser.id(jwt));
    }
}
