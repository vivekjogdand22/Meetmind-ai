import { apiFetch, saveSession, saveUser } from './session'


async function readMessage(response, fallback)
{
    try
    {
        const errorData = await response.json()

        return errorData.message || errorData.error || fallback
    }
    catch
    {
        return fallback
    }
}


async function post(path, body, fallback)
{
    const response = await apiFetch(
        path,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }
    )

    if (!response.ok)
    {
        throw new Error(await readMessage(response, fallback))
    }

    return response.json()
}


// Logs in with whatever the backend answered. "recoveryCode" is only there
// when a new one was just made: it is shown once and never again.
async function authenticate(path, body, fallback)
{
    const { token, user, recoveryCode } = await post(path, body, fallback)

    saveSession(token, user)

    return { user, recoveryCode: recoveryCode || null }
}


export function register(name, email, password)
{
    return authenticate(
        '/auth/register',
        { name, email, password },
        'The account could not be created'
    )
}


export function login(email, password)
{
    return authenticate(
        '/auth/login',
        { email, password },
        'Login failed'
    )
}


// Forgot the password: the recovery code sets a new one and logs in.
export function resetPassword(email, recoveryCode, newPassword)
{
    return authenticate(
        '/auth/reset-password',
        { email, recoveryCode, newPassword },
        'The password could not be reset'
    )
}


// Every other login of this account ends; this browser gets a new token.
export function changePassword(currentPassword, newPassword)
{
    return authenticate(
        '/auth/change-password',
        { currentPassword, newPassword },
        'The password could not be changed'
    )
}


// A new one-time recovery code (the old one stops working).
export async function createRecoveryCode(password)
{
    const { recoveryCode } = await post(
        '/auth/recovery-code',
        { password },
        'The recovery code could not be created'
    )

    return recoveryCode
}


// Asks the backend who the saved token belongs to. Throws if the token is no
// longer good.
export async function getMe()
{
    const response = await apiFetch('/auth/me')

    if (!response.ok)
    {
        throw new Error(await readMessage(response, 'Please log in.'))
    }

    const user = await response.json()

    saveUser(user)

    return user
}
