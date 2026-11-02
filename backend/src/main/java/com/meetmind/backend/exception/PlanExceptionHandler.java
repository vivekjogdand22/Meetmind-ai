package com.meetmind.backend.exception;

import com.meetmind.backend.controller.PlanController;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;


// ============================================================
// TURNS PROBLEMS IN THE PLAN API INTO CLEAR JSON ERRORS
//
// Only PlanController is covered, so the behavior of the older
// meeting endpoints does not change.
// The body has a "message" field, which the frontend already reads.
// ============================================================

@RestControllerAdvice(assignableTypes = PlanController.class)
public class PlanExceptionHandler
{
    @ExceptionHandler(PlanValidationException.class)
    public ResponseEntity<ApiError> handleValidation(
            PlanValidationException exception)
    {
        return respond(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
    }


    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            PlanNotFoundException exception)
    {
        return respond(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
    }


    @ExceptionHandler(PlanConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            PlanConflictException exception)
    {
        return respond(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(
            MethodArgumentNotValidException exception)
    {
        String message =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(error -> error.getDefaultMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));

        return respond(
                HttpStatus.BAD_REQUEST,
                message
        );
    }


    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleUnreadableRequest(
            Exception exception)
    {
        return respond(
                HttpStatus.BAD_REQUEST,
                "The request body or a parameter is missing or not valid"
        );
    }


    private ResponseEntity<ApiError> respond(
            HttpStatus status,
            String message)
    {
        return ResponseEntity
                .status(status)
                .body(
                        new ApiError(
                                status.value(),
                                status.getReasonPhrase(),
                                message
                        )
                );
    }
}
