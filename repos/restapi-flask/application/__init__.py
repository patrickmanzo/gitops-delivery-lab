from flask import Flask
from flask_restful import Api
from .db import init_db
from .logs import init_request_logging
from .tracing import init_tracing
from .app import User, Users, HealthCheck, Ready, Version


def create_app(config):
    # JSON API without sessions/cookies: CSRF does not apply
    app = Flask(__name__)  # NOSONAR
    app.config.from_object(config)
    api = Api(app)
    # tracing before the database (pymongo only instruments clients created afterwards)
    # and before logging (OTel's before_request opens the span the log reads)
    init_tracing(app)
    init_db(app)
    init_request_logging(app)

    api.add_resource(Users, "/users")
    api.add_resource(User, "/user", "/user/<string:cpf>")
    api.add_resource(HealthCheck, "/health")
    api.add_resource(Ready, "/ready")
    api.add_resource(Version, "/version")

    return app
