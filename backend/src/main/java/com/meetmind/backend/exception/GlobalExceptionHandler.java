package com.meetmind.backend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


// Problems that can happen on any endpoint.
@RestControllerAdvice
public class GlobalExceptionHandler
{
    // The meeting does not exist, or it belongs to somebody else.
    @ExceptionHandler(MeetingNotFoundException.class)
    public ResponseEntity<ApiError> handleMissingMeeting(
            MeetingNotFoundException exception)
    {
        return ResponseEntity
                .status(404)
                .body(
                        new ApiError(
                                404,
                                "Not Found",
                                exception.getMessage()
                        )
                );
    }
}
