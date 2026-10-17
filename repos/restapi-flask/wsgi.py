from application import create_app
from application.logs import setup_logging
import os
import socket

DEBUG = os.getenv("FLASK_ENV") == "development"

if DEBUG:
    app = create_app("config.DevConfig")
else:
    app = create_app("config.ProdConfig")

ip_address = socket.gethostbyname(socket.gethostname())

if __name__ == "__main__":
    setup_logging()
    # the Werkzeug debugger allows remote code execution: local dev only
    app.run(debug=DEBUG, host=ip_address, port=os.getenv("PORT", 5000))
