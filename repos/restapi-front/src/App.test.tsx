import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const user = {
  first_name: 'Patrick',
  last_name: 'Manzo',
  cpf: '641.396.500-28',
  email: 'patrick@localhost.com',
  birth_date: { $date: '1996-09-10T00:00:00Z' },
}

function json(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

describe('App', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/config.json') return json({ environment: 'stg', version: 'v1.0.0-abc' })
      if (url === '/api/version') return json({ environment: 'stg', version: 'v1.0.0-def' })
      if (url === '/api/users') return json([user])
      if (url === '/api/user' && init?.method === 'POST') return json({ message: 'CPF already exists in database!' }, 400)
      return json({}, 404)
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('mostra ambiente, versões e usuários', async () => {
    render(<App />)
    expect(await screen.findByText('Patrick Manzo')).toBeInTheDocument()
    expect(screen.getByTestId('environment')).toHaveTextContent('stg')
    expect(screen.getByText('v1.0.0-abc')).toBeInTheDocument()
    expect(screen.getByText('v1.0.0-def')).toBeInTheDocument()
    expect(screen.getByText('10/09/1996')).toBeInTheDocument()
  })

  it('filtra pela busca', async () => {
    render(<App />)
    await screen.findByText('Patrick Manzo')
    await userEvent.type(screen.getByPlaceholderText(/Buscar/), 'ninguém')
    expect(screen.getByText('Nada encontrado.')).toBeInTheDocument()
  })

  it('mostra o erro da API ao cadastrar', async () => {
    render(<App />)
    const form = screen.getByRole('form', { name: 'usuário' })
    await userEvent.type(within(form).getByLabelText('Nome'), 'Patrick')
    await userEvent.type(within(form).getByLabelText('Sobrenome'), 'Manzo')
    await userEvent.type(within(form).getByLabelText(/CPF/), '64139650028')
    await userEvent.type(within(form).getByLabelText('E-mail'), 'patrick@localhost.com')
    await userEvent.type(within(form).getByLabelText('Nascimento'), '1996-09-10')
    await userEvent.click(within(form).getByRole('button', { name: 'Cadastrar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('CPF already exists in database!')
  })

  it('bloqueia CPF inválido sem chamar a API', async () => {
    render(<App />)
    await userEvent.type(screen.getByLabelText(/CPF/), '64139650027')
    expect(screen.getByText('CPF inválido')).toBeInTheDocument()
  })
})
