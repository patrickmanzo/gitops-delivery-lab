// Each iteration is one user flow through the front end; whatever it creates it deletes at the end.
// Gentle waves (~0.5 to ~2 iter/s) with a VU cap.
import http from 'k6/http'
import { check, sleep } from 'k6'

const OK = http.expectedStatuses({ min: 200, max: 399 })
const BAD_REQUEST = http.expectedStatuses(400)

const BASE = __ENV.BASE_URL // ex.: http://app-dev.localhost.com/api
const CYCLE_MINUTES = Number(__ENV.CYCLE_MINUTES || 60)
const CYCLES = Number(__ENV.CYCLES || 24)

function wave() {
  const m = CYCLE_MINUTES
  return [
    { target: 5, duration: `${Math.round(m * 0.6)}m` },
    { target: 20, duration: `${Math.round(m * 0.1)}m` },
    { target: 20, duration: `${Math.round(m * 0.2)}m` },
    { target: 5, duration: `${Math.round(m * 0.1)}m` },
  ]
}

export const options = {
  scenarios: {
    usuarios: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '10s',
      preAllocatedVUs: 2,
      maxVUs: 8, // cap: if the API slows down, load does not pile up
      stages: Array.from({ length: CYCLES }, wave).flat(),
    },
  },
  // never abort on failures: the intentional 400s are part of the traffic
  thresholds: { 'http_req_duration{expected_response:true}': ['p(95)<1000'] },
}

const hex = (bytes) =>
  Array.from({ length: bytes }, () => Math.floor(Math.random() * 256).toString(16).padStart(2, '0')).join('')

function headers() {
  return {
    'Content-Type': 'application/json',
    traceparent: `00-${hex(16)}-${hex(8)}-01`,
    'User-Agent': 'k6-loadgen',
  }
}

function cpf() {
  const n = Array.from({ length: 9 }, () => Math.floor(Math.random() * 10))
  for (const len of [9, 10]) {
    const sum = n.slice(0, len).reduce((acc, d, i) => acc + d * (len + 1 - i), 0)
    n.push(((sum * 10) % 11) % 10)
  }
  const s = n.join('')
  return `${s.slice(0, 3)}.${s.slice(3, 6)}.${s.slice(6, 9)}-${s.slice(9)}`
}

// intentional 400s do not count as http_req_failed
const req = (method, path, body, name, expected = OK) =>
  http.request(method, BASE + path, body && JSON.stringify(body), {
    headers: headers(),
    tags: { name },
    responseCallback: expected,
  })

export default function () {
  check(req('GET', '/users', null, 'GET /users'), { 'lista 200': (r) => r.status === 200 })

  // ~5% controlled validation/business errors (400, never 5xx)
  if (Math.random() < 0.05) {
    const bad = [
      () => req('POST', '/user', { first_name: 'x', last_name: 'y', cpf: '111.111.111-11', email: 'x@y.com', birth_date: '1990-01-01' }, 'POST /user', BAD_REQUEST),
      () => req('GET', `/user/${cpf()}`, null, 'GET /user/<cpf>', BAD_REQUEST),
      () => req('POST', '/user', { first_name: 'x', last_name: 'y', cpf: cpf(), email: 'x@y.com', birth_date: '0002-02-22' }, 'POST /user', BAD_REQUEST),
    ]
    check(bad[Math.floor(Math.random() * bad.length)](), { 'erro esperado 400': (r) => r.status === 400 })
    return
  }

  const user = {
    first_name: 'Carga',
    last_name: `k6 ${hex(2)}`,
    cpf: cpf(),
    email: `k6-${hex(4)}@loadgen.local`,
    birth_date: '1990-05-17',
  }
  if (!check(req('POST', '/user', user, 'POST /user'), { 'cadastro 200': (r) => r.status === 200 })) return
  sleep(Math.random())
  check(req('GET', `/user/${user.cpf}`, null, 'GET /user/<cpf>'), { 'consulta 200': (r) => r.status === 200 })
  check(req('PATCH', '/user', { ...user, last_name: 'k6 editado' }, 'PATCH /user'), { 'edição 200': (r) => r.status === 200 })
  sleep(Math.random())
  check(req('DELETE', `/user/${user.cpf}`, null, 'DELETE /user/<cpf>'), { 'remoção 200': (r) => r.status === 200 })
}
