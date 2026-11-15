import { useState } from 'react'

import { Search, Trash2, X } from 'lucide-react'

import { filterMeetings } from '../utils/searchUtils'


function MeetingHistory({ meetings, selectedMeeting, onSelectMeeting, onDeleteMeeting, loading })
{
    const [query, setQuery] = useState('')

    const shownMeetings = filterMeetings(meetings, query)

    const isSearching = query.trim() !== ''


    const formatFileSize = (bytes) =>
    {
        if (!bytes)
        {
            return '0 Bytes'
        }

        const units = ['Bytes', 'KB', 'MB', 'GB']

        const unitIndex =
            Math.floor(
                Math.log(bytes) /
                Math.log(1024)
            )

        const fileSize =
            bytes /
            Math.pow(1024, unitIndex)

        return `${fileSize.toFixed(1)} ${units[unitIndex]}`
    }


    const getStatusClass = (status) =>
    {
        if (!status)
        {
            return 'status-default'
        }

        return `status-${status.toLowerCase()}`
    }


    const getStatusText = (status) =>
    {
        if (!status)
        {
            return 'Unknown'
        }

        return status
            .replaceAll('_', ' ')
            .toLowerCase()
            .replace(/\b\w/g, (letter) => letter.toUpperCase())
    }


    return (
        <section
            className="history-card"
            id="meeting-history"
        >
            <div className="section-heading">

                <div>
                    <span className="section-label">
                        MEETING LIBRARY
                    </span>

                    <h2>Recent meetings</h2>

                    <p>
                        Access your processed recordings,
                        transcripts and AI-generated summaries.
                    </p>
                </div>

                <div className="meeting-count">
                    {
                        isSearching
                            ? `${shownMeetings.length} of ${meetings.length}`
                            : meetings.length
                    }
                    {' '}
                    {meetings.length === 1 ? 'meeting' : 'meetings'}
                </div>

            </div>


            {
                meetings.length > 0 && (
                    <div className="history-search">

                        <Search size={16} />

                        <input
                            type="text"
                            value={query}
                            placeholder="Search by title, keyword, name or anything said..."
                            aria-label="Search meetings"
                            onChange={
                                (event) =>
                                    setQuery(event.target.value)
                            }
                        />

                        {
                            query && (
                                <button
                                    type="button"
                                    className="history-search-clear"
                                    aria-label="Clear search"
                                    onClick={() => setQuery('')}
                                >
                                    <X size={14} />
                                </button>
                            )
                        }

                    </div>
                )
            }


            {
                loading && meetings.length === 0
                    ?
                    (
                        <div className="history-message">
                            <div className="button-spinner"></div>

                            <p>
                                Loading your meetings...
                            </p>
                        </div>
                    )
                    :
                    meetings.length === 0
                        ?
                        (
                            <div className="empty-history">

                                <div className="empty-history-icon">
                                    ◫
                                </div>

                                <h3>
                                    No meetings yet
                                </h3>

                                <p>
                                    Upload your first recording to generate
                                    a transcript and AI summary.
                                </p>

                            </div>
                        )
                        :
                        shownMeetings.length === 0
                            ?
                            (
                                <div className="history-message">
                                    <p>
                                        No meetings match “{query.trim()}”.
                                    </p>
                                </div>
                            )
                            :
                        (
                            <div className="meeting-list">

                                {
                                    shownMeetings.map((meeting) =>
                                    {
                                        const isSelected =
                                            selectedMeeting?.id === meeting.id

                                        return (
                                            <div
                                                key={meeting.id}
                                                className="meeting-list-row"
                                            >
                                            <button
                                                type="button"
                                                className={
                                                    isSelected
                                                        ? 'meeting-list-item selected'
                                                        : 'meeting-list-item'
                                                }
                                                onClick={
                                                    () =>
                                                        onSelectMeeting(meeting)
                                                }
                                            >
                                                <div className="meeting-file-icon">
                                                    ♪
                                                </div>


                                                <div className="meeting-list-content">

                                                    <div className="meeting-list-header">

                                                        <h3>
                                                            {
                                                                meeting.title ||
                                                                'Untitled Meeting'
                                                            }
                                                        </h3>

                                                        <span
                                                            className={
                                                                `meeting-status ${getStatusClass(meeting.status)}`
                                                            }
                                                        >
                                                            {getStatusText(meeting.status)}
                                                        </span>

                                                    </div>


                                                    <p className="meeting-file-name">
                                                        {
                                                            meeting.originalFileName ||
                                                            'Meeting recording'
                                                        }
                                                    </p>


                                                    <div className="meeting-metadata">

                                                        <span>
                                                            {formatFileSize(meeting.fileSize)}
                                                        </span>

                                                        <span className="metadata-divider">
                                                            •
                                                        </span>

                                                        <span>
                                                            {
                                                                meeting.fileType ||
                                                                'Unknown file type'
                                                            }
                                                        </span>

                                                    </div>

                                                </div>


                                                <div className="meeting-arrow">
                                                    →
                                                </div>

                                            </button>

                                            <button
                                                type="button"
                                                className="meeting-row-delete"
                                                aria-label={`Delete ${meeting.title || 'Untitled Meeting'}`}
                                                title="Delete this meeting"
                                                onClick={
                                                    () =>
                                                        onDeleteMeeting(meeting)
                                                }
                                            >
                                                <Trash2 size={15} />
                                            </button>

                                            </div>
                                        )
                                    })
                                }

                            </div>
                        )
            }

        </section>
    )
}

export default MeetingHistory