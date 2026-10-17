# Same Alpine version in both stages: the Python in /usr/local links against its libs
FROM python:3.15-alpine3.24 AS build

COPY requirements.txt .
# The runtime needs no pip (it vendors urllib3/msgpack/setuptools, CVEs in the Harbor scan),
# headers, IDLE or tkinter. The .so libs Python needs come from the base image's own list.
RUN pip install --no-cache-dir -r requirements.txt && \
  python -m pip uninstall -y pip && \
  cd /usr/local && \
  rm -rf include bin/idle* bin/pydoc* bin/python*-config \
    lib/python3.15/ensurepip lib/python3.15/idlelib \
    lib/python3.15/tkinter lib/python3.15/turtledemo \
    lib/python3.15/lib-dynload/*_test* lib/python3.15/lib-dynload/_xxtestfuzz* \
    lib/python3.15/lib-dynload/_tkinter* && \
  apk info -R .python-rundeps | grep '^so:' > /python-rundeps

# Plain Alpine + /usr/local: files deleted above do not linger in base image layers
FROM alpine:3.24

COPY --from=build /python-rundeps /tmp/
RUN apk upgrade --no-cache && \
  apk add --no-cache ca-certificates tzdata $(cat /tmp/python-rundeps) && \
  rm /tmp/python-rundeps

COPY --from=build /usr/local /usr/local

WORKDIR /app
COPY wsgi.py config.py gunicorn.conf.py ./
COPY application/ application/

USER nobody
EXPOSE 5000

CMD [ "gunicorn", "--config", "gunicorn.conf.py", "wsgi:app" ]
