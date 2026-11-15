import { useRef, useState } from 'react'

import {
    AlertTriangle,
    Info,
    MessageSquareText,
    Quote,
    SendHorizontal
} from 'lucide-react'

import './AskMeeting.css'

import { askMeeting } from '../services/askApi'

import { describeError } from '../utils/planUtils'


const MAX_QUESTION_LENGTH = 500

const EXAMPLE_QUESTIONS = [
    'What decisions were made?',
    'Who is responsible for what?',
    'What deadlines were mentioned?',
    'What was agreed about the budget?'
]


// Splits a passage into the words before the quote, the quote and the words
// after it, so the quote can be highlighted. The quote is the transcript's
// own text; if it cannot be found the passage is shown without highlight.
function splitAtQuote(text, quote)
{
    const start = text.toLowerCase().indexOf(quote.toLowerCase())

    if (start < 0)
    {
        return { before: text, quote: '', after: '' }
    }

    return {
        before: text.slice(0, start),
        quote: text.slice(start, start + quote.length),
        after: text.slice(start + quote.length)
    }
}


function AskMeeting({ meetingId, hasTranscript })
{
    const [question, setQuestion] = useState('')

    const [loading, setLoading] = useState(false)

    const [result, setResult] = useState(null)

    const [error, setError] = useState('')

    // A newer question makes an older, slower answer irrelevant.
    const latestRequest = useRef(0)


    async function ask(text)
    {
        const trimmed = text.trim()

        if (!trimmed || loading)
        {
            return
        }

        const requestNumber = latestRequest.current + 1

        latestRequest.current = requestNumber

        setLoading(true)
        setError('')

        try
        {
            const answer = await askMeeting(meetingId, trimmed)

            if (latestRequest.current === requestNumber)
            {
                setResult({ ...answer, asked: trimmed })
            }
        }
        catch (requestError)
        {
            if (latestRequest.current === requestNumber)
            {
                setResult(null)
                setError(describeError(requestError))
            }
        }
        finally
        {
            if (latestRequest.current === requestNumber)
            {
                setLoading(false)
            }
        }
    }


    function handleSubmit(event)
    {
        event.preventDefault()

        ask(question)
    }


    function handleExample(example)
    {
        setQuestion(example)

        ask(example)
    }


    return (
        <article className="insight-panel ask-panel">

            <div className="panel-heading">

                <div className="panel-icon">
                    <MessageSquareText size={18} />
                </div>

                <div>
                    <span className="panel-label">
                        ONLY FROM THIS TRANSCRIPT
                    </span>

                    <h3>
                        Ask Your Meeting
                    </h3>
                </div>

            </div>


            <div className="ask-body">

                {
                    !hasTranscript
                        ? (
                            <p className="ask-hint">
                                This meeting has no transcript to ask about.
                            </p>
                        )
                        : (
                            <>

                                <form
                                    className="ask-form"
                                    onSubmit={handleSubmit}
                                >

                                    <input
                                        type="text"
                                        className="ask-input"
                                        value={question}
                                        maxLength={MAX_QUESTION_LENGTH}
                                        placeholder="Ask something the meeting talked about..."
                                        aria-label="Your question"
                                        onChange={
                                            (event) =>
                                                setQuestion(event.target.value)
                                        }
                                    />

                                    <button
                                        type="submit"
                                        className="ask-submit"
                                        disabled={loading || !question.trim()}
                                    >
                                        {
                                            loading
                                                ? <span className="button-spinner" />
                                                : <SendHorizontal size={16} />
                                        }

                                        <span>
                                            {loading ? 'Reading...' : 'Ask'}
                                        </span>
                                    </button>

                                </form>


                                <div className="ask-examples">

                                    {
                                        EXAMPLE_QUESTIONS.map(
                                            (example) => (
                                                <button
                                                    key={example}
                                                    type="button"
                                                    className="ask-example"
                                                    disabled={loading}
                                                    onClick={
                                                        () =>
                                                            handleExample(example)
                                                    }
                                                >
                                                    {example}
                                                </button>
                                            )
                                        )
                                    }

                                </div>

                            </>
                        )
                }


                {
                    loading && (
                        <p className="ask-hint" role="status">
                            Searching the transcript and checking the answer.
                            The first question about a meeting can take up to a
                            minute.
                        </p>
                    )
                }


                {
                    error && (
                        <div className="ask-message is-error" role="alert">
                            <AlertTriangle size={16} />
                            <span>{error}</span>
                        </div>
                    )
                }


                {
                    result && !loading && (
                        <div className="ask-result">

                            <p className="ask-asked">
                                {result.asked}
                            </p>

                            {
                                result.answerable
                                    ? (
                                        <p className="ask-answer">
                                            {result.answer}
                                        </p>
                                    )
                                    : (
                                        <div
                                            className={
                                                result.unsupported
                                                    ? 'ask-message is-warning'
                                                    : 'ask-message is-neutral'
                                            }
                                        >
                                            <Info size={16} />

                                            <div>
                                                <strong>
                                                    {
                                                        result.unsupported
                                                            ? 'Could not confirm an answer'
                                                            : 'Not discussed'
                                                    }
                                                </strong>

                                                <span>
                                                    {result.answer}
                                                </span>
                                            </div>
                                        </div>
                                    )
                            }


                            {
                                result.broad && (
                                    <p className="ask-note">
                                        This question asks for a lot. The
                                        answer uses only the closest passages
                                        of the transcript. For the whole
                                        meeting, see the summary and the
                                        action items above.
                                    </p>
                                )
                            }


                            {
                                result.sources.length > 0 && (
                                    <div className="ask-sources">

                                        <span className="ask-sources-title">
                                            Evidence from the transcript
                                        </span>

                                        {
                                            result.sources.map(
                                                (source) => {
                                                    const parts =
                                                        splitAtQuote(
                                                            source.text,
                                                            source.quote
                                                        )

                                                    return (
                                                        <blockquote
                                                            key={`${source.passage}-${source.quote}`}
                                                            className="ask-source"
                                                        >
                                                            <Quote size={14} />

                                                            <p>
                                                                {parts.before}
                                                                {
                                                                    parts.quote && (
                                                                        <mark>
                                                                            {parts.quote}
                                                                        </mark>
                                                                    )
                                                                }
                                                                {parts.after}
                                                            </p>
                                                        </blockquote>
                                                    )
                                                }
                                            )
                                        }

                                    </div>
                                )
                            }


                            <p className="ask-footnote">
                                Answered by {result.model} in {Math.round(result.seconds)} s
                                using only this meeting's transcript.
                            </p>

                        </div>
                    )
                }

            </div>

        </article>
    )
}


export default AskMeeting
