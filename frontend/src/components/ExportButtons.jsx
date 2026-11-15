import { useState } from 'react'

import { FileDown } from 'lucide-react'

import { exportDocx, exportPdf } from '../utils/exportReport'


// "PDF" and "Word" buttons for one meeting. Each downloads a report with the
// summary, decisions, action items and the full transcript.
function ExportButtons({ meeting })
{
    const [busy, setBusy] = useState('')


    async function run(kind, exporter)
    {
        setBusy(kind)

        try
        {
            await exporter(meeting)
        }
        catch (error)
        {
            console.error(error)

            window.alert(`The ${kind} could not be created. Please try again.`)
        }
        finally
        {
            setBusy('')
        }
    }


    return (
        <>
            <button
                type="button"
                className="export-button"
                disabled={Boolean(busy)}
                title="Download this meeting as a PDF"
                onClick={() => run('PDF', exportPdf)}
            >
                <FileDown size={13} />
                {busy === 'PDF' ? 'Creating...' : 'PDF'}
            </button>

            <button
                type="button"
                className="export-button"
                disabled={Boolean(busy)}
                title="Download this meeting as a Word document"
                onClick={() => run('Word document', exportDocx)}
            >
                <FileDown size={13} />
                {busy === 'Word document' ? 'Creating...' : 'Word'}
            </button>
        </>
    )
}


export default ExportButtons
