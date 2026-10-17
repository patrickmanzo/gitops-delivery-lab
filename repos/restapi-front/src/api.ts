const BASE = '/api'

export type User = {
  first_name: string
  last_name: string
  cpf: string
  email: string
  birth_date: string // YYYY-MM-DD
}

export type Info = { environment: string; version: string }

// relaxed Extended JSON: ISO for 1970-9999, {$numberLong: ms} otherwise (old records)
type MongoDate = { $date: string | { $numberLong: string } }
type RawUser = Omit<User, 'birth_date'> & { birth_date: MongoDate | string }

export class ApiError extends Error {}

// W3C Trace Context per call; getRandomValues because randomUUID needs HTTPS outside localhost
function randomHex(bytes: number): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(bytes)), (b) => b.toString(16).padStart(2, '0')).join('')
}

export function traceHeaders(): Record<string, string> {
  const traceId = randomHex(16)
  return { traceparent: `00-${traceId}-${randomHex(8)}-01`, 'X-Request-ID': traceId }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...traceHeaders() },
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new ApiError(errorMessage(body) ?? `HTTP ${response.status}`)
  }
  return body as T
}

// reqparse returns {message: {field: error}}; everything else returns {message: "..."}
function errorMessage(body: unknown): string | undefined {
  const message = (body as { message?: unknown } | null)?.message
  if (typeof message === 'string') return message
  if (message && typeof message === 'object') {
    return Object.entries(message)
      .map(([field, error]) => `${field}: ${error}`)
      .join(', ')
  }
  return undefined
}

export function toUser(raw: RawUser): User {
  const value = typeof raw.birth_date === 'string' ? raw.birth_date : raw.birth_date.$date
  const date = typeof value === 'string' ? value : new Date(Number(value.$numberLong)).toISOString()
  return {
    first_name: raw.first_name,
    last_name: raw.last_name,
    cpf: raw.cpf,
    email: raw.email,
    birth_date: date.slice(0, 10),
  }
}

export const api = {
  health: () => request<string>('/health'),
  version: () => request<Info>('/version'),
  users: async () => (await request<RawUser[]>('/users')).map(toUser),
  create: (user: User) =>
    request<{ message: string }>('/user', { method: 'POST', body: JSON.stringify(user) }),
  update: (user: User) =>
    request<{ message: string }>('/user', { method: 'PATCH', body: JSON.stringify(user) }),
  remove: (cpf: string) =>
    request<{ message: string }>(`/user/${encodeURIComponent(cpf)}`, { method: 'DELETE' }),
}

export async function frontInfo(): Promise<Info> {
  try {
    const response = await fetch('/config.json')
    if (response.ok) return (await response.json()) as Info
  } catch {
    // no config.json: running locally
  }
  return { environment: 'local', version: 'local' }
}
