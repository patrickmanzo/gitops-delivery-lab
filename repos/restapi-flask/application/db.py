from mongoengine import connect


def init_db(app):
    connect(**app.config["MONGODB_SETTINGS"])
