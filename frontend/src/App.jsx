import { useEffect, useState } from 'react'
import './App.css'

import Header from './components/Header'
import UploadMeeting from './components/UploadMeeting'
import MeetingHistory from './components/MeetingHistory'
import MeetingDetails from './components/MeetingDetails'
import AuthScreen from './components/AuthScreen'

import { getMe } from './services/authApi'

import {
    LOGGED_OUT_EVENT,
    clearSession,
    getSavedUser,
    getToken
} from './services/session'

import {
    deleteMeeting,
    getAllMeetings,
    uploadMeeting
} from './services/meetingApi'


function Workspace({ user, onLogout })
{
    const [meetings, setMeetings] = useState([])

    const [selectedMeeting, setSelectedMeeting] =
        useState(null)

    const [loading, setLoading] =
        useState(false)

    const [message, setMessage] =
        useState('')

    const [messageType, setMessageType] =
        useState('info')


    useEffect(() =>
    {
        loadMeetings()
    }, [])


    const loadMeetings = async () =>
    {
        try
        {
            const data =
                await getAllMeetings()


            const sortedMeetings =
                [...data].sort(
                    (firstMeeting, secondMeeting) =>
                        secondMeeting.id - firstMeeting.id
                )


            setMeetings(
                sortedMeetings
            )


            setSelectedMeeting(
                (currentMeeting) =>
                {
                    if (currentMeeting)
                    {
                        return currentMeeting
                    }

                    return sortedMeetings[0] || null
                }
            )
        }
        catch (error)
        {
            console.error(error)

            setMessage(
                'Unable to connect to the MeetMind backend.'
            )

            setMessageType(
                'error'
            )
        }
    }


    const handleUpload = async (
        title,
        file
    ) =>
    {
        if (!title.trim())
        {
            setMessage(
                'Please enter a meeting title.'
            )

            setMessageType(
                'error'
            )

            return false
        }


        if (!file)
        {
            setMessage(
                'Please select an audio or video file.'
            )

            setMessageType(
                'error'
            )

            return false
        }


        try
        {
            setLoading(true)

            setMessage(
                'Transcribing and analyzing your meeting. This may take a few minutes.'
            )

            setMessageType(
                'processing'
            )


            const processedMeeting =
                await uploadMeeting(
                    title.trim(),
                    file
                )


            setSelectedMeeting(
                processedMeeting
            )


            setMessage(
                'Meeting processed successfully. Your AI insights are ready.'
            )

            setMessageType(
                'success'
            )


            await loadMeetings()


            setTimeout(() =>
            {
                document
                    .getElementById(
                        'meeting-details'
                    )
                    ?.scrollIntoView(
                        {
                            behavior: 'smooth',
                            block: 'start'
                        }
                    )
            }, 100)


            return true
        }
        catch (error)
        {
            console.error(error)

            setMessage(
                error.message ||
                'Unable to process the meeting.'
            )

            setMessageType(
                'error'
            )


            return false
        }
        finally
        {
            setLoading(false)
        }
    }


    const handleSelectMeeting =
        (meeting) =>
    {
        setSelectedMeeting(
            meeting
        )


        setTimeout(() =>
        {
            document
                .getElementById(
                    'meeting-details'
                )
                ?.scrollIntoView(
                    {
                        behavior: 'smooth',
                        block: 'start'
                    }
                )
        }, 100)
    }


    const handleDeleteMeeting = async (meeting) =>
    {
        const confirmed = window.confirm(
            `Delete "${meeting.title || 'Untitled Meeting'}"? ` +
            'The recording, the transcript, the AI results and the ' +
            'project plan of this meeting will be removed.'
        )

        if (!confirmed)
        {
            return
        }


        try
        {
            await deleteMeeting(meeting.id)

            const remainingMeetings =
                meetings.filter(
                    (existingMeeting) =>
                        existingMeeting.id !== meeting.id
                )

            setMeetings(
                remainingMeetings
            )

            // keep the open meeting unless it is the one that was deleted
            setSelectedMeeting(
                (current) =>
                    current && current.id !== meeting.id
                        ? current
                        : remainingMeetings[0] || null
            )

            setMessage(
                'The meeting was deleted.'
            )

            setMessageType(
                'success'
            )
        }
        catch (error)
        {
            console.error(error)

            setMessage(
                error.message ||
                'Unable to delete the meeting.'
            )

            setMessageType(
                'error'
            )
        }
    }


    return (
        <div className="app">

            <Header
                user={user}
                onLogout={onLogout}
            />


            <main className="main-container">


                <section className="hero-section">

                    <div className="hero-content">

                        <span className="hero-badge">
                            AI MEETING INTELLIGENCE
                        </span>


                        <h1>
                            Turn every meeting into
                            <span>
                                {' '}
                                clear insights.
                            </span>
                        </h1>


                        <p>
                            Upload your meeting recording and let
                            MeetMind AI generate an accurate transcript,
                            concise summary, key decisions, and
                            actionable follow-up tasks.
                        </p>


                        <div className="hero-features">

                            <span>
                                ✦ AI Summaries
                            </span>

                            <span>
                                ◉ Smart Transcription
                            </span>

                            <span>
                                ✓ Action Items
                            </span>

                        </div>

                    </div>

                </section>


                <UploadMeeting
                    loading={loading}
                    onUpload={handleUpload}
                />


                {
                    message &&
                    (
                        <div
                            className={
                                `message message-${messageType}`
                            }
                        >
                            {
                                loading &&
                                (
                                    <span className="button-spinner"></span>
                                )
                            }

                            <span>
                                {message}
                            </span>
                        </div>
                    )
                }


                <div className="workspace-grid">


                    <MeetingHistory
                        meetings={meetings}
                        selectedMeeting={
                            selectedMeeting
                        }
                        onSelectMeeting={
                            handleSelectMeeting
                        }
                        onDeleteMeeting={
                            handleDeleteMeeting
                        }
                        loading={loading}
                    />


                    <div id="meeting-details">

                        <MeetingDetails
                            meeting={
                                selectedMeeting
                            }
                            onDelete={
                                handleDeleteMeeting
                            }
                        />

                    </div>


                </div>


            </main>


            <footer className="footer">

    <div className="footer-content">

        <div>

            <strong>
                MeetMind AI
            </strong>

            <p>
                Intelligent meeting transcription,
                summarization, decisions,
                and action-item extraction.
            </p>

        </div>

       <div className="footer-credit">

    <span>
        Designed & Developed by
    </span>

    <strong>
        Vivek Jogdand
    </strong>

    <span className="footer-divider">
        •
    </span>

    <span>
        Faster-Whisper • Qwen3 • Spring Boot • React
    </span>

</div>

    </div>

</footer>

        </div>
    )
}


