import { useEffect, useId, useState } from 'react'

import { Target } from 'lucide-react'

import { getPlan } from '../services/planApi'

import {
    formatChance,
    formatDays
} from '../utils/planUtils'


// Waiting this long after the last keystroke before asking the backend.
const TYPING_PAUSE_MS = 400


function describeChance(probability)
{
    if (probability >= 0.8)
    {
        return { word: 'Comfortable', level: 'is-good' }
    }

    if (probability >= 0.5)
    {
        return { word: 'Risky', level: 'is-warn' }
    }

    return { word: 'Unlikely', level: 'is-bad' }
}


// "How likely is it that the project is done within N days?"
// The backend does the maths; this component only asks and shows the answer.
function PlanDeadline({ meetingId, plan })
{
    const inputId = useId()

    const [input, setInput] = useState('')

    const [probability, setProbability] = useState(null)


    const typed = input.trim() === '' ? NaN : Number(input)

    const target = Number.isFinite(typed) && typed >= 0 ? typed : null


    // Asks again whenever the target changes, and also whenever the plan
    // changes, so the answer always matches the estimates on screen.
    useEffect(() =>
    {
        if (target === null)
        {
            setProbability(null)

            return undefined
        }

        let ignore = false

        const timer = setTimeout(async () =>
        {
            try
            {
                const answer = await getPlan(meetingId, target)

                if (!ignore)
                {
                    setProbability(
                        answer.schedule
                            ? answer.schedule.probabilityOfFinishingByTarget
                            : null
                    )
                }
            }
            catch
            {
                if (!ignore)
                {
                    setProbability(null)
                }
            }
        }, TYPING_PAUSE_MS)

        return () =>
        {
            ignore = true

            clearTimeout(timer)
        }
    }, [meetingId, plan, target])


    const schedule = plan.schedule

    const hasNoSpread = schedule.projectStandardDeviation === 0

    const verdict = probability === null ? null : describeChance(probability)


    return (
        <div className="plan-card">

            <div className="plan-card-title">
                <Target size={14} />
                Deadline check
            </div>

            <p className="plan-card-text">
                How likely is it that the whole project is done by a day you
                choose? The expected finish is day {formatDays(schedule.projectDurationDays)}.
            </p>

            <div className="plan-inline-form">

                <div className="plan-field">

                    <label htmlFor={inputId}>
                        Finish within (days)
                    </label>

                    <input
                        id={inputId}
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="0.5"
                        value={input}
                        placeholder={`e.g. ${Math.ceil(schedule.projectDurationDays + 1)}`}
                        onChange={(event) => setInput(event.target.value)}
                    />

                </div>

            </div>


            {
                verdict &&
                (
                    <div
                        className={`plan-chance ${verdict.level}`}
                        role="status"
                    >

                        <div className="plan-chance-head">

                            <span className="plan-chance-value">
                                {formatChance(probability, hasNoSpread)}
                            </span>

                            <span className="plan-chance-word">
                                {verdict.word}
                            </span>

                        </div>

                        <div
                            className="plan-meter"
                            aria-hidden="true"
                        >
                            <div
                                className="plan-meter-fill"
                                style={{ width: `${probability * 100}%` }}
                            />
                        </div>

                        <p className="plan-card-text">
                            With the current estimates, there is about a{' '}
                            {formatChance(probability, hasNoSpread)} chance of
                            finishing within {formatDays(target)} days.
                            {
                                hasNoSpread
                                    ? ' All estimates are single values, so the answer is certain.'
                                    : ' This follows from how far apart your optimistic and pessimistic estimates are on the critical path.'
                            }
                        </p>

                    </div>
                )
            }

        </div>
    )
}


export default PlanDeadline
