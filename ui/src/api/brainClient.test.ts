import { describe, it, expect, vi, beforeEach } from 'vitest'
import axios from 'axios'

vi.mock('axios', () => {
  const mockClient = {
    get: vi.fn(),
    post: vi.fn(),
  }
  return {
    default: { create: () => mockClient },
    ...mockClient,
  }
})

const mockAxios = axios as unknown as {
  create: () => { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn> }
}
const client = mockAxios.create()

import { brainApi } from './brainClient'

describe('brainApi', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('listProjects calls GET /projects', async () => {
    client.get.mockResolvedValue({ data: [{ id: 'test' }] })
    const result = await brainApi.listProjects()
    expect(result).toEqual([{ id: 'test' }])
  })

  it('getProject calls GET /projects/:id', async () => {
    client.get.mockResolvedValue({ data: { id: 'ce-imei', name: 'ce-IMEI' } })
    const result = await brainApi.getProject('ce-imei')
    expect(result.id).toBe('ce-imei')
  })

  it('ingest calls POST /projects/ingest', async () => {
    client.post.mockResolvedValue({ data: { status: 'ingestion_started' } })
    const result = await brainApi.ingest({
      projectId: 'test', projectName: 'Test', repoUrl: 'https://github.com/org/repo.git',
    })
    expect(result.status).toBe('ingestion_started')
  })

  it('analyze calls POST /analyze', async () => {
    client.post.mockResolvedValue({ data: { planReady: true, plan: '{}' } })
    const result = await brainApi.analyze({ projectId: 'test', requirement: 'add auth' })
    expect(result.planReady).toBe(true)
  })

  it('getConventions calls GET with category param', async () => {
    client.get.mockResolvedValue({ data: [] })
    await brainApi.getConventions('test', 'logging')
    expect(client.get).toHaveBeenCalled()
  })

  it('getIngestionStatus calls GET /projects/:id/status', async () => {
    client.get.mockResolvedValue({ data: { status: 'COMPLETE' } })
    const result = await brainApi.getIngestionStatus('test')
    expect(result.status).toBe('COMPLETE')
  })

  it('getJiraConfig returns PAT configuration status', async () => {
    client.get.mockResolvedValue({ data: { configured: true, baseUrl: 'https://flipswap.jira.com' } })
    const result = await brainApi.getJiraConfig()
    expect(result.configured).toBe(true)
    expect(result.baseUrl).toBe('https://flipswap.jira.com')
  })

  it('fetchJiraTicket returns extracted text from ticket', async () => {
    client.post.mockResolvedValue({ data: { intakeId: 'int-1', sourceType: 'JIRA_TICKET', extractedText: 'Add retry', charCount: 9 } })
    const result = await brainApi.fetchJiraTicket('PAY-123')
    expect(result.extractedText).toBe('Add retry')
  })

  it('uploadDocument sends multipart form data', async () => {
    client.post.mockResolvedValue({ data: { intakeId: 'int-2', sourceType: 'DOCUMENT_UPLOAD', extractedText: 'doc content', charCount: 11 } })
    const file = new File(['test'], 'test.pdf', { type: 'application/pdf' })
    const result = await brainApi.uploadDocument(file)
    expect(result.sourceType).toBe('DOCUMENT_UPLOAD')
  })

  it('submitAdhoc sends plain text', async () => {
    client.post.mockResolvedValue({ data: { intakeId: 'int-3', sourceType: 'ADHOC_TEXT', extractedText: 'my requirement', charCount: 14 } })
    const result = await brainApi.submitAdhoc('my requirement')
    expect(result.sourceType).toBe('ADHOC_TEXT')
  })
})
