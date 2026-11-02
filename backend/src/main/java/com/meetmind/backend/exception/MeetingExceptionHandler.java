package com.meetmind.backend.exception;

import com.meetmind.backend.controller.MeetingController;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


// ============================================================
// CLEAR ERRORS FOR UPLOADING A RECORDING
//
// Without this, every problem reached the screen as just
// "Internal Server Error". Now the page can say, for example,
// "No speech could be detected in the uploaded recording".
// Only MeetingController is covered.
// ============================================================

@RestControllerAdvice(assignableTypes = MeetingController.class)
public class MeetingExceptionHandler
{
    // The AI service could not transcribe or analyze the recording.
    @ExceptionHandler(AiProcessingException.class)
    public ResponseEntity<ApiError> handleAiProblem(
            AiProcessingException exception)
    {
        return ResponseEntity
                .status(exception.getStatus())
                .body(
                        new ApiError(
                                exception.getStatus().value(),
                                exception.getStatus().getReasonPhrase(),
                                exception.getMessage()
                        )
                );
    }


    // The request itself was not acceptable, for example an empty file
    // or a file that is neither audio nor video.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(
            IllegalArgumentException exception)
    {
        return ResponseEntity
                .badRequest()
                .body(
                        new ApiError(
                                400,
                                "Bad Request",
                                exception.getMessage()
                        )
                );
    }
}
