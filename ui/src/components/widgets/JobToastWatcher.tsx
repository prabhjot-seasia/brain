import Snackbar from '@mui/material/Snackbar'
import Alert from '@mui/material/Alert'
import { useEffect, useState } from 'react'
import { useJobStream, type JobSnapshot } from '../../hooks/useJobStream'

const STORAGE_KEY = 'brain.jobs.watching'

export interface WatchedJob {
  jobId: string
  label: string
  startedAt: string
}

export function startWatchingJob(jobId: string, label: string): void {
  if (typeof window === 'undefined') return
  try {
    const existing = readWatched()
    if (existing.find(w => w.jobId === jobId)) return
    const next = [...existing, { jobId, label, startedAt: new Date().toISOString() }]
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    window.dispatchEvent(new CustomEvent('brain.jobs.watching.changed'))
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission().catch(() => undefined)
    }
  } catch {
    // localStorage may be unavailable
  }
}

export function stopWatchingJob(jobId: string): void {
  if (typeof window === 'undefined') return
  try {
    const next = readWatched().filter(w => w.jobId !== jobId)
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    window.dispatchEvent(new CustomEvent('brain.jobs.watching.changed'))
  } catch {
    // ignore
  }
}

function readWatched(): WatchedJob[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

interface ToastState {
  open: boolean
  severity: 'success' | 'warning' | 'error' | 'info'
  message: string
}

export default function JobToastWatcher() {
  const [watching, setWatching] = useState<WatchedJob[]>(() => readWatched())
  const [toast, setToast] = useState<ToastState>({ open: false, severity: 'success', message: '' })

  useEffect(() => {
    const refresh = () => setWatching(readWatched())
    refresh()
    window.addEventListener('storage', refresh)
    window.addEventListener('brain.jobs.watching.changed', refresh)
    return () => {
      window.removeEventListener('storage', refresh)
      window.removeEventListener('brain.jobs.watching.changed', refresh)
    }
  }, [])

  return (
    <>
      {watching.map(w => (
        <SingleJobWatcher
          key={w.jobId}
          watched={w}
          onTerminal={(snap, label) => {
            const severity: ToastState['severity'] = snap?.status === 'FAILED'
              ? 'error'
              : snap?.status === 'PARTIAL'
                ? 'warning'
                : 'success'
            const verb = snap?.status === 'FAILED'
              ? 'failed'
              : snap?.status === 'PARTIAL'
                ? 'completed with errors'
                : 'ready'
            const message = `${label} ${verb}`
            setToast({ open: true, severity, message })
            stopWatchingJob(w.jobId)
            if (typeof window !== 'undefined' && 'Notification' in window
                && Notification.permission === 'granted') {
              try { new Notification(`Brain: ${message}`) } catch { /* ignored */ }
            }
          }}
        />
      ))}
      <Snackbar
        open={toast.open}
        autoHideDuration={6000}
        onClose={() => setToast(t => ({ ...t, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          severity={toast.severity}
          variant="filled"
          onClose={() => setToast(t => ({ ...t, open: false }))}
        >
          {toast.message}
        </Alert>
      </Snackbar>
    </>
  )
}

function SingleJobWatcher({ watched, onTerminal }: {
  watched: WatchedJob
  onTerminal: (snap: JobSnapshot | null, label: string) => void
}) {
  useJobStream(watched.jobId, {
    onTerminal: snap => onTerminal(snap, watched.label),
  })
  return null
}
