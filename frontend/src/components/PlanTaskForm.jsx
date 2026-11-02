import { useEffect, useId, useRef, useState } from 'react'

import { Check, X } from 'lucide-react'


function toInput(value)
{
    return value === null || value === undefined ? '' : String(value)
}


// Returns a sentence describing the first problem, or '' when the
// values are fine. The server checks everything again.
function findProblem(name, estimates)
{
    if (!name.trim())
    {
        return 'Give the task a name.'
    }

    const filled = estimates.filter((value) => value.trim() !== '')

    if (filled.length === 0)
    {
        return ''
    }

    if (filled.length < 3)
    {
        return 'Enter all three estimates, or leave all three empty.'
    }

    const [optimistic, mostLikely, pessimistic] = estimates.map(Number)

    if (estimates.some((value) => Number.isNaN(Number(value)) || Number(value) < 0))
    {
        return 'Estimates must be numbers of zero or more.'
    }

    if (optimistic > mostLikely || mostLikely > pessimistic)
    {
        return 'The estimates must grow from optimistic to most likely to pessimistic.'
    }

    return ''
}


function PlanTaskForm({ task, otherTasks, blockedIds, busy, error, onSave, onCancel })
{
    const formId = useId()

    const nameInput = useRef(null)


    const [name, setName] = useState(task?.name ?? '')

    const [owner, setOwner] = useState(task?.owner ?? '')

    const [optimistic, setOptimistic] = useState(toInput(task?.optimisticDays))

    const [mostLikely, setMostLikely] = useState(toInput(task?.mostLikelyDays))

    const [pessimistic, setPessimistic] = useState(toInput(task?.pessimisticDays))

    const [predecessorIds, setPredecessorIds] = useState(task ? task.predecessorIds : [])

    const [localError, setLocalError] = useState('')


    useEffect(() =>
    {
        nameInput.current?.focus()
    }, [])


    const togglePredecessor = (id) =>
    {
        setPredecessorIds(
            (current) =>
                current.includes(id)
                    ? current.filter((existing) => existing !== id)
                    : [...current, id]
        )
    }


    const handleSubmit = (event) =>
    {
        event.preventDefault()

        const problem = findProblem(name, [optimistic, mostLikely, pessimistic])

        setLocalError(problem)

        if (problem)
        {
            return
        }

        const hasEstimates = optimistic.trim() !== ''

        // a task chosen earlier may have been removed since
        const existingIds = new Set(otherTasks.map((other) => other.id))

        onSave({
            name: name.trim(),
            owner: owner.trim() || null,
            optimisticDays: hasEstimates ? Number(optimistic) : null,
            mostLikelyDays: hasEstimates ? Number(mostLikely) : null,
            pessimisticDays: hasEstimates ? Number(pessimistic) : null,
            predecessorIds: predecessorIds.filter((id) => existingIds.has(id))
        })
    }


    const handleKeyDown = (event) =>
    {
        if (event.key === 'Escape')
        {
            onCancel()
        }
    }


    const message = localError || error


    return (
        <form
            className="plan-form"
            onSubmit={handleSubmit}
            onKeyDown={handleKeyDown}
            aria-label={task ? `Edit ${task.name}` : 'Add a task'}
            noValidate
        >

            <div className="plan-form-grid">

                <div className="plan-field plan-field-wide">

                    <label htmlFor={`${formId}-name`}>
                        Task name
                    </label>

                    <input
                        id={`${formId}-name`}
                        ref={nameInput}
                        type="text"
                        value={name}
                        maxLength={500}
                        onChange={(event) => setName(event.target.value)}
                    />

                </div>


                <div className="plan-field plan-field-wide">

                    <label htmlFor={`${formId}-owner`}>
                        Owner
                    </label>

                    <input
                        id={`${formId}-owner`}
                        type="text"
                        value={owner}
                        maxLength={255}
                        placeholder="Unassigned"
                        onChange={(event) => setOwner(event.target.value)}
                    />

                </div>


                <div className="plan-field">

                    <label htmlFor={`${formId}-optimistic`}>
                        Optimistic (days)
                    </label>

                    <input
                        id={`${formId}-optimistic`}
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="0.5"
                        value={optimistic}
                        onChange={(event) => setOptimistic(event.target.value)}
                    />

                    <small>best case</small>

                </div>


                <div className="plan-field">

                    <label htmlFor={`${formId}-likely`}>
                        Most likely (days)
                    </label>

                    <input
                        id={`${formId}-likely`}
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="0.5"
                        value={mostLikely}
                        onChange={(event) => setMostLikely(event.target.value)}
                    />

                    <small>normal case</small>

                </div>


                <div className="plan-field">

                    <label htmlFor={`${formId}-pessimistic`}>
                        Pessimistic (days)
                    </label>

                    <input
                        id={`${formId}-pessimistic`}
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="0.5"
                        value={pessimistic}
                        onChange={(event) => setPessimistic(event.target.value)}
                    />

                    <small>worst case</small>

                </div>

            </div>


            <fieldset className="plan-predecessors">

                <legend>
                    Starts after (these must finish first)
                </legend>

                {
                    otherTasks.length === 0
                        ?
                        (
                            <p className="plan-task-hint">
                                There are no other tasks yet.
                            </p>
                        )
                        :
                        (
                            <div className="plan-predecessor-list">
                                {
                                    otherTasks.map(
                                        (other) =>
                                        {
                                            const blocked = blockedIds.has(other.id)

                                            return (
                                                <label
                                                    key={other.id}
                                                    className={
                                                        blocked
                                                            ? 'plan-check is-blocked'
                                                            : 'plan-check'
                                                    }
                                                    title={
                                                        blocked
                                                            ? 'This task already waits for the one you are editing. Choosing it would make a loop.'
                                                            : undefined
                                                    }
                                                >
                                                    <input
                                                        type="checkbox"
                                                        checked={predecessorIds.includes(other.id)}
                                                        disabled={blocked}
                                                        onChange={() => togglePredecessor(other.id)}
                                                    />

                                                    <span>
                                                        {other.name}
                                                    </span>
                                                </label>
                                            )
                                        }
                                    )
                                }
                            </div>
                        )
                }

            </fieldset>


            {
                message &&
                (
                    <p
                        className="plan-message plan-message-error"
                        role="alert"
                    >
                        {message}
                    </p>
                )
            }


            <div className="plan-form-actions">

                <button
                    type="submit"
                    className="plan-button plan-button-primary"
                    disabled={busy}
                >
                    <Check size={14} />
                    {busy ? 'Saving...' : 'Save task'}
                </button>

                <button
                    type="button"
                    className="plan-button plan-button-secondary"
                    onClick={onCancel}
                    disabled={busy}
                >
                    <X size={14} />
                    Cancel
                </button>

            </div>

        </form>
    )
}


export default PlanTaskForm
