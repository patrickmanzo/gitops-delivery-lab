import { useCallback, useEffect, useMemo, useState, type SubmitEvent } from 'react'
import { api, frontInfo, type Info, type User } from './api'
import { isValidCpf, maskCpf } from './cpf'

const EMPTY: User = { first_name: '', last_name: '', cpf: '', email: '', birth_date: '' }

type Status = 'checking' | 'up' | 'down'
const STATUS_LABEL: Record<Status, string> = { checking: '…', up: 'online', down: 'offline' }
type Notice = { kind: 'ok' | 'error'; text: string } | null

export default function App() {
  const [front, setFront] = useState<Info>()
  const [backend, setBackend] = useState<Info>()
  const [status, setStatus] = useState<Status>('checking')
  const [users, setUsers] = useState<User[]>([])
  const [search, setSearch] = useState('')
  const [form, setForm] = useState<User>(EMPTY)
  const [editing, setEditing] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const loadUsers = useCallback(async () => {
    try {
      setUsers(await api.users())
      setStatus('up')
    } catch {
      setStatus('down')
    }
  }, [])

  useEffect(() => {
    frontInfo().then(setFront)
    api.version().then(setBackend).catch(() => setStatus('down'))
    loadUsers()
    const timer = setInterval(() => {
      api.health().then(() => setStatus('up')).catch(() => setStatus('down'))
    }, 10_000)
    return () => clearInterval(timer)
  }, [loadUsers])

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return users
    return users.filter((user) =>
      [user.first_name, user.last_name, user.email, user.cpf].some((field) =>
        field.toLowerCase().includes(term),
      ),
    )
  }, [users, search])

  const cpfInvalid = form.cpf.length === 14 && !isValidCpf(form.cpf)

  function change(field: keyof User, value: string) {
    setForm((current) => ({ ...current, [field]: field === 'cpf' ? maskCpf(value) : value }))
  }

  function reset() {
    setForm(EMPTY)
    setEditing(false)
  }

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!isValidCpf(form.cpf)) {
      setNotice({ kind: 'error', text: 'CPF inválido' })
      return
    }
    try {
      const { message } = editing ? await api.update(form) : await api.create(form)
      setNotice({ kind: 'ok', text: message })
      reset()
      await loadUsers()
    } catch (error) {
      setNotice({ kind: 'error', text: (error as Error).message })
    }
  }

  async function remove(cpf: string) {
    try {
      const { message } = await api.remove(cpf)
      setNotice({ kind: 'ok', text: message })
      await loadUsers()
    } catch (error) {
      setNotice({ kind: 'error', text: (error as Error).message })
    }
  }

  const environment = front?.environment ?? 'local'

  return (
    <div className={`page env-${environment}`}>
      <header className="header">
        <div>
          <h1>Usuários</h1>
          <span className="subtitle">restapi-front + restapi-flask</span>
        </div>
        <div className="meta">
          <span className="badge" data-testid="environment">
            {environment}
          </span>
          <span>
            front <code>{front?.version ?? '…'}</code>
          </span>
          <span>
            api <code>{backend?.version ?? '…'}</code>
          </span>
          <span className={`status status-${status}`} title={`API ${status}`}>
            API {STATUS_LABEL[status]}
          </span>
        </div>
      </header>

      {notice && (
        <output className={`notice notice-${notice.kind}`}>
          {notice.text}
          <button type="button" className="link" onClick={() => setNotice(null)}>
            fechar
          </button>
        </output>
      )}

      <main className="grid">
        <form className="card" onSubmit={submit} aria-label="usuário">
          <h2>{editing ? 'Editar usuário' : 'Novo usuário'}</h2>
          <label>
            <span>Nome</span>
            <input required value={form.first_name} onChange={(e) => change('first_name', e.target.value)} />
          </label>
          <label>
            <span>Sobrenome</span>
            <input required value={form.last_name} onChange={(e) => change('last_name', e.target.value)} />
          </label>
          <label>
            <span>CPF</span>
            <input
              required
              disabled={editing}
              placeholder="000.000.000-00"
              aria-invalid={cpfInvalid}
              value={form.cpf}
              onChange={(e) => change('cpf', e.target.value)}
            />
            {cpfInvalid && <small className="error">CPF inválido</small>}
          </label>
          <label>
            <span>E-mail</span>
            <input required type="email" value={form.email} onChange={(e) => change('email', e.target.value)} />
          </label>
          <label>
            <span>Nascimento</span>
            <input
              required
              type="date"
              min="1900-01-01"
              max={new Date().toISOString().slice(0, 10)}
              value={form.birth_date}
              onChange={(e) => change('birth_date', e.target.value)}
            />
          </label>
          <div className="actions">
            <button type="submit">{editing ? 'Salvar' : 'Cadastrar'}</button>
            {editing && (
              <button type="button" className="secondary" onClick={reset}>
                Cancelar
              </button>
            )}
          </div>
        </form>

        <section className="card">
          <div className="toolbar">
            <h2>
              Cadastrados <span className="count">{users.length}</span>
            </h2>
            <input
              type="search"
              placeholder="Buscar por nome, e-mail ou CPF"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          {filtered.length === 0 ? (
            <p className="empty">{users.length === 0 ? 'Nenhum usuário cadastrado.' : 'Nada encontrado.'}</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Nome</th>
                    <th>CPF</th>
                    <th>E-mail</th>
                    <th>Nascimento</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((user) => (
                    <tr key={user.cpf}>
                      <td>
                        {user.first_name} {user.last_name}
                      </td>
                      <td>
                        <code>{user.cpf}</code>
                      </td>
                      <td>{user.email}</td>
                      <td>{user.birth_date.split('-').reverse().join('/')}</td>
                      <td className="row-actions">
                        <button
                          type="button"
                          className="link"
                          onClick={() => {
                            setForm(user)
                            setEditing(true)
                          }}
                        >
                          editar
                        </button>
                        <button type="button" className="link danger" onClick={() => remove(user.cpf)}>
                          excluir
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </main>
    </div>
  )
}
