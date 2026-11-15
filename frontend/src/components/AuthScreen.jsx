import { useState } from 'react'

import { LogIn, UserPlus } from 'lucide-react'

import './AuthScreen.css'

import { login, register } from '../services/authApi'

import { describeError } from '../utils/planUtils'


const MIN_PASSWORD_LENGTH = 8


// Log in or create an account. After a successful login the parent shows the
// workspace; every meeting belongs to the account that created it.
function AuthScreen({ notice, onAuthenticated })
{
    const [mode, setMode] = useState('login')

    const [name, setName] = useState('')

    const [email, setEmail] = useState('')

    const [password, setPassword] = useState('')

    const [busy, setBusy] = useState(false)

    const [error, setError] = useState('')

    const creating = mode === 'register'


    function switchMode(newMode)
    {
        setMode(newMode)

        setError('')
    }


    async function handleSubmit(event)
    {
        event.preventDefault()

        if (busy)
        {
            return
        }

        if (creating && password.length < MIN_PASSWORD_LENGTH)
        {
            setError(`The password must have at least ${MIN_PASSWORD_LENGTH} characters.`)

            return
        }

        setBusy(true)
        setError('')

        try
        {
            const user = creating
                ? await register(name.trim(), email.trim(), password)
                : await login(email.trim(), password)

            onAuthenticated(user)
        }
        catch (requestError)
        {
            setError(describeError(requestError))

            setBusy(false)
        }
    }


    return (
        <main className="auth-page">

            <section className="auth-card">

                <div className="auth-brand">

                    <div className="brand-icon">
                        🧠
                    </div>

                    <div>
                        <h1>MeetMind AI</h1>

                        <p>AI Meeting Intelligence</p>
                    </div>

                </div>


                <div className="auth-tabs" role="tablist">

                    <button
                        type="button"
                        role="tab"
                        aria-selected={!creating}
                        className={!creating ? 'is-active' : ''}
                        onClick={() => switchMode('login')}
                    >
                        <LogIn size={14} />
                        Log in
                    </button>

                    <button
                        type="button"
                        role="tab"
                        aria-selected={creating}
                        className={creating ? 'is-active' : ''}
                        onClick={() => switchMode('register')}
                    >
                        <UserPlus size={14} />
                        Create account
                    </button>

                </div>


                {
                    notice && !error && (
                        <p className="auth-notice" role="status">
                            {notice}
                        </p>
                    )
                }


                <form className="auth-form" onSubmit={handleSubmit}>

                    {
                        creating && (
                            <label>
                                <span>Name</span>

                                <input
                                    type="text"
                                    value={name}
                                    maxLength={60}
                                    autoComplete="name"
                                    placeholder="Your name"
                                    onChange={(event) => setName(event.target.value)}
                                />
                            </label>
                        )
                    }

                    <label>
                        <span>Email</span>

                        <input
                            type="email"
                            required
                            value={email}
                            autoComplete="email"
                            placeholder="you@example.com"
                            onChange={(event) => setEmail(event.target.value)}
                        />
                    </label>

                    <label>
                        <span>Password</span>

                        <input
                            type="password"
                            required
                            value={password}
                            autoComplete={creating ? 'new-password' : 'current-password'}
                            placeholder={creating ? `At least ${MIN_PASSWORD_LENGTH} characters` : 'Your password'}
                            onChange={(event) => setPassword(event.target.value)}
                        />
                    </label>

                    {
                        error && (
                            <p className="auth-error" role="alert">
                                {error}
                            </p>
                        )
                    }

                    <button
                        type="submit"
                        className="auth-submit"
                        disabled={busy}
                    >
                        {busy && <span className="button-spinner" />}

                        {creating ? 'Create account' : 'Log in'}
                    </button>

                </form>


                <p className="auth-footnote">
                    Your meetings, plans and answers are private to your account.
                </p>

            </section>

        </main>
    )
}


export default AuthScreen
