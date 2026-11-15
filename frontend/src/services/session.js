// The login session: who is logged in, and the token that proves it.
// The token is kept in the browser's local storage, so a reload keeps you
// logged in until the token expires or you log out.

const API_ROOT = 'http://localhost:8080/api'

const TOKEN_KEY = 'meetmind.token'

const USER_KEY = 'meetmind.user'

// App.jsx listens for this and shows the login screen again.
export const LOGGED_OUT_EVENT = 'meetmind:logged-out'


function read(key)
{
    try
    {
        return window.localStorage.getItem(key)
    }
    catch
    {
        return null
    }
}


function write(key, value)
{
    try
    {
        if (value === null)
        {
            window.localStorage.removeItem(key)
        }
        else
        {
            window.localStorage.setItem(key, value)
        }
    }
    catch
    {
        // storage blocked (private window): you stay logged in until reload
    }
}


export function getToken()
{
    return read(TOKEN_KEY)
}


export function getSavedUser()
{
    try
    {
        return getToken() ? JSON.parse(read(USER_KEY)) : null
    }
    catch
    {
        return null
    }
}


export function saveSession(token, user)
{
    write(TOKEN_KEY, token)

    write(USER_KEY, JSON.stringify(user))
}


export function clearSession()
{
    write(TOKEN_KEY, null)

    write(USER_KEY, null)
}


// fetch() that adds the login token. If the backend says the token is not
// good any more (expired, or the account is gone), the session is cleared and
// the app goes back to the login screen.
export async function apiFetch(path, options = {})
{
    const token = getToken()

    const headers = { ...(options.headers || {}) }

    if (token)
    {
        headers.Authorization = `Bearer ${token}`
    }

    const response = await fetch(`${API_ROOT}${path}`, { ...options, headers })

    if (response.status === 401 && token)
    {
        clearSession()

        window.dispatchEvent(new Event(LOGGED_OUT_EVENT))
    }

    return response
}
