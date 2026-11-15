// Turns one meeting into a downloadable report, as a PDF or a Word file.
// Both files hold the same things: the summary, the key decisions, the action
// items and the full transcript. The libraries are loaded only when a button
// is clicked, so the rest of the app does not get heavier.


function parseList(value)
{
    if (!value)
    {
        return []
    }

    if (Array.isArray(value))
    {
        return value
    }

    try
    {
        const parsed = JSON.parse(value)

        return Array.isArray(parsed) ? parsed : []
    }
    catch
    {
        return []
    }
}


// The same things the page shows, in one plain object.
export function reportData(meeting)
{
    return {
        title: meeting.title || 'Untitled Meeting',
        recording: meeting.originalFileName || '',
        summary: meeting.summary || '',
        decisions: parseList(meeting.keyDecisions)
            .map((decision) => String(decision?.decision ?? decision)),
        actionItems: parseList(meeting.actionItems)
            .map((item) => ({
                task: item.task || '',
                owner: item.owner || 'Unassigned',
                dueDate: item.dueDate || '',
                priority: item.priority || 'MEDIUM'
            })),
        transcript: meeting.transcript || '',
        exportedOn: new Date().toLocaleDateString(
            undefined,
            { year: 'numeric', month: 'long', day: 'numeric' }
        )
    }
}


export function fileName(title, extension)
{
    const base =
        title
            .normalize('NFKD')
            .replace(/[^a-zA-Z0-9]+/g, '-')
            .replace(/^-+|-+$/g, '')
            .slice(0, 60)

    return `${base || 'meeting'}-report.${extension}`
}


function download(blob, name)
{
    const url = URL.createObjectURL(blob)

    const link = document.createElement('a')

    link.href = url
    link.download = name

    document.body.appendChild(link)

    link.click()

    link.remove()

    setTimeout(() => URL.revokeObjectURL(url), 1000)
}


// ============================================================
// PDF
// ============================================================

// The PDF fonts only know Western European letters. Anything else would
// come out as garbage, so it becomes "?". The Word file keeps every language.
function forPdf(text)
{
    return text
        .normalize('NFC')
        .replace(/[^ -~ -ÿ‘’“”–—…•\n]/g, '?')
}


export async function exportPdf(meeting)
{
    const { jsPDF } = await import('jspdf')

    const report = reportData(meeting)

    const pdf = new jsPDF({ unit: 'pt', format: 'a4' })

    const margin = 56

    const pageWidth = pdf.internal.pageSize.getWidth()

    const pageHeight = pdf.internal.pageSize.getHeight()

    const width = pageWidth - margin * 2

    let y = margin


    const room = (height) =>
    {
        if (y + height > pageHeight - margin)
        {
            pdf.addPage()

            y = margin
        }
    }


    const write = (text, { size = 10.5, bold = false, indent = 0, gap = 6, color = 30 } = {}) =>
    {
        pdf.setFont('helvetica', bold ? 'bold' : 'normal')

        pdf.setFontSize(size)

        pdf.setTextColor(color)

        const lineHeight = size * 1.45

        for (const paragraph of forPdf(text).split('\n'))
        {
            const lines = pdf.splitTextToSize(paragraph || ' ', width - indent)

            for (const line of lines)
            {
                room(lineHeight)

                pdf.text(line, margin + indent, y + size)

                y += lineHeight
            }
        }

        y += gap
    }


    const heading = (text) =>
    {
        room(40)

        y += 10

        write(text, { size: 14, bold: true, gap: 4, color: 49 })
    }


    const bullet = (text) =>
    {
        room(16)

        pdf.setFont('helvetica', 'normal')

        pdf.setFontSize(10.5)

        pdf.setTextColor(30)

        pdf.text('•', margin + 4, y + 10.5)

        write(text, { indent: 18, gap: 3 })
    }


    write(report.title, { size: 20, bold: true, gap: 2 })

    write(
        `MeetMind AI meeting report${report.recording ? ' | ' + report.recording : ''} | ${report.exportedOn}`,
        { size: 9, color: 110, gap: 8 }
    )


    heading('Summary')

    write(report.summary || 'No summary.')


    heading('Key decisions')

    if (report.decisions.length === 0)
    {
        write('No decisions were recorded.', { color: 110 })
    }

    report.decisions.forEach(bullet)


    heading('Action items')

    if (report.actionItems.length === 0)
    {
        write('No action items were found.', { color: 110 })
    }

    report.actionItems.forEach((item) =>
    {
        const details =
            [
                `Owner: ${item.owner}`,
                item.dueDate ? `Due: ${item.dueDate}` : null,
                `Priority: ${item.priority}`
            ]
                .filter(Boolean)
                .join('  |  ')

        bullet(`${item.task}\n${details}`)
    })


    heading('Transcript')

    write(report.transcript || 'No transcript.', { size: 10 })


    const pages = pdf.getNumberOfPages()

    for (let page = 1; page <= pages; page++)
    {
        pdf.setPage(page)

        pdf.setFont('helvetica', 'normal')

        pdf.setFontSize(8.5)

        pdf.setTextColor(140)

        pdf.text(`Page ${page} of ${pages}`, pageWidth / 2, pageHeight - 28, { align: 'center' })
    }


    download(pdf.output('blob'), fileName(report.title, 'pdf'))
}


