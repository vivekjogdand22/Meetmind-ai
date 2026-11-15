package com.meetmind.backend.exception;

import com.meetmind.backend.controller.AuthController;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


// Clear messages for registering and logging in.
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler
{
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadInput(IllegalArgumentException exception)
    {
        return reply(400, "Bad Request", exception.getMessage());
    }


    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> handleEmailTaken(EmailAlreadyUsedException exception)
    {
        return reply(409, "Conflict", exception.getMessage());
    }


    @ExceptionHandler(InvalidLoginException.class)
    public ResponseEntity<ApiError> handleBadLogin(InvalidLoginException exception)
    {
        return reply(401, "Unauthorized", exception.getMessage());
    }


    private static ResponseEntity<ApiError> reply(int status, String error, String message)
    {
        return ResponseEntity
                .status(status)
                .body(new ApiError(status, error, message));
    }
}
