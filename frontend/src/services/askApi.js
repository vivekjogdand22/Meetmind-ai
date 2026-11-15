const API_BASE_URL = 'http://localhost:8080/api/meetings'


async function readErrorMessage(response)
{
    const fallback = 'The question could not be answered'

    try
    {
        const errorData = await response.json()

        return errorData.message || errorData.error || fallback
    }
    catch
    {
        return fallback
    }
}


// Asks one question about one meeting. The answer comes only from that
// meeting's transcript:
//
//   { answer, answerable, unsupported, broad, sources, model, seconds }
//
// "sources" are the passages of the transcript the answer rests on, each
// with the exact words quoted from it.
export async function askMeeting(meetingId, question)
{
    const response = await fetch(
        `${API_BASE_URL}/${meetingId}/ask`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question })
        }
    )

    if (!response.ok)
    {
        throw new Error(await readErrorMessage(response))
    }

    return response.json()
}
