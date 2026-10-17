import { expect, test, vi } from 'vitest'
import { api, toUser, traceHeaders } from './api'

test('traceparent W3C com o mesmo trace_id do X-Request-ID, novo a cada chamada', () => {
  const headers = traceHeaders()
  expect(headers.traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/)
  expect(headers.traceparent.split('-')[1]).toBe(headers['X-Request-ID'])
  expect(traceHeaders()['X-Request-ID']).not.toBe(headers['X-Request-ID'])
})

test('chamadas à API enviam traceparent', async () => {
  const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response('"OK"', { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  await api.health()
  const headers = fetchMock.mock.calls[0][1]!.headers as Record<string, string>
  expect(headers.traceparent).toMatch(/^00-[0-9a-f]{32}-/)
  vi.unstubAllGlobals()
})

test('toUser aceita data ISO e $numberLong (fora de 1970-9999)', () => {
  const base = { first_name: 'a', last_name: 'b', cpf: '1', email: 'e' }
  expect(toUser({ ...base, birth_date: { $date: '1996-09-10T00:00:00Z' } }).birth_date).toBe('1996-09-10')
  expect(toUser({ ...base, birth_date: { $date: { $numberLong: '-62099568000000' } } }).birth_date).toBe('0002-02-22')
})
