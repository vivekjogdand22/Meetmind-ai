import { useEffect, useState } from 'react'

import { X } from 'lucide-react'

import './AuthScreen.css'

import { RecoveryCodeBox } from './RecoveryCode'

import { changePassword, createRecoveryCode } from '../services/authApi'

import { saveUser } from '../services/session'

import { describeError } from '../utils/planUtils'


const MIN_PASSWORD_LENGTH = 8


// Change the password, or make a new recovery code. Both ask for the current
// password again.
function AccountPanel({ user, onClose, onUserChange })
{
    const [currentPassword, setCurrentPassword] = useState('')

    const [newPassword, setNewPassword] = useState('')

    const [changeMessage, setChangeMessage] = useState({ text: '', error: false })

    const [codePassword, setCodePassword] = useState('')

    const [codeMessage, setCodeMessage] = useState('')

    const [newCode, setNewCode] = useState('')

    const [busy, setBusy] = useState('')


    useEffect(() =>
    {
        const closeOnEscape = (event) =>
        {
            if (event.key === 'Escape')
            {
                onClose()
            }
        }

        window.addEventListener('keydown', closeOnEscape)

        return () => window.removeEventListener('keydown', closeOnEscape)
    }, [onClose])


    async function handleChangePassword(event)
    {
        event.preventDefault()

        if (newPassword.length < MIN_PASSWORD_LENGTH)
        {
            setChangeMessage({ text: `The new password must have at least ${MIN_PASSWORD_LENGTH} characters.`, error: true })

            return
        }

        setBusy('password')

        setChangeMessage({ text: '', error: false })

        try
        {
            const { user: updated } = await changePassword(currentPassword, newPassword)

            onUserChange(updated)

            setCurrentPassword('')

            setNewPassword('')

            setChangeMessage({ text: 'Password changed. Every other login of this account was ended.', error: false })
        }
        catch (error)
        {
            setChangeMessage({ text: describeError(error), error: true })
        }
        finally
        {
            setBusy('')
        }
    }


    async function handleNewCode(event)
    {
        event.preventDefault()

        setBusy('code')

        setCodeMessage('')

        try
        {
            setNewCode(await createRecoveryCode(codePassword))

            const updated = { ...user, hasRecoveryCode: true }

            saveUser(updated)

            onUserChange(updated)

            setCodePassword('')
        }
        catch (error)
        {
            setNewCode('')

            setCodeMessage(describeError(error))
        }
        finally
        {
            setBusy('')
        }
    }


    return (
        <div
            className="modal-backdrop"
            onMouseDown={(event) => event.target === event.currentTarget && onClose()}
        >

            <section
                className="auth-card account-panel"
                role="dialog"
                aria-modal="true"
                aria-label="Your account"
            >

                <div className="account-header">

                    <div>
                        <h2>Your account</h2>

                        <p>{user.name} · {user.email}</p>
                    </div>

                    <button
                        type="button"
                        className="account-close"
                        aria-label="Close"
                        onClick={onClose}
                    >
                        <X size={16} />
                    </button>

                </div>


                <form className="auth-form" onSubmit={handleChangePassword}>

                    <h3>Change password</h3>

                    <label>
                        <span>Current password</span>

                        <input
                            type="password"
                            required
                            autoComplete="current-password"
                            value={currentPassword}
                            onChange={(event) => setCurrentPassword(event.target.value)}
                        />
                    </label>

                    <label>
                        <span>New password</span>

                        <input
                            type="password"
                            required
                            autoComplete="new-password"
                            placeholder={`At least ${MIN_PASSWORD_LENGTH} characters`}
                            value={newPassword}
                            onChange={(event) => setNewPassword(event.target.value)}
                        />
                    </label>

                    {
                        changeMessage.text && (
                            <p
                                className={changeMessage.error ? 'auth-error' : 'auth-success'}
                                role={changeMessage.error ? 'alert' : 'status'}
                            >
                                {changeMessage.text}
                            </p>
                        )
                    }

                    <button type="submit" className="auth-submit" disabled={busy !== ''}>
                        {busy === 'password' && <span className="button-spinner" />}
                        Change password
                    </button>

                </form>


                <form className="auth-form account-section" onSubmit={handleNewCode}>

                    <h3>Recovery code</h3>

                    <p className="recovery-text">
                        {
                            user.hasRecoveryCode
                                ? 'You have a recovery code. A new one replaces it, and the old one stops working.'
                                : 'You have no recovery code yet. Without one, a forgotten password cannot be reset.'
                        }
                    </p>

                    {
                        newCode
                            ? (
                                <>
                                    <RecoveryCodeBox code={newCode} email={user.email} />

                                    <p className="auth-success" role="status">
                                        Save this code now. It is not shown again.
                                    </p>
                                </>
                            )
                            : (
                                <>
                                    <label>
                                        <span>Your password</span>

                                        <input
                                            type="password"
                                            required
                                            autoComplete="current-password"
                                            value={codePassword}
                                            onChange={(event) => setCodePassword(event.target.value)}
                                        />
                                    </label>

                                    {codeMessage && <p className="auth-error" role="alert">{codeMessage}</p>}

                                    <button type="submit" className="auth-submit" disabled={busy !== ''}>
                                        {busy === 'code' && <span className="button-spinner" />}
                                        {user.hasRecoveryCode ? 'Create a new recovery code' : 'Create a recovery code'}
                                    </button>
                                </>
                            )
                    }

                </form>

            </section>

        </div>
    )
}


export default AccountPanel
