import { apiFetch, saveSession } from './session'


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


async function authenticate(path, body, fallback)
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

    const { token, user } = await response.json()

    saveSession(token, user)

    return user
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


// Asks the backend who the saved token belongs to. Throws if the token is no
// longer good.
export async function getMe()
{
    const response = await apiFetch('/auth/me')

    if (!response.ok)
    {
        throw new Error(await readMessage(response, 'Please log in.'))
    }

    return response.json()
}
