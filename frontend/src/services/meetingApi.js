import { apiFetch } from './session'

const MEETINGS = '/meetings'


export async function getAllMeetings()
{
    const response = await apiFetch(MEETINGS)

    if (!response.ok)
    {
        throw new Error('Failed to fetch meetings')
    }

    return response.json()
}


export async function uploadMeeting(title, file)
{
    const formData = new FormData()

    formData.append('title', title)
    formData.append('file', file)

    const response = await apiFetch(
        `${MEETINGS}/upload`,
        {
            method: 'POST',
            body: formData
        }
    )

    if (!response.ok)
    {
        let errorMessage = 'Failed to process meeting'

        try
        {
            const errorData = await response.json()

            errorMessage =
                errorData.message ||
                errorData.error ||
                errorMessage
        }
        catch
        {
            // Keep default error message
        }

        throw new Error(errorMessage)
    }

    return response.json()
}


export async function deleteMeeting(id)
{
    const response = await apiFetch(
        `${MEETINGS}/${id}`,
        {
            method: 'DELETE'
        }
    )

    if (!response.ok)
    {
        throw new Error('Failed to delete meeting')
    }
}