// Decides what to show: the login screen, or the workspace of the logged-in
// user. Everything a user sees belongs to their account.
function App()
{
    const [user, setUser] = useState(getSavedUser)

    // a saved login is checked with the backend before it is trusted
    const [checking, setChecking] = useState(() => Boolean(getToken()))

    const [notice, setNotice] = useState('')


    useEffect(() =>
    {
        if (!getToken())
        {
            return
        }

        getMe()
            .then(setUser)
            .catch((error) =>
            {
                setUser(null)

                // a backend that cannot be reached is not a bad login
                if (error instanceof TypeError)
                {
                    setNotice('Unable to reach the MeetMind backend. Is it running on port 8080?')
                }
            })
            .finally(() => setChecking(false))
    }, [])


    useEffect(() =>
    {
        const handleLoggedOut = () =>
        {
            setUser(null)

            setNotice('Your session has ended. Please log in again.')
        }

        window.addEventListener(LOGGED_OUT_EVENT, handleLoggedOut)

        return () => window.removeEventListener(LOGGED_OUT_EVENT, handleLoggedOut)
    }, [])


    const handleLogout = () =>
    {
        clearSession()

        setNotice('')

        setUser(null)
    }


    if (checking)
    {
        return (
            <main className="auth-page">
                <span className="button-spinner" style={{ borderTopColor: '#4f46e5', borderColor: '#c7d2fe' }} />
            </main>
        )
    }


    if (!user)
    {
        return (
            <AuthScreen
                notice={notice}
                onAuthenticated={(loggedInUser) =>
                {
                    setNotice('')

                    setUser(loggedInUser)
                }}
            />
        )
    }


    // "key" gives every account a fresh workspace, so nothing of the
    // previous user stays on screen after a log out and a new login
    return (
        <Workspace
            key={user.id}
            user={user}
            onLogout={handleLogout}
        />
    )
}


export default App