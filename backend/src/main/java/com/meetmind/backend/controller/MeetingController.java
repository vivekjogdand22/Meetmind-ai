package com.meetmind.backend.controller;

import com.meetmind.backend.dto.AskDto;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.service.MeetingService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController
{
    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService)
    {
        this.meetingService = meetingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Meeting createMeeting(
            @RequestBody Meeting meeting)
    {
        return meetingService.saveMeeting(meeting);
    }

    @GetMapping
    public List<Meeting> getAllMeetings()
    {
        return meetingService.getAllMeetings();
    }

    @PutMapping("/{id}")
    public Meeting updateMeeting(
            @PathVariable Long id,
            @RequestBody Meeting meeting)
    {
        return meetingService.updateMeeting(id, meeting);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeeting(
            @PathVariable Long id)
    {
        meetingService.deleteMeeting(id);
    }

    @PostMapping("/{id}/ask")
    public AskDto.Answer askMeeting(
            @PathVariable Long id,
            @RequestBody AskDto.Request request)
    {
        return meetingService.askQuestion(
                id,
                request.question()
        );
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Meeting uploadMeeting(
            @RequestParam("title") String title,
            @RequestParam("file") MultipartFile file)
            throws IOException
    {
        return meetingService.uploadMeeting(title, file);
    }
}