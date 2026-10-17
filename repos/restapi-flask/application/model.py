from mongoengine import DateTimeField, Document, EmailField, StringField


class UserModel(Document):
    cpf = StringField(required=True, unique=True)
    first_name = StringField(required=True)
    last_name = StringField(required=True)
    email = EmailField(required=True)
    birth_date = DateTimeField(required=True)


class HealthCheckModel(Document):
    status = StringField(required=True)
