package com.meetmind.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;


// The "Ask Your Meeting" request and answer.
public class AskDto
{
    private AskDto()
    {
    }


    // What the user typed.
    public record Request(String question)
    {
    }


    // Where the answer comes from: a passage of the transcript and the exact
    // words quoted from it. The AI service has already checked that the quote
    // is really in the transcript.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(
            int passage,
            String text,
            String quote,
            double score)
    {
    }


    // "answerable" is false when the meeting does not say (or when the AI
    // could not confirm an answer: then "unsupported" is true).
    // "broad" means the question asked for a lot ("summarize", "list all"),
    // which one answer from a few passages cannot cover.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Answer(
            String answer,
            boolean answerable,
            boolean unsupported,
            boolean broad,
            List<Source> sources,
            String model,
            double seconds)
    {
    }
}
