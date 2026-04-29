import { useEffect, useRef, useState } from 'react'

export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'

export interface JobSnapshot {
  id: string
  jobType: string
  targetKind: string
  targetId: string
  projectId: string | null
  status: JobStatus
  progressPct: number | null
  progressMsg: string | null
  errorMessage: string | null
  result: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface JobStreamEvent {
  name: string
  data: Record<string, unknown>
}

export interface UseJobStreamOptions {
  onEvent?: (event: JobStreamEvent) => void
  onTerminal?: (snapshot: JobSnapshot | null) => void
  reconnectMaxAttempts?: number
  baseUrl?: string
}

export interface UseJobStreamResult {
  snapshot: JobSnapshot | null
  events: JobStreamEvent[]
  isConnected: boolean
  error: string | null
  close: () => void
}

const TERMINAL_EVENTS = new Set(['succeeded', 'partial', 'failed', 'cancelled'])

export function useJobStream(jobId: string | null, opts: UseJobStreamOptions = {}): UseJobStreamResult {
  const [snapshot, setSnapshot] = useState<JobSnapshot | null>(null)
  const [events, setEvents] = useState<JobStreamEvent[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const sourceRef = useRef<EventSource | null>(null)
  const attemptRef = useRef(0)
  const cancelledRef = useRef(false)
  const optsRef = useRef(opts)
  optsRef.current = opts

  useEffect(() => {
    if (!jobId) return
    cancelledRef.current = false
    attemptRef.current = 0

    const baseUrl = opts.baseUrl ?? '/api/v1'
    const maxAttempts = opts.reconnectMaxAttempts ?? 5

    function connect() {
      if (cancelledRef.current) return
      const es = new EventSource(`${baseUrl}/jobs/stream/${jobId}`)
      sourceRef.current = es
      setIsConnected(true)
      setError(null)

      const handle = (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data)
          const job = (data && typeof data === 'object' && 'job' in data ? data.job : null) as JobSnapshot | null
          const event: JobStreamEvent = { name: ev.type, data: data ?? {} }
          setEvents(prev => [...prev, event])
          if (job) setSnapshot(job)
          optsRef.current.onEvent?.(event)
          if (TERMINAL_EVENTS.has(ev.type)) {
            optsRef.current.onTerminal?.(job)
            close()
          }
        } catch {
          // ignore non-JSON heartbeats
        }
      }

      ;['status', 'progress', 'succeeded', 'partial', 'failed', 'cancelled',
        'section-complete', 'type-ready', 'type-failed', 'bundle-ready', 'not-found']
        .forEach(name => es.addEventListener(name, handle as EventListener))

      es.onerror = () => {
        es.close()
        sourceRef.current = null
        setIsConnected(false)
        if (cancelledRef.current) return
        attemptRef.current += 1
        if (attemptRef.current > maxAttempts) {
          setError('Lost connection to job stream after multiple retries.')
          return
        }
        const backoffMs = Math.min(30_000, 500 * Math.pow(2, attemptRef.current))
        setTimeout(connect, backoffMs)
      }
    }

    function close() {
      cancelledRef.current = true
      sourceRef.current?.close()
      sourceRef.current = null
      setIsConnected(false)
    }

    connect()
    return close
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId])

  const close = () => {
    cancelledRef.current = true
    sourceRef.current?.close()
    sourceRef.current = null
    setIsConnected(false)
  }

  return { snapshot, events, isConnected, error, close }
}
