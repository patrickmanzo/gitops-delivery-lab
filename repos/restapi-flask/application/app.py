from bson.json_util import RELAXED_JSON_OPTIONS
from flask import Response
from flask_restful import Resource, reqparse
from mongoengine import NotUniqueError
from mongoengine.connection import get_db
from opentelemetry.instrumentation.utils import suppress_instrumentation
from .model import UserModel
import os
import re
from datetime import date

BLANK_FIELD = "This field cannot be blank."
USER_NOT_FOUND = "User does not exist in database!"
INVALID_BIRTH_DATE = "Birth date must be YYYY-MM-DD between 1900-01-01 and today."
MIN_BIRTH_DATE = date(1900, 1, 1)


def valid_birth_date(value):
    # outside 1970-9999 Mongo returns {"$numberLong"}, and it makes no sense here
    try:
        return MIN_BIRTH_DATE <= date.fromisoformat(value) <= date.today()
    except ValueError:
        return False


_user_parser = reqparse.RequestParser()
for field in ("first_name", "last_name", "cpf", "email", "birth_date"):
    _user_parser.add_argument(field, type=str, required=True, help=BLANK_FIELD)


def _json(queryset):
    return Response(
        queryset.to_json(json_options=RELAXED_JSON_OPTIONS), mimetype="application/json"
    )


class HealthCheck(Resource):
    def get(self):
        return "Healthy", 200


class Ready(Resource):
    # readiness: only take traffic while MongoDB is reachable (liveness is /health)
    def get(self):
        try:
            # probe every 5s: no Mongo ping span (it would be an orphan trace)
            with suppress_instrumentation():
                get_db().command("ping")
        except Exception:
            return {"status": "unavailable"}, 503
        return {"status": "ready"}, 200


class Version(Resource):
    def get(self):
        return {
            "environment": os.getenv("ENVIRONMENT", "local"),
            "version": os.getenv("APP_VERSION", "local"),
        }


class Users(Resource):
    def get(self):
        return _json(UserModel.objects())


class User(Resource):
    def validate_cpf(self, cpf):
        if not re.match(r"\d{3}\.\d{3}\.\d{3}-\d{2}", cpf):
            return False

        numbers = [int(digit) for digit in cpf if digit.isdigit()]

        if len(numbers) != 11 or len(set(numbers)) == 1:
            return False

        sum_of_products = sum(a * b for a, b in zip(numbers[0:9], range(10, 1, -1)))
        expected_digit = (sum_of_products * 10 % 11) % 10
        if numbers[9] != expected_digit:
            return False

        sum_of_products = sum(a * b for a, b in zip(numbers[0:10], range(11, 1, -1)))
        expected_digit = (sum_of_products * 10 % 11) % 10
        if numbers[10] != expected_digit:
            return False

        return True

    def post(self):
        data = _user_parser.parse_args()

        if not self.validate_cpf(data["cpf"]):
            return {"message": "CPF is invalid!"}, 400

        if not valid_birth_date(data["birth_date"]):
            return {"message": INVALID_BIRTH_DATE}, 400

        try:
            response = UserModel(**data).save()
            return {"message": "User %s successfully created!" % response.id}
        except NotUniqueError:
            return {"message": "CPF already exists in database!"}, 400

    def get(self, cpf):
        response = UserModel.objects(cpf=cpf)

        if response:
            return _json(response)

        return {"message": USER_NOT_FOUND}, 400

    def patch(self):
        data = _user_parser.parse_args()

        if not self.validate_cpf(data["cpf"]):
            return {"message": "CPF is invalid!"}, 400

        if not valid_birth_date(data["birth_date"]):
            return {"message": INVALID_BIRTH_DATE}, 400

        response = UserModel.objects(cpf=data["cpf"])
        if response:
            response.update(**data)
            return {"message": "User updated!"}, 200
        else:
            return {"message": USER_NOT_FOUND}, 400

    def delete(self, cpf):
        response = UserModel.objects(cpf=cpf)

        if response:
            response.delete()
            return {"message": "User deleted!"}, 200
        else:
            return {"message": USER_NOT_FOUND}, 400
