"""Logs em JSON (uma linha por evento) para o Loki.

Nomes de campo seguem as convenções semânticas do OpenTelemetry (semconv 1.2x),
então um OTel SDK/Collector pode substituir este formatter sem renomear consultas.
"""

import json
import logging
import os
import re
import secrets
import time
from datetime import datetime, timezone

from flask import g, has_request_context, request
from opentelemetry import trace

SERVICE = {
    "service.name": "restapi-flask",
    "service.version": os.getenv("APP_VERSION", "local"),
    "deployment.environment.name": os.getenv("ENVIRONMENT", "local"),
}

# kubelet probes: frequent and useless in the access log
QUIET_PATHS = {"/health", "/ready"}

access_logger = logging.getLogger("restapi.access")

# W3C Trace Context: 00-<trace_id 32 hex>-<parent span 16 hex>-<flags>
TRACEPARENT = re.compile(r"^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$")


def trace_context(header):
    """trace_id/span_id do request: do span OTel ativo (tracing ligado) ou,
    sem SDK, do traceparent recebido (ou novos) para o log manter o formato.
    """
    span = trace.get_current_span().get_span_context()
    if span.is_valid:
        return format(span.trace_id, "032x"), format(span.span_id, "016x")
    match = TRACEPARENT.match(header or "")
    trace_id = match.group(1) if match and match.group(1) != "0" * 32 else None
    return trace_id or secrets.token_hex(16), secrets.token_hex(8)


class JsonFormatter(logging.Formatter):
    def format(self, record):
        entry = {
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(
                timespec="milliseconds"
            ),
            "level": record.levelname.lower(),
            "logger": record.name,
            "message": record.getMessage(),
            **SERVICE,
            **getattr(record, "fields", {}),
        }
        # logs emitted during a request carry the access log request_id
        if has_request_context() and "request_id" in g:
            entry.setdefault("trace_id", g.trace_id)
            entry.setdefault("span_id", g.span_id)
            entry.setdefault("request_id", g.request_id)
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


def setup_logging():
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(os.getenv("LOG_LEVEL", "INFO"))


def init_request_logging(app):
    @app.before_request
    def start_request():
        g.start = time.perf_counter()
        # same IDs as the front end/Traefik; generated when the call has none
        g.trace_id, g.span_id = trace_context(request.headers.get("traceparent"))
        g.request_id = request.headers.get("X-Request-ID") or g.trace_id

    @app.after_request
    def log_request(response):
        response.headers["X-Request-ID"] = g.request_id
        if request.path not in QUIET_PATHS:
            access_logger.log(
                logging.ERROR if response.status_code >= 500 else logging.INFO,
                "%s %s %s",
                request.method,
                request.path,
                response.status_code,
                extra={
                    "fields": {
                        "http.request.method": request.method,
                        "url.path": request.path,
                        "http.response.status_code": response.status_code,
                        "duration_ms": round((time.perf_counter() - g.start) * 1000, 2),
                        "client.address": request.headers.get(
                            "X-Forwarded-For", request.remote_addr
                        ),
                        "user_agent.original": request.user_agent.string,
                    }
                },
            )
        return response
