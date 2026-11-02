package com.meetmind.backend.service;

import java.util.List;

// analysisSource is "LLM" when the language model produced the analysis and
// "FALLBACK" when the AI service had to use its simple rule-based extractor.
// It is null when an older AI service does not send it.
public record MeetingAnalysis(
        String summary,
        List<String> keyDecisions,
        List<ActionItem> actionItems,
        String analysisSource)
{
    public record ActionItem(
            String task,
            String owner,
            String dueDate,
            String priority,
            String evidence)
    {
    }
}