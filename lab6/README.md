# Лабораторная работа №6 - Автопилот релиза: катит хорошее, откатывает плохое

## Часть 0 - Сервисы

Для лабораторной работы было расширено Java-приложение `api` из Лабы 3. Исходный код реализован в [api](api), чтобы сохранить работоспособность предыдущих лабораторных.

Основная задача - подготовить две версии `api` для последующего канареечного развертывания, а также реализовать управляемую генерацию ошибок для проверки надежности service mesh и автоматического отката релиза.

В приложении сохранены существующие endpoints:

- `GET /health` - проверка состояния приложения;
- `POST /order` - создание заказа в PostgreSQL;
- `GET /orders` - получение списка заказов.

Дополнительно реализованы:

- `GET /version` - возвращает текущую версию `v1` или `v2`;
- `FAIL=true` - заставляет `v2` возвращать HTTP 500 при создании заказа;
- `FLAKY=true` - заставляет `v1` случайным образом возвращать HTTP 500 с вероятностью 30%.

### Реализация версий API

Для определения версии приложения создан контроллер [VersionController.java](api/src/main/java/org/panini/controllers/VersionController.java).

Версия задается через переменную окружения `API_VERSION`.

В [application.yaml](api/src/main/resources/application.yaml) добавлены настройки:

```yaml
api:
  version: ${API_VERSION:v1}
  fail: ${FAIL:false}
  flaky: ${FLAKY:false}
```

Если переменные окружения не заданы, приложение запускается как `v1`, а оба переключателя ошибок выключены.

Таким образом, для запуска разных версий не требуется изменять исходный код приложения.

Соберем приложение:

```bash
cd lab6/api
mvn clean package -DskipTests
```

Сборка завершилась успешно:

```text
BUILD SUCCESS
```

Запустим первую версию:

```bash
API_VERSION=v1 java -jar target/lab3-api-1.0.0.jar
```

Проверим endpoint:

```bash
curl -i http://localhost:8080/version
```

Результат:

```text
HTTP/1.1 200
v1
```

После остановки приложения запустим вторую версию:

```bash
API_VERSION=v2 java -jar target/lab3-api-1.0.0.jar
```

Результат запроса `GET /version`:

```text
HTTP/1.1 200
v2
```

Таким образом, обе версии приложения успешно запускаются и возвращают соответствующее значение через `GET /version`.

### Реализация FAIL=true

В [OrderController.java](api/src/main/java/org/panini/controllers/OrderController.java) добавлена проверка версии приложения и переключателя `FAIL`.

Если приложение запущено с `API_VERSION=v2` и `FAIL=true`, метод `POST /order` принудительно возвращает HTTP 500.

```java
if ("v2".equals(version) && fail) {
    throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "v2 forced failure"
    );
}
```

Ошибка возникает до обращения к PostgreSQL, поэтому такой запрос не создает заказ в базе данных.

Запустим приложение:

```bash
API_VERSION=v2 FAIL=true java -jar target/lab3-api-1.0.0.jar
```

Отправим запрос:

```bash
curl -i -X POST http://localhost:8080/order \
  -H "Content-Type: application/json" \
  -d '{"description":"test order"}'
```

Получен ответ:

```text
HTTP/1.1 500
```

Таким образом, переключатель `FAIL=true` работает корректно и позволяет имитировать неудачный релиз `v2`.

### Реализация FLAKY=true

Для версии `v1` реализована случайная генерация ошибок с помощью `ThreadLocalRandom`.

```java
if ("v1".equals(version) && flaky
        && ThreadLocalRandom.current().nextDouble() < 0.3) {
    throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "v1 random failure"
    );
}
```

При каждом запросе `POST /order` генерируется случайное число от 0 до 1. Если полученное значение меньше `0.3`, возвращается HTTP 500. В остальных случаях выполняется обычное создание заказа.

Вероятность ошибки составляет 30%.

Для проверки использовался отдельный контейнер PostgreSQL 16 Alpine, запущенный без Kubernetes:

```bash
docker run -d \
  --name lab6-postgres \
  --rm \
  -e POSTGRES_DB=shop \
  -e POSTGRES_USER=shop \
  -e POSTGRES_PASSWORD=shop \
  -p 127.0.0.1:5432:5432 \
  postgres:16-alpine
```

Таблица `orders` была создана с использованием существующей SQL-миграции из Лабы 4:

```bash
docker exec -i lab6-postgres \
  psql -v ON_ERROR_STOP=1 -U shop -d shop \
  < lab4/migrations/sql/V1__create_orders.sql
```

После подготовки PostgreSQL запустим приложение:

```bash
API_VERSION=v1 FLAKY=true java -jar target/lab3-api-1.0.0.jar
```

Отправим 20 запросов `POST /order`:

```bash
for i in {1..20}; do
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST http://localhost:8080/order \
    -H "Content-Type: application/json" \
    -d '{"description":"test order"}')
  echo "Request $i: HTTP $code"
done
```

Получены следующие результаты:

| Результат | Количество | Доля |
|---|---:|---:|
| HTTP 201 | 13 | 65% |
| HTTP 500 | 7 | 35% |
| Всего | 20 | 100% |

Из 20 запросов 7 завершились ошибкой HTTP 500, остальные 13 успешно создали заказы.

