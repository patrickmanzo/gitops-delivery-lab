import { describe, expect, it } from 'vitest'
import { isValidCpf, maskCpf } from './cpf'

describe('cpf', () => {
  it('aplica a máscara enquanto digita', () => {
    expect(maskCpf('641')).toBe('641')
    expect(maskCpf('6413965')).toBe('641.396.5')
    expect(maskCpf('64139650028')).toBe('641.396.500-28')
    expect(maskCpf('641.396.500-28999')).toBe('641.396.500-28')
  })

  it('valida dígitos verificadores como o backend', () => {
    expect(isValidCpf('641.396.500-28')).toBe(true)
    expect(isValidCpf('641.396.500-27')).toBe(false)
    expect(isValidCpf('111.111.111-11')).toBe(false)
    expect(isValidCpf('64139650028')).toBe(false)
  })
})
