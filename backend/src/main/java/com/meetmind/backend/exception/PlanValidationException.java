package com.meetmind.backend.exception;

public class PlanValidationException extends RuntimeException
{
    public PlanValidationException(String message)
    {
        super(message);
    }
}
