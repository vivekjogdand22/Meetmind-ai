import { useEffect, useState } from 'react'

import {
    AlertTriangle,
    CalendarClock,
    ChevronRight,
    Plus,
    RotateCcw
} from 'lucide-react'

import './ProjectPlan.css'

import PlanDeadline from './PlanDeadline'
import PlanTaskCard from './PlanTaskCard'
import PlanTaskForm from './PlanTaskForm'
import PlanTimeline from './PlanTimeline'
import PlanWhatIf from './PlanWhatIf'

import {
    addTask,
    createPlan,
    deletePlan,
    deleteTask,
    getPlan,
    updateTask
} from '../services/planApi'

import {
    describeError,
    findDependentIds,
    formatDays
} from '../utils/planUtils'


function ProjectPlan({ meetingId, actionItemCount })
{
    const [plan, setPlan] = useState(null)

    const [loading, setLoading] = useState(true)

    const [loadError, setLoadError] = useState('')

    const [reloadCount, setReloadCount] = useState(0)

    // true while a save, delete or create request is running
    const [busy, setBusy] = useState(false)

    // null, 'new' (adding a task), or the id of the task being edited
    const [editing, setEditing] = useState(null)

    const [actionError, setActionError] = useState('')


    useEffect(() =>
    {
        let ignore = false

        async function loadPlan()
        {
            setLoading(true)

            setLoadError('')

            try
            {
                const loadedPlan = await getPlan(meetingId)

                if (!ignore)
                {
                    setPlan(loadedPlan)
                }
            }
            catch (error)
            {
                if (!ignore)
                {
                    setLoadError(describeError(error))
                }
            }
            finally
            {
                if (!ignore)
                {
                    setLoading(false)
                }
            }
        }

        loadPlan()

        // ignore the answer if the meeting changed in the meantime
        return () =>
        {
            ignore = true
        }
    }, [meetingId, reloadCount])


    const tasks = plan ? plan.tasks : []

    const scheduled = Boolean(plan && plan.readyToSchedule)

    const schedule = plan ? plan.schedule : null

    const namesById = Object.fromEntries(
        tasks.map((task) => [task.id, task.name])
    )


    // Shows the plan as the backend has it now. Used after a failed action,
    // because the plan may have been changed somewhere else in the meantime.
    const refreshPlan = async () =>
    {
        try
        {
            const currentPlan = await getPlan(meetingId)

            setPlan(currentPlan)

            // the task being edited may be gone
            setEditing(
                (current) =>
                    current === null ||
                    current === 'new' ||
                    currentPlan.tasks.some((task) => task.id === current)
                        ? current
                        : null
            )
        }
        catch
        {
            // keep what is on screen
        }
    }


    // Runs a request that answers with the updated plan.
    // Returns true when it worked.
    const runAction = async (request) =>
    {
        setBusy(true)

        setActionError('')

        try
        {
            const updatedPlan = await request()

            if (updatedPlan)
            {
                setPlan(updatedPlan)
            }

            return true
        }
        catch (error)
        {
            setActionError(describeError(error))

            await refreshPlan()

            return false
        }
        finally
        {
            setBusy(false)
        }
    }


    const handleCreatePlan = () =>
    {
        runAction(() => createPlan(meetingId))
    }


    const handleStartEdit = (target) =>
    {
        setActionError('')

        setEditing(target)
    }


    const handleCancelEdit = () =>
    {
        setActionError('')

        setEditing(null)
    }


    const handleSave = async (taskId, values) =>
    {
        const saved = await runAction(
            () =>
                taskId === 'new'
                    ? addTask(meetingId, values)
                    : updateTask(meetingId, taskId, values)
        )

        if (saved)
        {
            setEditing(null)
        }
    }


    const handleDeleteTask = (task) =>
    {
        if (!window.confirm(`Remove "${task.name}" from the plan?`))
        {
            return
        }

        runAction(() => deleteTask(meetingId, task.id))
    }


    const handleResetPlan = async () =>
    {
        const confirmed = window.confirm(
            'Remove the whole project plan of this meeting? ' +
            'Your estimates and dependencies will be lost. ' +
            'The AI action items are not affected.'
        )

        if (!confirmed)
        {
            return
        }

        setEditing(null)

        await runAction(
            async () =>
            {
                await deletePlan(meetingId)

                return getPlan(meetingId)
            }
        )
    }


    const errorBanner =
        actionError && editing === null
            ?
            (
                <p
                    className="plan-message plan-message-error"
                    role="alert"
                >
                    <AlertTriangle size={14} />
                    {actionError}
                </p>
            )
            :
            null


    const itemWord = actionItemCount === 1 ? 'action item' : 'action items'

    const createLabel = `Create plan from ${actionItemCount} ${itemWord}`


    let body


    if (loading)
    {
        body = (
            <p className="plan-loading">
                <span className="button-spinner"></span>
                Loading the project plan...
            </p>
        )
    }
    else if (loadError)
    {
        body = (
            <div className="plan-empty">

                <p className="plan-message plan-message-error" role="alert">
                    <AlertTriangle size={14} />
                    {loadError}
                </p>

                <div className="plan-empty-actions">
                    <button
                        type="button"
                        className="plan-button plan-button-secondary"
                        onClick={() => setReloadCount((count) => count + 1)}
                    >
                        <RotateCcw size={13} />
                        Try again
                    </button>
                </div>

            </div>
        )
    }
    else if (tasks.length === 0 && editing !== 'new')
    {
        body = (
            <div className="plan-empty">

                {errorBanner}

                <p>
                    A project plan turns the action items of this meeting into a
                    schedule. You give every task three time estimates and say
                    which tasks must finish first. MeetMind then works out how
                    long the whole project takes, which tasks are critical, and
                    how much slack the others have.
                </p>

                <div className="plan-empty-actions">

                    {
                        actionItemCount > 0 &&
                        (
                            <button
                                type="button"
                                className="plan-button plan-button-primary"
                                onClick={handleCreatePlan}
                                disabled={busy}
                            >
                                <CalendarClock size={14} />
                                {busy ? 'Creating...' : createLabel}
                            </button>
                        )
                    }

                    <button
                        type="button"
                        className="plan-button plan-button-secondary"
                        onClick={() => handleStartEdit('new')}
                        disabled={busy}
                    >
                        <Plus size={14} />
                        Add a task manually
                    </button>

                </div>

                {
                    actionItemCount === 0 &&
                    (
                        <p className="plan-empty-note">
                            This meeting has no action items to copy.
                        </p>
                    )
                }

            </div>
        )
    }
    else
    {
        const criticalCount = tasks.filter((task) => task.critical).length

        const pathNames =
            schedule
                ? schedule.criticalPath.map((id) => namesById[id])
                : []

        body = (
            <>

                {errorBanner}


                {
                    scheduled
                        ?
                        (
                            <section aria-label="Schedule summary">

                                <div className="plan-summary">

                                    <div className="plan-tile">
                                        <span className="plan-tile-label">Project duration</span>
                                        <span className="plan-tile-value">
                                            {formatDays(schedule.projectDurationDays)}
                                            <small> days</small>
                                        </span>
                                        <span className="plan-tile-note">
                                            typical uncertainty ±{formatDays(schedule.projectStandardDeviation)} days
                                        </span>
                                    </div>

                                    <div className="plan-tile">
                                        <span className="plan-tile-label">Critical tasks</span>
                                        <span className="plan-tile-value">
                                            {criticalCount}
                                            <small> of {tasks.length}</small>
                                        </span>
                                        <span className="plan-tile-note">
                                            a delay in any of them delays the finish
                                        </span>
                                    </div>

                                    <div className="plan-tile plan-tile-wide">
                                        <span className="plan-tile-label">Critical path</span>
                                        <ol className="plan-path">
                                            {
                                                pathNames.map(
                                                    (name, index) =>
                                                    (
                                                        <li key={`${name}-${index}`}>
                                                            {index > 0 && <ChevronRight size={14} aria-hidden="true" />}
                                                            <span>{name}</span>
                                                        </li>
                                                    )
                                                )
                                            }
                                        </ol>
                                        {
                                            criticalCount > pathNames.length &&
                                            (
                                                <span className="plan-tile-note">
                                                    Other critical tasks run in parallel to this chain.
                                                </span>
                                            )
                                        }
                                    </div>

                                </div>

                            </section>
                        )
                        :
                        (
                            <p className="plan-message plan-message-info">
                                Enter the three estimates, in days, for every task to
                                see the schedule.
                                {
                                    plan.tasksMissingEstimates.length > 0 &&
                                    ` Still missing: ${plan.tasksMissingEstimates.join(', ')}.`
                                }
                            </p>
                        )
                }


                {
                    scheduled &&
                    (
                        <section aria-label="Timeline">

                            <div className="plan-section-title">
                                <span>Timeline</span>
                            </div>

                            <PlanTimeline
                                tasks={tasks}
                                durationDays={schedule.projectDurationDays}
                            />

                        </section>
                    )
                }


                {
                    scheduled &&
                    (
                        <section aria-label="Plan checks">

                            <div className="plan-section-title">
                                <span>Plan checks</span>
                            </div>

                            <div className="plan-checks">

                                <PlanDeadline
                                    meetingId={meetingId}
                                    plan={plan}
                                />

                                <PlanWhatIf
                                    meetingId={meetingId}
                                    tasks={tasks}
                                    plan={plan}
                                />

                            </div>

                        </section>
                    )
                }


                <section aria-label="Tasks">

                    <div className="plan-section-title">
                        <span>Tasks ({tasks.length})</span>
                    </div>

                    <ol className="plan-task-list">

                        {
                            tasks.map(
                                (task, index) =>
                                {
                                    if (editing === task.id)
                                    {
                                        return (
                                            <li
                                                key={task.id}
                                                className="plan-task is-editing"
                                            >
                                                <PlanTaskForm
                                                    task={task}
                                                    otherTasks={tasks.filter((other) => other.id !== task.id)}
                                                    blockedIds={findDependentIds(tasks, task.id)}
                                                    busy={busy}
                                                    error={actionError}
                                                    onSave={(values) => handleSave(task.id, values)}
                                                    onCancel={handleCancelEdit}
                                                />
                                            </li>
                                        )
                                    }

                                    return (
                                        <PlanTaskCard
                                            key={task.id}
                                            task={task}
                                            number={index + 1}
                                            namesById={namesById}
                                            scheduled={scheduled}
                                            busy={busy}
                                            onEdit={() => handleStartEdit(task.id)}
                                            onDelete={() => handleDeleteTask(task)}
                                        />
                                    )
                                }
                            )
                        }

                        {
                            editing === 'new' &&
                            (
                                <li className="plan-task is-editing">
                                    <PlanTaskForm
                                        task={null}
                                        otherTasks={tasks}
                                        blockedIds={new Set()}
                                        busy={busy}
                                        error={actionError}
                                        onSave={(values) => handleSave('new', values)}
                                        onCancel={handleCancelEdit}
                                    />
                                </li>
                            )
                        }

                    </ol>


                    {
                        editing !== 'new' &&
                        (
                            <button
                                type="button"
                                className="plan-button plan-button-secondary plan-add"
                                onClick={() => handleStartEdit('new')}
                                disabled={busy}
                            >
                                <Plus size={14} />
                                Add a task
                            </button>
                        )
                    }

                </section>

            </>
        )
    }


    return (
        <article
            className="insight-panel plan-panel"
            aria-busy={busy}
        >

            <div className="panel-heading">

                <div className="panel-icon">
                    <CalendarClock size={18} />
                </div>

                <div>
                    <span className="panel-label">
                        PERT / CPM
                    </span>

                    <h3>
                        Project Plan
                    </h3>
                </div>

                {
                    tasks.length > 0 &&
                    (
                        <div className="plan-heading-actions">
                            <button
                                type="button"
                                className="plan-button plan-button-secondary"
                                onClick={handleResetPlan}
                                disabled={busy}
                            >
                                <RotateCcw size={13} />
                                Reset plan
                            </button>
                        </div>
                    )
                }

            </div>


            <div className="plan-body">
                {body}
            </div>

        </article>
    )
}


export default ProjectPlan