Фактическая доля ошибок составила 35%, что согласуется с заданной вероятностью 30% с учетом случайного характера генерации и небольшого количества запросов.

<details>
<summary>Результат проверки FLAKY=true</summary>

![Случайные ошибки API v1](images/part0-flaky-requests.png)

</details>

Дополнительно проверим существующие endpoints:

```bash
curl -i http://localhost:8080/health
curl -s http://localhost:8080/orders | python3 -m json.tool
```

`GET /health` возвращает HTTP 200 и `ok`.

`GET /orders` возвращает список из 13 заказов, соответствующих успешным POST-запросам.

Таким образом, случайные ошибки генерируются до записи в PostgreSQL, а успешные запросы корректно сохраняют данные.

### Сборка и проверка Docker-образа

Для приложения используется многоэтапный [Dockerfile](api/Dockerfile):

- на первом этапе Maven собирает JAR-файл приложения;
- на втором этапе приложение переносится в образ с Java 21 JRE;
- приложение запускается от непривилегированного пользователя.

Соберем Docker-образ:

```bash
cd lab6/api
docker build -t shop-api:lab6 .
```

Результат:

```text
Successfully built 1e65805c1199
Successfully tagged shop-api:lab6
```

Проверим работу приложения внутри контейнера с включенным `FAIL=true`:

```bash
docker run -d --rm \
  --name lab6-api-test \
  -p 127.0.0.1:8081:8080 \
  -e API_VERSION=v2 \
  -e FAIL=true \
  shop-api:lab6
```

Проверим версию:

```bash
curl -i http://localhost:8081/version
```

Результат:

```text
HTTP/1.1 200
v2
```

Отправим запрос на создание заказа:

```bash
curl -i -X POST http://localhost:8081/order \
  -H "Content-Type: application/json" \
  -d '{"description":"docker test"}'
```

Результат:

```text
HTTP/1.1 500
```

<details>
<summary>Результат проверки Docker-образа</summary>

![Docker API v2 с FAIL=true](images/part0-docker-v2-fail.png)

</details>

Таким образом, приложение корректно работает внутри Docker-контейнера, а его версия и режим генерации ошибок управляются переменными окружения.


## Часть 1 - Выбор service mesh

Для лабораторной работы выбран service mesh `Linkerd`.

В качестве вариантов рассматривались `Linkerd` и `Istio ambient`. Оба решения поддерживают шифрование трафика через mTLS, управление HTTP-маршрутизацией и механизмы повышения надежности приложений.

Для данной работы выбран `Linkerd` по следующим причинам:

- Linkerd использует легковесные proxy, написанные на Rust, что позволяет уменьшить нагрузку на ресурсы небольшого Kubernetes-кластера;
- автоматически обеспечивает mTLS между сервисами, подключенными к mesh, с проверкой идентичности через сертификаты;
- поддерживает HTTP retries и timeouts для обработки временных ошибок без изменения кода приложения;
- позволяет распределять трафик между версиями приложения через `HTTPRoute` с указанием весов `backendRefs`;
- предоставляет Prometheus-метрики запросов, ошибок, задержек и работы proxy;
- поддерживает подключение отдельных приложений к mesh без необходимости изменять все сервисы кластера.

`Istio ambient` позволяет обходиться без sidecar-proxy в Pod-ах приложений. Однако для необходимых в этой лабораторной работе функций уровня HTTP, включая retries и traffic split, требуется дополнительный компонент `waypoint proxy`.

Для небольшого кластера и ограниченных ресурсов виртуальной машины выбран Linkerd как более простой вариант с необходимой функциональностью.

Для реализации лабораторной работы планируется использовать:

- `Linkerd` - service mesh;
- `mTLS` - шифрование и аутентификация соединений;
- `HTTPRoute` - распределение трафика между API v1 и v2;
- `retries` - повторные запросы при временных ошибках;
- `Prometheus` и `Grafana` из Лабы 2 - сбор и отображение метрик.

Начальное распределение трафика будет составлять 90% для `v1` и 10% для `v2`. В дальнейшем веса будут автоматически изменяться скриптом-рейт на основании показателей ошибок и задержек.

Поскольку Linkerd 2.20 официально поддерживает Kubernetes до версии 1.35, для работы планируется использовать отдельный однодовый kind-кластер с совместимой версией Kubernetes.

В Части 3 будет выполнена установка Linkerd, подключение API к mesh через настройки Helm-chart и проверка mTLS. Для включения proxy-injection будут использоваться аннотации Pod-ов, задаваемые через `values.yaml`.

## Часть 2 - Замерь baseline
Настроим дашборд для нагрузочного тестирования со следующими метриками:
- `POST /order p95 latency`
- `CPU usage` - использования CPU в разрезе каждого пода `api`
- `Memory working set` - использование памяти в разрезе каждого пода `api`

В пункте 8 в последние две метрики будет добавлена величина занимаемых ресурсов со стороны прокси.

Запустим нагрузку 100rps и снимем графики измеряемых величин за время тестирования:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ hey \
  -z 15m \
  -c 50 \
  -q 2 \
  -m POST \
  -T application/json \
  -d '{"description":"lab6-load-test"}' \
  "http://${API_IP}:30082/order"
```
<details>
<summary>Результат</summary>

![images/part2.png](images/part2.png)
</details>
