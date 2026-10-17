"""Traces OpenTelemetry: span por request (Flask) e por comando no MongoDB.

Configurado pelas variáveis padrão do SDK (OTEL_EXPORTER_OTLP_ENDPOINT,
OTEL_RESOURCE_ATTRIBUTES...). Sem endpoint (local/testes) fica desligado.
"""

import os

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.flask import FlaskInstrumentor
from opentelemetry.instrumentation.pymongo import PymongoInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from .logs import QUIET_PATHS, SERVICE


def init_tracing(app):
    if not os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"):
        return
    # runs in each gunicorn worker (no preload): the exporter does not survive fork
    provider = TracerProvider(resource=Resource.create(SERVICE))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    instrument(app)


def instrument(app):
    # probes stay out of traces, as in the access log
    FlaskInstrumentor().instrument_app(
        app, excluded_urls=",".join(p.lstrip("/") for p in QUIET_PATHS)
    )
    PymongoInstrumentor().instrument()
