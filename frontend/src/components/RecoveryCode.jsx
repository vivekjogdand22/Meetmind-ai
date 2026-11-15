import { useState } from 'react'

import { Check, Copy, Download } from 'lucide-react'

import './AuthScreen.css'


// The recovery code, big, with Copy and Download buttons.
export function RecoveryCodeBox({ code, email })
{
    const [copied, setCopied] = useState(false)


    async function copy()
    {
        try
        {
            await navigator.clipboard.writeText(code)

            setCopied(true)

            setTimeout(() => setCopied(false), 2000)
        }
        catch
        {
            // clipboard blocked: the code is on screen and can be selected
        }
    }


    function download()
    {
        const text =
            'MeetMind AI recovery code\n\n' +
            (email ? `Account: ${email}\n` : '') +
            `Code: ${code}\n\n` +
            'Keep this file somewhere safe. If you forget your password, this code is the only way back in.\n'

        const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))

        const link = document.createElement('a')

        link.href = url
        link.download = 'meetmind-recovery-code.txt'

        document.body.appendChild(link)

        link.click()

        link.remove()

        setTimeout(() => URL.revokeObjectURL(url), 1000)
    }


    return (
        <div className="recovery-box">

            <code className="recovery-code" data-testid="recovery-code">
                {code}
            </code>

            <div className="recovery-actions">

                <button type="button" onClick={copy}>
                    {copied ? <Check size={14} /> : <Copy size={14} />}
                    {copied ? 'Copied' : 'Copy'}
                </button>

                <button type="button" onClick={download}>
                    <Download size={14} />
                    Download
                </button>

            </div>

        </div>
    )
}


// Shown once, right after an account is created or a password is reset.
function RecoveryCodeScreen({ code, email, onContinue })
{
    const [saved, setSaved] = useState(false)

    return (
        <main className="auth-page">

            <section className="auth-card">

                <h2 className="recovery-title">
                    Save your recovery code
                </h2>

                <p className="recovery-text">
                    If you ever forget your password, this code is the
                    <strong> only way back into your account</strong>. It is
                    shown just this once, so keep it somewhere safe.
                </p>

                <RecoveryCodeBox code={code} email={email} />

                <label className="recovery-confirm">
                    <input
                        type="checkbox"
                        checked={saved}
                        onChange={(event) => setSaved(event.target.checked)}
                    />
                    <span>I have saved this code in a safe place</span>
                </label>

                <button
                    type="button"
                    className="auth-submit"
                    disabled={!saved}
                    onClick={onContinue}
                >
                    Continue
                </button>

            </section>

        </main>
    )
}


export default RecoveryCodeScreen
