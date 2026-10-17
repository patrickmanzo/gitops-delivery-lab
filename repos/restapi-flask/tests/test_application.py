import mongomock
import pytest
from application import create_app


class MockConfig:
    MONGODB_SETTINGS = {
        "db": "users",
        "host": "mongodb://localhost",
        "mongo_client_class": mongomock.MongoClient,
    }


class TestApplication:
    @pytest.fixture
    def client(self):
        app = create_app(MockConfig)
        return app.test_client()

    @pytest.fixture
    def valid_user(self):
        return {
            "first_name": "Patrick",
            "last_name": "Manzo",
            "cpf": "641.396.500-28",
            "email": "patrick@localhost.com",
            "birth_date": "1996-09-10",
        }

    @pytest.fixture
    def invalid_user(self):
        return {
            "first_name": "Patrick",
            "last_name": "Manzo",
            "cpf": "641.396.500-27",
            "email": "patrick@localhost.com",
            "birth_date": "1996-09-10",
        }

    def test_request_id_propagado(self, client):
        response = client.get("/health", headers={"X-Request-ID": "abc-123"})
        assert response.headers["X-Request-ID"] == "abc-123"

    def test_request_id_gerado_e_access_log_json(self, client, capsys):
        import json
        import logging

        from application.logs import JsonFormatter

        handler = logging.StreamHandler()
        handler.setFormatter(JsonFormatter())
        logger = logging.getLogger("restapi.access")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        try:
            response = client.get("/users")
        finally:
            logger.removeHandler(handler)

        request_id = response.headers["X-Request-ID"]
        assert len(request_id) == 32
        entry = json.loads(capsys.readouterr().err.strip().splitlines()[-1])
        assert entry["request_id"] == request_id
        assert entry["http.response.status_code"] == 200
        assert entry["url.path"] == "/users"
        assert entry["service.name"] == "restapi-flask"

    def test_traceparent_propagado_no_log(self, client, capsys):
        import json
        import logging

        from application.logs import JsonFormatter

        trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
        handler = logging.StreamHandler()
        handler.setFormatter(JsonFormatter())
        logger = logging.getLogger("restapi.access")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        try:
            response = client.get(
                "/users", headers={"traceparent": f"00-{trace_id}-00f067aa0ba902b7-01"}
            )
        finally:
            logger.removeHandler(handler)

        entry = json.loads(capsys.readouterr().err.strip().splitlines()[-1])
        assert entry["trace_id"] == trace_id
        assert len(entry["span_id"]) == 16
        assert entry["request_id"] == response.headers["X-Request-ID"] == trace_id

    def test_trace_id_do_span_otel(self, capsys, monkeypatch):
        import json
        import logging

        from opentelemetry.sdk.trace.export import SimpleSpanProcessor
        from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
            InMemorySpanExporter,
        )

        import application.tracing
        from application.logs import JsonFormatter

        # init_tracing de verdade, só com exporter em memória e flush síncrono
        exporter = InMemorySpanExporter()
        monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318")
        monkeypatch.setattr(application.tracing, "OTLPSpanExporter", lambda: exporter)
        monkeypatch.setattr(
            application.tracing, "BatchSpanProcessor", SimpleSpanProcessor
        )
        app = create_app(MockConfig)
        handler = logging.StreamHandler()
        handler.setFormatter(JsonFormatter())
        logger = logging.getLogger("restapi.access")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
        try:
            app.test_client().get(
                "/version",
                headers={"traceparent": f"00-{trace_id}-00f067aa0ba902b7-01"},
            )
        finally:
            logger.removeHandler(handler)

        entry = json.loads(capsys.readouterr().err.strip().splitlines()[-1])
        span = exporter.get_finished_spans()[-1]
        assert entry["trace_id"] == trace_id == format(span.context.trace_id, "032x")
        assert entry["span_id"] == format(span.context.span_id, "016x")

    def test_traceparent_invalido_gera_novo(self):
        from application.logs import trace_context

        trace_id, span_id = trace_context("lixo")
        assert len(trace_id) == 32
        assert len(span_id) == 16
        assert trace_context(f"00-{'0' * 32}-{'1' * 16}-01")[0] != "0" * 32

    def test_ready(self, client):
        response = client.get("/ready")
        assert response.status_code == 200
        assert response.json == {"status": "ready"}

    def test_not_ready_without_database(self, client, monkeypatch):
        def unreachable():
            raise ConnectionError("mongo down")

        monkeypatch.setattr("application.app.get_db", unreachable)
        response = client.get("/ready")
        assert response.status_code == 503

    def test_version(self, client, monkeypatch):
        monkeypatch.setenv("ENVIRONMENT", "dev")
        monkeypatch.setenv("APP_VERSION", "dev-abc123")
        response = client.get("/version")
        assert response.status_code == 200
        assert response.json == {"environment": "dev", "version": "dev-abc123"}

    def test_get_users(self, client):
        response = client.get("/users")
        assert response.status_code == 200

    def test_post_user(self, client, valid_user, invalid_user):
        response = client.post("/user", json=valid_user)
        assert response.status_code == 200
        assert b"successfully" in response.data

        response = client.post("/user", json=invalid_user)
        assert response.status_code == 400
        assert b"invalid" in response.data

    def test_post_user_birth_date_invalida(self, client, valid_user):
        for birth_date in ("0002-02-22", "2999-01-01", "22/02/1990"):
            response = client.post(
                "/user", json={**valid_user, "birth_date": birth_date}
            )
            assert response.status_code == 400
            assert "Birth date" in response.json["message"]

    def test_get_user(self, client, valid_user, invalid_user):
        response = client.get("/user/%s" % valid_user["cpf"])
        assert response.status_code == 200
        assert response.json[0]["first_name"] == "Patrick"
        assert response.json[0]["last_name"] == "Manzo"
        assert response.json[0]["cpf"] == "641.396.500-28"
        assert response.json[0]["email"] == "patrick@localhost.com"

        birth_date = response.json[0]["birth_date"]["$date"]
        assert birth_date == "1996-09-10T00:00:00Z"

        response = client.get("/user/%s" % invalid_user["cpf"])
        assert response.status_code == 400
        assert b"User does not exist in database!" in response.data

    def test_patch_user(self, client, valid_user):
        valid_user["first_name"] = "Pat"
        response = client.patch("/user", json=valid_user)
        assert response.status_code == 200
        assert b"updated" in response.data

        valid_user["cpf"] = "199.624.120-64"
        response = client.patch("/user", json=valid_user)
        assert response.status_code == 400
        assert b"does not exist in database" in response.data

    def test_delete_user(self, client, valid_user):
        response = client.delete("/user/%s" % valid_user["cpf"])
        assert response.status_code == 200
        assert b"deleted" in response.data

        response = client.delete("/user/%s" % valid_user["cpf"])
        assert response.status_code == 400
        assert b"does not exist in database" in response.data
