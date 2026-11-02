import {
    CalendarDays,
    Link2,
    Pencil,
    Trash2,
    UserRound
} from 'lucide-react'

import { formatDays } from '../utils/planUtils'


function Stat({ label, value, strong })
{
    return (
        <div className={strong ? 'plan-stat plan-stat-strong' : 'plan-stat'}>

            <dt>
                {label}
            </dt>

            <dd>
                {value}
            </dd>

        </div>
    )
}


function PlanTaskCard({ task, number, namesById, scheduled, busy, onEdit, onDelete })
{
    const hasEstimates = task.optimisticDays !== null

    const predecessorNames =
        task.predecessorIds
            .map((id) => namesById[id])
            .filter(Boolean)


    const classNames = ['plan-task']

    if (scheduled && task.critical)
    {
        classNames.push('is-critical')
    }

    if (!hasEstimates)
    {
        classNames.push('is-draft')
    }


    return (
        <li className={classNames.join(' ')}>

            <div className="plan-task-head">

                <span className="plan-task-number">
                    {number}
                </span>

                <div className="plan-task-title">

                    <strong>
                        {task.name}
                    </strong>

                    <div className="plan-task-meta">

                        <span>
                            <UserRound size={12} />
                            {task.owner || 'Unassigned'}
                        </span>

                        {
                            task.sourceDueDate &&
                            (
                                <span title="Due date the AI heard in the meeting. It is not used in the schedule.">
                                    <CalendarDays size={12} />
                                    Said in meeting: {task.sourceDueDate}
                                </span>
                            )
                        }

                    </div>

                </div>


                {
                    scheduled && task.critical &&
                    (
                        <span className="plan-badge plan-badge-critical">
                            CRITICAL
                        </span>
                    )
                }

                {
                    scheduled && !task.critical &&
                    (
                        <span className="plan-badge plan-badge-slack">
                            {formatDays(task.slackDays)} DAYS SLACK
                        </span>
                    )
                }

                {
                    !hasEstimates &&
                    (
                        <span className="plan-badge plan-badge-draft">
                            NEEDS ESTIMATES
                        </span>
                    )
                }


                <div className="plan-task-actions">

                    <button
                        type="button"
                        className="plan-icon-button"
                        onClick={onEdit}
                        disabled={busy}
                        aria-label={`Edit ${task.name}`}
                        title="Edit"
                    >
                        <Pencil size={14} />
                    </button>

                    <button
                        type="button"
                        className="plan-icon-button is-danger"
                        onClick={onDelete}
                        disabled={busy}
                        aria-label={`Delete ${task.name}`}
                        title="Delete"
                    >
                        <Trash2 size={14} />
                    </button>

                </div>

            </div>


            {
                hasEstimates
                    ?
                    (
                        <dl className="plan-stats">

                            <Stat label="Optimistic" value={formatDays(task.optimisticDays)} />
                            <Stat label="Most likely" value={formatDays(task.mostLikelyDays)} />
                            <Stat label="Pessimistic" value={formatDays(task.pessimisticDays)} />

                            {
                                scheduled &&
                                (
                                    <>
                                        <Stat label="Expected" value={formatDays(task.expectedDays)} strong />
                                        <Stat label="Starts day" value={formatDays(task.earliestStart)} />
                                        <Stat label="Ends day" value={formatDays(task.earliestFinish)} />
                                        <Stat label="Slack" value={formatDays(task.slackDays)} />
                                    </>
                                )
                            }

                        </dl>
                    )
                    :
                    (
                        <p className="plan-task-hint">
                            Edit this task to enter how long it could take: the
                            best case, the most likely case and the worst case, in days.
                        </p>
                    )
            }


            <p className="plan-task-depends">

                <Link2 size={12} />

                {
                    predecessorNames.length > 0
                        ? `Starts after: ${predecessorNames.join(', ')}`
                        : 'Starts after: nothing, it can begin right away'
                }

            </p>

        </li>
    )
}


export default PlanTaskCard
