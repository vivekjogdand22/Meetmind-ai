// Search for the meeting list. Every word you type has to appear somewhere
// in the meeting (any order, any case): its title, file name, summary,
// decisions, action items or transcript.

export function filterMeetings(meetings, query)
{
    const words =
        query
            .toLowerCase()
            .split(/\s+/)
            .filter(Boolean)

    if (words.length === 0)
    {
        return meetings
    }

    return meetings.filter(
        (meeting) =>
        {
            const text =
                [
                    meeting.title,
                    meeting.originalFileName,
                    meeting.summary,
                    meeting.keyDecisions,
                    meeting.actionItems,
                    meeting.transcript
                ]
                    .filter(Boolean)
                    .join(' ')
                    .toLowerCase()

            return words.every((word) => text.includes(word))
        }
    )
}
