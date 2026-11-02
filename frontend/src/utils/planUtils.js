// Small helpers shared by the project-plan components.


// Turns a failed request into a sentence for the user.
export function describeError(error)
{
    // fetch() fails with a TypeError when the server cannot be reached
    if (error instanceof TypeError)
    {
        return 'Unable to reach the MeetMind backend. Is it running on port 8080?'
    }

    return error.message || 'Something went wrong.'
}


// 0.7218 -> "72%". A chance that comes from estimates is never shown as a
// sure thing (">99%", "<1%"), unless the estimates have no spread at all.
export function formatChance(probability, hasNoSpread = false)
{
    const percent = probability * 100

    if (hasNoSpread)
    {
        return `${Math.round(percent)}%`
    }

    if (percent > 99)
    {
        return '>99%'
    }

    if (percent < 1)
    {
        return '<1%'
    }

    return `${Math.round(percent)}%`
}


// 6 -> "6", 4.3333 -> "4.33", missing -> "-"
export function formatDays(value)
{
    if (value === null || value === undefined)
    {
        return '-'
    }

    return String(Number(value.toFixed(2)))
}


// Ids of every task that waits for the given task, directly or through
// other tasks. Such a task cannot also be a predecessor of the given
// task, because that would make a loop.
export function findDependentIds(tasks, taskId)
{
    const dependents = new Set()

    let foundNew = true

    while (foundNew)
    {
        foundNew = false

        for (const task of tasks)
        {
            if (dependents.has(task.id))
            {
                continue
            }

            const waitsForIt =
                task.predecessorIds.some(
                    (id) => id === taskId || dependents.has(id)
                )

            if (waitsForIt)
            {
                dependents.add(task.id)

                foundNew = true
            }
        }
    }

    return dependents
}


const TICK_STEPS = [0.5, 1, 2, 5, 10, 20, 25, 50, 100, 200, 500, 1000]


// The smallest step that keeps the time axis to about 8 labels or fewer.
export function chooseTickStep(totalDays)
{
    return (
        TICK_STEPS.find((step) => totalDays / step <= 8) ??
        TICK_STEPS[TICK_STEPS.length - 1]
    )
}
