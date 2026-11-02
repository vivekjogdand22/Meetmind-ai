import { Fragment } from 'react'

import {
    chooseTickStep,
    formatDays
} from '../utils/planUtils'


// Bars narrower than this share of the axis have no room for their label.
const MIN_WIDTH_FOR_LABEL = 9


function PlanTimeline({ tasks, durationDays })
{
    const total = durationDays > 0 ? durationDays : 1

    const step = chooseTickStep(total)


    const ticks = []

    for (let tick = 0; tick <= total + 0.000001; tick += step)
    {
        ticks.push(Number(tick.toFixed(2)))
    }


    const percent = (days) => (days / total) * 100


    // Tasks that start first are drawn first, like a Gantt chart.
    const rows = [...tasks].sort(
        (first, second) => first.earliestStart - second.earliestStart
    )


    return (
        <div
            className="plan-timeline"
            role="group"
            aria-label="Project timeline"
        >

            <div
                className="plan-timeline-grid"
                style={{ '--tick-width': `${percent(step)}%` }}
            >

                <div className="plan-timeline-corner">
                    Day
                </div>

                <div className="plan-timeline-axis">
                    {
                        ticks.map(
                            (tick) =>
                            (
                                <span
                                    key={tick}
                                    className="plan-timeline-tick"
                                    style={{ left: `${percent(tick)}%` }}
                                >
                                    {tick}
                                </span>
                            )
                        )
                    }
                </div>


                {
                    rows.map(
                        (task) =>
                        {
                            const barWidth =
                                percent(task.earliestFinish - task.earliestStart)

                            const description =
                                `${task.name}: day ${formatDays(task.earliestStart)} ` +
                                `to day ${formatDays(task.earliestFinish)}` +
                                (
                                    task.critical
                                        ? ' (critical)'
                                        : `, can slip ${formatDays(task.slackDays)} days`
                                )

                            return (
                                <Fragment key={task.id}>

                                    <div
                                        className="plan-timeline-label"
                                        title={task.name}
                                    >
                                        {task.name}
                                    </div>

                                    <div className="plan-timeline-track">

                                        {
                                            task.slackDays > 0 &&
                                            (
                                                <div
                                                    className="plan-timeline-slack"
                                                    style={{
                                                        left: `${percent(task.earliestFinish)}%`,
                                                        width: `${percent(task.slackDays)}%`
                                                    }}
                                                />
                                            )
                                        }

                                        <div
                                            className={
                                                task.critical
                                                    ? 'plan-timeline-bar is-critical'
                                                    : 'plan-timeline-bar'
                                            }
                                            style={{
                                                left: `${percent(task.earliestStart)}%`,
                                                width: `${barWidth}%`
                                            }}
                                            title={description}
                                        >
                                            {
                                                barWidth >= MIN_WIDTH_FOR_LABEL &&
                                                formatDays(task.expectedDays)
                                            }
                                        </div>

                                    </div>

                                </Fragment>
                            )
                        }
                    )
                }

            </div>


            <div className="plan-timeline-legend">

                <span>
                    <i className="plan-swatch plan-swatch-critical"></i>
                    Critical: any delay moves the finish
                </span>

                <span>
                    <i className="plan-swatch plan-swatch-normal"></i>
                    Has slack
                </span>

                <span>
                    <i className="plan-swatch plan-swatch-slack"></i>
                    How far it can slip
                </span>

            </div>

        </div>
    )
}


export default PlanTimeline
