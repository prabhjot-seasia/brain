import { describe, it, expect } from 'vitest'
import { assurantTheme } from './assurantTheme'

describe('assurantTheme', () => {
  it('defines the primary color as Assurant blue', () => {
    expect(assurantTheme.palette.primary.main).toBe('#006ebb')
  })

  it('uses Roboto as the default font', () => {
    expect(assurantTheme.typography.fontFamily).toContain('Roboto')
  })

  it('has a light palette mode', () => {
    expect(assurantTheme.palette.mode).toBe('light')
  })
})
