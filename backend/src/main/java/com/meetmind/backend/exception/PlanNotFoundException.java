package com.meetmind.backend.exception;

public class PlanNotFoundException extends RuntimeException
{
    public PlanNotFoundException(String message)
    {
        super(message);
    }
}
