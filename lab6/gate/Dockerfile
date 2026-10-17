FROM python:3.12-alpine

WORKDIR /app

RUN addgroup -S -g 10001 gate \
    && adduser -S -D -H -u 10001 -G gate gate

COPY gate.py /app/gate.py

USER 10001:10001

ENTRYPOINT ["python", "-u", "/app/gate.py"]