// ============================================================
// WORD
// ============================================================

export async function exportDocx(meeting)
{
    const {
        Document,
        HeadingLevel,
        Packer,
        Paragraph,
        ShadingType,
        Table,
        TableCell,
        TableRow,
        TextRun,
        WidthType
    } = await import('docx')

    const report = reportData(meeting)


    const paragraph = (text, options = {}) =>
        new Paragraph({
            children: [new TextRun({ text, ...(options.run || {}) })],
            spacing: { after: 120 },
            ...(options.paragraph || {})
        })


    const heading = (text) =>
        new Paragraph({
            text,
            heading: HeadingLevel.HEADING_2,
            spacing: { before: 280, after: 120 }
        })


    const cell = (text, { bold = false, fill } = {}) =>
        new TableCell({
            children: [
                new Paragraph({
                    children: [new TextRun({ text, bold, color: fill ? 'FFFFFF' : undefined })]
                })
            ],
            shading: fill ? { type: ShadingType.CLEAR, color: 'auto', fill } : undefined,
            margins: { top: 60, bottom: 60, left: 100, right: 100 }
        })


    const children = [
        new Paragraph({
            text: report.title,
            heading: HeadingLevel.HEADING_1
        }),

        paragraph(
            `MeetMind AI meeting report${report.recording ? ' | ' + report.recording : ''} | ${report.exportedOn}`,
            { run: { color: '6B7280', size: 18 } }
        ),

        heading('Summary'),

        paragraph(report.summary || 'No summary.'),

        heading('Key decisions')
    ]


    if (report.decisions.length === 0)
    {
        children.push(paragraph('No decisions were recorded.', { run: { color: '6B7280' } }))
    }

    report.decisions.forEach((decision) =>
        children.push(
            new Paragraph({
                children: [new TextRun({ text: decision })],
                bullet: { level: 0 },
                spacing: { after: 60 }
            })
        )
    )


    children.push(heading('Action items'))

    if (report.actionItems.length === 0)
    {
        children.push(paragraph('No action items were found.', { run: { color: '6B7280' } }))
    }
    else
    {
        children.push(
            new Table({
                width: { size: 100, type: WidthType.PERCENTAGE },
                rows: [
                    new TableRow({
                        tableHeader: true,
                        children: ['Task', 'Owner', 'Due', 'Priority']
                            .map((title) => cell(title, { bold: true, fill: '1F3864' }))
                    }),
                    ...report.actionItems.map(
                        (item) =>
                            new TableRow({
                                cantSplit: true,
                                children: [
                                    cell(item.task),
                                    cell(item.owner),
                                    cell(item.dueDate || '-'),
                                    cell(item.priority)
                                ]
                            })
                    )
                ]
            })
        )
    }


    children.push(heading('Transcript'))

    ;(report.transcript || 'No transcript.')
        .split('\n')
        .forEach((line) => children.push(paragraph(line)))


    const document_ = new Document({
        creator: 'MeetMind AI',
        title: report.title,
        sections: [{ children }]
    })


    download(await Packer.toBlob(document_), fileName(report.title, 'docx'))
}
