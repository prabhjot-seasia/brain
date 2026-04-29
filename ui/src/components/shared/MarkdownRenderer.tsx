import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import mermaid from 'mermaid'
import type { SxProps, Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Divider from '@mui/material/Divider'

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'sandbox' })

function MermaidDiagram({ chart }: { chart: string }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [svg, setSvg] = useState<string>('')

  useEffect(() => {
    const id = `mermaid-${Math.random().toString(36).slice(2, 9)}`
    mermaid.render(id, chart.trim()).then(({ svg: renderedSvg }) => {
      setSvg(renderedSvg)
    }).catch(() => {
      setSvg('')
    })
  }, [chart])

  if (!svg) {
    return (
      <Box sx={{ my: 1.5 }}>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          Diagram could not be rendered — showing source:
        </Typography>
        <Box component="pre" sx={{
          backgroundColor: 'background.default', p: 2, borderRadius: 1,
          fontFamily: 'monospace', fontSize: '0.8125rem', overflowX: 'auto',
        }}>
          {chart}
        </Box>
      </Box>
    )
  }

  return (
    <Box ref={containerRef} role="img" aria-label="Mermaid diagram"
      sx={{ my: 2, textAlign: 'center' }}
      dangerouslySetInnerHTML={{ __html: svg }} />
  )
}

interface MarkdownRendererProps {
  content: string
  sx?: SxProps<Theme>
}

export default function MarkdownRenderer({ content, sx }: MarkdownRendererProps) {
  return (
    <Box sx={{ '& img': { maxWidth: '100%' }, ...sx }}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => <Typography variant="h5" sx={{ mt: 3, mb: 1.5 }}>{children}</Typography>,
          h2: ({ children }) => <Typography variant="h6" sx={{ mt: 2.5, mb: 1 }}>{children}</Typography>,
          h3: ({ children }) => <Typography variant="subtitle1" sx={{ mt: 2, mb: 0.5, fontWeight: 600 }}>{children}</Typography>,
          h4: ({ children }) => <Typography variant="subtitle2" sx={{ mt: 1.5, mb: 0.5, fontWeight: 600 }}>{children}</Typography>,
          p: ({ children }) => <Typography variant="body2" sx={{ mb: 1.5 }}>{children}</Typography>,
          li: ({ children }) => (
            <Box component="li" sx={{ mb: 0.5 }}>
              <Typography variant="body2" component="span">{children}</Typography>
            </Box>
          ),
          hr: () => <Divider sx={{ my: 2 }} />,
          table: ({ children }) => (
            <Box sx={{ overflowX: 'auto', mb: 2 }}>
              <Table size="small" sx={{ '& th, & td': { fontSize: '0.875rem' } }}>
                {children}
              </Table>
            </Box>
          ),
          thead: ({ children }) => <TableHead>{children}</TableHead>,
          tbody: ({ children }) => <TableBody>{children}</TableBody>,
          tr: ({ children }) => <TableRow>{children}</TableRow>,
          th: ({ children }) => <TableCell sx={{ fontWeight: 600, backgroundColor: 'action.hover' }}>{children}</TableCell>,
          td: ({ children }) => <TableCell>{children}</TableCell>,
          code: ({ className, children }) => {
            const match = /language-(\w+)/.exec(className ?? '')
            const lang = match?.[1]
            const codeStr = String(children).replace(/\n$/, '')

            if (lang === 'mermaid') {
              return <MermaidDiagram chart={codeStr} />
            }

            if (lang) {
              return (
                <Box component="pre" sx={{
                  backgroundColor: 'background.default', p: 2, borderRadius: 1,
                  fontFamily: 'monospace', fontSize: '0.8125rem', overflowX: 'auto', my: 1.5,
                }}>
                  <code>{codeStr}</code>
                </Box>
              )
            }

            return (
              <Box component="code" sx={{
                backgroundColor: 'action.hover', px: 0.5, py: 0.25,
                borderRadius: 0.5, fontFamily: 'monospace', fontSize: '0.85em',
              }}>
                {children}
              </Box>
            )
          },
          a: ({ href, children }) => (
            <Box component="a" href={href} target="_blank" rel="noopener noreferrer"
              sx={{ color: 'primary.main', textDecoration: 'underline' }}>
              {children}
            </Box>
          ),
          blockquote: ({ children }) => (
            <Box sx={{ borderLeft: 3, borderColor: 'primary.main', pl: 2, py: 0.5, my: 1.5, color: 'text.secondary' }}>
              {children}
            </Box>
          ),
          strong: ({ children }) => <strong>{children}</strong>,
          em: ({ children }) => <em>{children}</em>,
        }}
      >
        {content}
      </ReactMarkdown>
    </Box>
  )
}
