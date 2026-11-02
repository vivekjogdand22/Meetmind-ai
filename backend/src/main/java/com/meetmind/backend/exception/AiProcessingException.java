package com.meetmind.backend.exception;

import org.springframework.http.HttpStatus;


// Something went wrong while the AI service transcribed or analyzed a
// recording. The message is meant to be shown to the user, so it says what
// happened and, where possible, what to do about it.
public class AiProcessingException extends RuntimeException
{
    private final HttpStatus status;


    public AiProcessingException(
            String message,
            HttpStatus status,
            Throwable cause)
    {
        super(message, cause);

        this.status = status;
    }


    public HttpStatus getStatus()
    {
        return status;
    }
}
