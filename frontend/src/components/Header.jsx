import { KeyRound, LogOut } from 'lucide-react'

import './AuthScreen.css'


function Header({ user, onLogout, onOpenAccount })
{
    return (
        <header className="header">

            <div className="header-brand">

                <div className="brand-icon">
                    🧠
                </div>

                <div>

                    <h1>
                        MeetMind AI
                    </h1>

                    <p>
                        AI Meeting Intelligence
                    </p>

                </div>

            </div>

            <div className="header-user">

                <div className="header-badge">
                    AI Powered
                </div>

                {
                    user && (
                        <>
                            <span
                                className="header-user-name"
                                title={user.email}
                            >
                                {user.name}
                            </span>

                            <button
                                type="button"
                                className={user.hasRecoveryCode ? 'logout-button' : 'logout-button needs-attention'}
                                onClick={onOpenAccount}
                                title={user.hasRecoveryCode ? 'Password and recovery code' : 'You have no recovery code: a forgotten password could not be reset'}
                            >
                                <KeyRound size={13} />
                                {user.hasRecoveryCode ? 'Account' : 'Get a recovery code'}
                            </button>

                            <button
                                type="button"
                                className="logout-button"
                                onClick={onLogout}
                            >
                                <LogOut size={13} />
                                Log out
                            </button>
                        </>
                    )
                }

            </div>

        </header>
    )
}

export default Header
