FROM flyway/flyway:13.7-alpine
COPY sql/ /flyway/sql/
CMD ["-connectRetries=60", "migrate"]