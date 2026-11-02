package com.meetmind.backend.exception;

// The JSON body of every error the API reports on purpose.
// "message" is written for people; the frontend shows it as it is.
public record ApiError(
        int status,
        String error,
        String message)
{
}
