bind = "0.0.0.0:5000"
workers = 2
graceful_timeout = 20
# the control socket (gunicorn 25+) writes to $HOME, which is not writable here
control_socket_disable = True

# the application owns the access log (request_id, duration; no probes)
accesslog = None

logconfig_dict = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {"json": {"()": "application.logs.JsonFormatter"}},
    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "formatter": "json",
            "stream": "ext://sys.stdout",
        }
    },
    "root": {"level": "INFO", "handlers": ["console"]},
    "loggers": {
        "gunicorn.error": {
            "level": "INFO",
            "handlers": ["console"],
            "propagate": False,
        },
    },
}
