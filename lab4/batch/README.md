# Batch

Заглушка фоновой аналитики для лабораторной работы №4. После запуска сервис
удерживает заданный объём физической памяти и непрерывно загружает CPU.

Параметры нагрузки задаются переменными окружения:

- `BATCH_CPU_THREADS` — число нагружающих CPU потоков, по умолчанию `1`;
- `BATCH_MEMORY_MEGABYTES` — объём удерживаемой памяти в MiB, по умолчанию `128`.

Сервис предоставляет `GET /health` и метрики Prometheus на `GET /metrics`.

## Локальный запуск

```bash
BATCH_CPU_THREADS=1 BATCH_MEMORY_MEGABYTES=128 mvn spring-boot:run
```

## Запуск в Docker

```bash
docker build -t shop-batch:1.0.0 .
docker run --rm \
  --cpus 1 \
  --memory 512m \
  -p 8080:8080 \
  -e BATCH_CPU_THREADS=1 \
  -e BATCH_MEMORY_MEGABYTES=128 \
  shop-batch:1.0.0
```

`BATCH_MEMORY_MEGABYTES` задаёт только полезную нагрузку. Лимит контейнера должен
также оставлять запас под heap JVM, metaspace, стеки потоков и сам Spring Boot.
