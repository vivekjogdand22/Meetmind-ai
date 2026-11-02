import { useEffect, useId, useState } from 'react'

import { FlaskConical, X } from 'lucide-react'

import PlanTimeline from './PlanTimeline'

import { simulateDelay } from '../services/planApi'

import {
    describeError,
    formatDays
} from '../utils/planUtils'


function describeOutcome(result)
{
    const name = result.delayedTaskName

    const slack = formatDays(result.slackBeforeDelayDays)

    if (result.absorbedBySlack)
    {
        const left = Math.max(0, result.slackBeforeDelayDays - result.delayDays)

        return {
            level: 'is-good',
            headline: 'The finish date does not change',
            detail:
                `"${name}" has ${slack} days of slack, so a delay of ` +
                `${formatDays(result.delayDays)} days is absorbed. ` +
                `${formatDays(left)} days of slack would remain.`
        }
    }

    const coveredBySlack =
        result.slackBeforeDelayDays > 0
            ? ` Its ${slack} days of slack cover part of the delay.`
            : ''

    return {
        level: 'is-bad',
        headline:
            `The project would be ${formatDays(result.projectDelayDays)} days late`,
        detail:
            `It would finish on day ${formatDays(result.newDurationDays)} ` +
            `instead of day ${formatDays(result.baselineDurationDays)}.${coveredBySlack}`
    }
}


// "What if this task takes longer than planned?"
// Nothing is saved: the backend only calculates the effect.
function PlanWhatIf({ meetingId, tasks, plan })
{
    const taskId = useId()

    const delayId = useId()

    const [selectedId, setSelectedId] = useState('')

    const [delay, setDelay] = useState('2')

    const [result, setResult] = useState(null)

    const [busy, setBusy] = useState(false)

    const [error, setError] = useState('')


    // an old answer no longer describes the plan once the plan has changed
    useEffect(() =>
    {
        setResult(null)

        setError('')
    }, [plan])


    // the chosen task may have been removed, so fall back to the first one
    const chosenId =
        tasks.some((task) => String(task.id) === selectedId)
            ? selectedId
            : String(tasks[0].id)


    const handleSubmit = async (event) =>
    {
        event.preventDefault()

        const days = delay.trim() === '' ? NaN : Number(delay)

        if (!Number.isFinite(days) || days < 0)
        {
            setError('Enter the delay as zero or a positive number of days.')

            setResult(null)

            return
        }

        setBusy(true)

        setError('')

        try
        {
            setResult(await simulateDelay(meetingId, Number(chosenId), days))
        }
        catch (problem)
        {
            setError(describeError(problem))

            setResult(null)
        }
        finally
        {
            setBusy(false)
        }
    }


    const outcome = result ? describeOutcome(result) : null


    return (
        <div className="plan-card">

            <div className="plan-card-title">
                <FlaskConical size={14} />
                What if a task is late?
            </div>

            <p className="plan-card-text">
                Try a delay to see whether the project would finish later.
                This is only a simulation: your plan is not changed.
            </p>


            <form
                className="plan-inline-form"
                onSubmit={handleSubmit}
                noValidate
            >

                <div className="plan-field plan-field-grow">

                    <label htmlFor={taskId}>
                        Task
                    </label>

                    <select
                        id={taskId}
                        value={chosenId}
                        onChange={(event) => setSelectedId(event.target.value)}
                    >
                        {
                            tasks.map(
                                (task, index) =>
                                (
                                    <option
                                        key={task.id}
                                        value={task.id}
                                    >
                                        {index + 1}. {task.name}
                                    </option>
                                )
                            )
                        }
                    </select>

                </div>


                <div className="plan-field">

                    <label htmlFor={delayId}>
                        Delay (days)
                    </label>

                    <input
                        id={delayId}
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="0.5"
                        value={delay}
                        onChange={(event) => setDelay(event.target.value)}
                    />

                </div>


                <button
                    type="submit"
                    className="plan-button plan-button-primary"
                    disabled={busy}
                >
                    {busy ? 'Calculating...' : 'Simulate'}
                </button>

            </form>


            {
                error &&
                (
                    <p
                        className="plan-message plan-message-error"
                        role="alert"
                    >
                        {error}
                    </p>
                )
            }


            {
                result &&
                (
                    <div
                        className={`plan-outcome ${outcome.level}`}
                        role="status"
                    >

                        <div className="plan-outcome-head">

                            <strong>
                                {outcome.headline}
                            </strong>

                            <button
                                type="button"
                                className="plan-icon-button"
                                onClick={() => setResult(null)}
                                aria-label="Close the simulation result"
                                title="Close"
                            >
                                <X size={14} />
                            </button>

                        </div>

                        <p className="plan-card-text">
                            {outcome.detail}
                        </p>

                        {
                            result.shiftedTasks.length > 0 &&
                            (
                                <ul className="plan-shifts">
                                    {
                                        result.shiftedTasks.map(
                                            (shift) =>
                                            (
                                                <li key={shift.id}>

                                                    <span>
                                                        {shift.name}
                                                    </span>

                                                    <span className="plan-shift-days">
                                                        day {formatDays(shift.baselineFinish)}
                                                        {' → '}
                                                        day {formatDays(shift.newFinish)}
                                                        {' '}
                                                        (+{formatDays(shift.shiftDays)})
                                                    </span>

                                                </li>
                                            )
                                        )
                                    }
                                </ul>
                            )
                        }

                        <div className="plan-section-title">
                            <span>Timeline if this happened (not saved)</span>
                        </div>

                        <PlanTimeline
                            tasks={
                                result.scenarioTasks.map(
                                    (task) =>
                                    ({
                                        ...task,
                                        expectedDays: task.earliestFinish - task.earliestStart
                                    })
                                )
                            }
                            durationDays={result.newDurationDays}
                        />

                    </div>
                )
            }

        </div>
    )
}


export default PlanWhatIf
