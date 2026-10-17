{{- define "restapi-front.fullname" -}}
{{- default (printf "%s-%s" .Release.Name .Chart.Name) .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "restapi-front.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* labels recomendadas do Kubernetes; version = tag da imagem em execução */}}
{{- define "restapi-front.labels" -}}
{{ include "restapi-front.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/component: frontend
app.kubernetes.io/part-of: restapi
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
environment: {{ .Values.environment }}
{{- end }}
