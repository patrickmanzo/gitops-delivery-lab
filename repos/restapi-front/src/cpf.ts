// Same rule as the backend (User.validate_cpf): 000.000.000-00 mask + check digits

export function maskCpf(value: string): string {
  const digits = value.replace(/\D/g, '').slice(0, 11)
  return digits
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d{1,2})$/, '.$1-$2')
}

function checkDigit(numbers: number[], length: number): number {
  const sum = numbers
    .slice(0, length)
    .reduce((total, digit, index) => total + digit * (length + 1 - index), 0)
  return ((sum * 10) % 11) % 10
}

export function isValidCpf(cpf: string): boolean {
  if (!/^\d{3}\.\d{3}\.\d{3}-\d{2}$/.test(cpf)) return false
  const numbers = cpf.replace(/\D/g, '').split('').map(Number)
  if (new Set(numbers).size === 1) return false
  return checkDigit(numbers, 9) === numbers[9] && checkDigit(numbers, 10) === numbers[10]
}
