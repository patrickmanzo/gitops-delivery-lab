variable "openbao_jwt" {
  description = "Token da SA openbao/openbao-admin (o make preenche via TF_VAR_openbao_jwt)"
  type        = string
  sensitive   = true
}

variable "org" {
  type    = string
  default = "patrickmanzo"
}

variable "discord_webhook" {
  description = "Opcional: URL do webhook Discord para notificações do pipeline"
  type        = string
  default     = ""
  sensitive   = true
}
