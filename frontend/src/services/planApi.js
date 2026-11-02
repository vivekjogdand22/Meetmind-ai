const API_BASE_URL = 'http://localhost:8080/api/meetings'


async function readErrorMessage(response)
{
    const fallback = 'The request failed'

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


async function request(meetingId, path, method = 'GET', body)
{
    const options = { method }

    if (body !== undefined)
    {
        options.headers = { 'Content-Type': 'application/json' }
        options.body = JSON.stringify(body)
    }

    const response = await fetch(
        `${API_BASE_URL}/${meetingId}/plan${path}`,
        options
    )

    if (!response.ok)
    {
        throw new Error(await readErrorMessage(response))
    }

    // DELETE of the whole plan answers with "204 No Content"
    if (response.status === 204)
    {
        return null
    }

    return response.json()
}


// Every call below answers with the whole updated plan
// (tasks, schedule and critical path), except deletePlan.

// With targetDays, the answer also says how likely the project is to
// finish within that many days (schedule.probabilityOfFinishingByTarget).
export async function getPlan(meetingId, targetDays)
{
    const query =
        targetDays === undefined || targetDays === null
            ? ''
            : `?targetDays=${encodeURIComponent(targetDays)}`

    return request(meetingId, query)
}


// "What if this task takes delayDays longer?" Nothing is saved.
export async function simulateDelay(meetingId, taskId, delayDays)
{
    return request(meetingId, '/what-if', 'POST', { taskId, delayDays })
}


export async function createPlan(meetingId)
{
    return request(meetingId, '', 'POST')
}


export async function deletePlan(meetingId)
{
    return request(meetingId, '', 'DELETE')
}


export async function addTask(meetingId, task)
{
    return request(meetingId, '/tasks', 'POST', task)
}


export async function updateTask(meetingId, taskId, task)
{
    return request(meetingId, `/tasks/${taskId}`, 'PUT', task)
}


export async function deleteTask(meetingId, taskId)
{
    return request(meetingId, `/tasks/${taskId}`, 'DELETE')
}
