# Лабораторная работа №3 - Собери платформу для shop

## Часть 0 - Сервисы
Реализация сервисов `api` и `worker` представлена соответственно в директориях [api](api) и [worker](worker). Скрипты накатки схемы данных представлены в [migrations](migrations). В директории [install](install) представлены конфигурационные файлы для развертывания PostgreSQL, схемы данных, а также приложений `api` и `worker`.

В сервисе `api` доступны следующие endpoint-ы:
- `GET /health` - возвращает `ok`, но при выставленной переменной окружения `HEALTH_FAIL=true` отвечает 500
- `POST /order` — создает новый заказ в `orders` с переданным `description` 
- `GET /orders` — возвращает список всех существующих заказов: новых, обработанных и прочее

В сервисе `worker` доступны следующие endpoint-ы:
- `GET /health` - возвращает `ok`
- `POST /orders/{orderNumber}/process` - переводит заказ с номером `orderNumber` в статус "обработан"

Схема данных имеет следующее строение:
- таблица `orders` с параметрами:
  * `order_number` - номер заказа
  * `description` - описание заказа
  * `status` - статус заказа: 0 - заказ создан; 1 - заказ обработан
  * `created_at` - дата и время создания заказа
  * `processed_at` - дата и время обработки заказа

Для дальнейшей работы необходимо предустановить в кластер оператор БД `CloudNativePg`:
- `helm repo add cnpg https://cloudnative-pg.github.io/charts` - добавление репозитория
- `helm repo update` - обновление репозитория
- `helm upgrade --install cnpg cnpg/cloudnative-pg --namespace cnpg-system --create-namespace` - установка оператора БД
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm repo add cnpg https://cloudnative-pg.github.io/charts
"cnpg" has been added to your repositories
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm repo update
Hang tight while we grab the latest from your chart repositories...
...Successfully got an update from the "jaegertracing" chart repository
...Successfully got an update from the "cnpg" chart repository
...Successfully got an update from the "grafana-community" chart repository
...Successfully got an update from the "grafana" chart repository
...Successfully got an update from the "prometheus-community" chart repository
Update Complete. ⎈Happy Helming!⎈

pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm upgrade --install cnpg \ 
  cnpg/cloudnative-pg \
  --namespace cnpg-system \
  --create-namespace
Release "cnpg" does not exist. Installing it now.
NAME: cnpg
LAST DEPLOYED: Sun Sep 27 19:47:56 2026
NAMESPACE: cnpg-system
STATUS: deployed
REVISION: 1
TEST SUITE: None
NOTES:
CloudNativePG operator should be installed in namespace "cnpg-system".
You can now create a PostgreSQL cluster with 3 nodes as follows:

cat <<EOF | kubectl apply -f -
# Example of PostgreSQL cluster
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: cluster-example
  
spec:
  instances: 3
  storage:
    size: 1Gi
EOF

kubectl get -A cluster
```
</details>

Для управления структурой схемы данных воспользуемся инструментом Flyway. Миграционные скрипты базы данных хранятся в [migrations/sql](migrations/sql), на основе которых создается отдельный Docker-образ (см. [migrations/Dockerfile](migrations/Dockerfile)). При установке или обновлении Helm-релиза запускается `Job` [install/templates/schema-migration-job.yaml](install/templates/schema-migration-job.yaml), которая в свою очередь запускает `Flyway`, вносящий изменения в структуру базы данных. `Flyway` подключается к `PostgreSQL` по адресу `FLYWAY_URL` с параметрами авторизации из `FLYWAY_USER`/`FLYWAY_PASSWORD`. В `PostgreSQL` создана схема `flyway_schema_history`, в которой хранится информация о примененных скриптах миграции, таким образом `Job` применяет ровно тот набор скриптов, который еще не применен к базе данных.

Соберем docker-образ скриптов миграции схемы и импортируем его в `minikube`:
- `cd lab3 && docker build -f ./migrations/Dockerfile -t lab3-migration:1.0 ./migrations` - собираем docker-образ
- `helm upgrade --install shop ./lab3/install --namespace lab3 --create-namespace --wait --timeout 10m` - обновление образа приложения

<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab3$ docker build -f ./migrations/Dockerfile -t lab3-migration:1.0 ./migrations
[+] Building 131.7s (7/7) FINISHED                                                                                                                  docker:default
 => [internal] load build definition from Dockerfile                                                                                                          0.1s
 => => transferring dockerfile: 128B                                                                                                                          0.0s
 => [internal] load metadata for docker.io/flyway/flyway:13.7-alpine                                                                                          2.6s
 => [internal] load .dockerignore                                                                                                                             0.1s
 => => transferring context: 56B                                                                                                                              0.0s
 => [internal] load build context                                                                                                                             0.1s
 => => transferring context: 458B                                                                                                                             0.0s
 => [1/2] FROM docker.io/flyway/flyway:13.7-alpine@sha256:93e407c40df8a7d46c3c331ee22a79b4778d03ef6fffd49743b85c136f710ea2                                  125.8s
 => => resolve docker.io/flyway/flyway:13.7-alpine@sha256:93e407c40df8a7d46c3c331ee22a79b4778d03ef6fffd49743b85c136f710ea2                                    0.1s
 => => sha256:d44f403c2ecfc6749db0c4ec5819af8cbce9d4a1deeba87608142cdc941b60cb 323.92MB / 323.92MB                                                          123.7s
 => => sha256:6aa5f812d8b635007ddbc0fb94b92f2b7f1ea2252cea5e4941ec06281d587271 96B / 96B                                                                      1.4s
 => => sha256:99cb2eba0fea5b20ec89e3e29867448ba46f562f417f5b6370bea37c3f3d701a 852.37kB / 852.37kB                                                            2.4s
 => => sha256:51c06ae8de15c24740d0ff89711760d6b1005548314337bf6f669c586ce079f3 2.46kB / 2.46kB                                                                2.1s
 => => sha256:ae042fea25ae1b61992570e8acb8e2db0edbe2f6ae17f09ea59d15a858c89851 127B / 127B                                                                    0.7s
 => => sha256:e8a1250704614a551e41ca03d4485964b9783a54f0467ef85d8b2fc340659d7c 62.12MB / 62.12MB                                                             44.1s
 => => sha256:c76ddb39a6b74360783352e38026832cbd501c34a637da62a4b40826ebbc5748 9.50MB / 9.50MB                                                                8.7s
 => => sha256:55afa1ecc21d2bb5e5045f32dafee56272ffd89860bac26f6c32123439af26a4 3.85MB / 3.85MB                                                                5.0s
 => => extracting sha256:55afa1ecc21d2bb5e5045f32dafee56272ffd89860bac26f6c32123439af26a4                                                                     0.1s
 => => extracting sha256:c76ddb39a6b74360783352e38026832cbd501c34a637da62a4b40826ebbc5748                                                                     0.3s
 => => extracting sha256:e8a1250704614a551e41ca03d4485964b9783a54f0467ef85d8b2fc340659d7c                                                                     1.0s
 => => extracting sha256:ae042fea25ae1b61992570e8acb8e2db0edbe2f6ae17f09ea59d15a858c89851                                                                     0.1s
 => => extracting sha256:51c06ae8de15c24740d0ff89711760d6b1005548314337bf6f669c586ce079f3                                                                     0.0s
 => => extracting sha256:99cb2eba0fea5b20ec89e3e29867448ba46f562f417f5b6370bea37c3f3d701a                                                                     0.1s
 => => extracting sha256:6aa5f812d8b635007ddbc0fb94b92f2b7f1ea2252cea5e4941ec06281d587271                                                                     0.0s
 => => extracting sha256:d44f403c2ecfc6749db0c4ec5819af8cbce9d4a1deeba87608142cdc941b60cb                                                                     1.5s
 => [2/2] COPY sql/ /flyway/sql/                                                                                                                              2.3s
 => exporting to image                                                                                                                                        0.6s
 => => exporting layers                                                                                                                                       0.3s
 => => exporting manifest sha256:7c36ac516966286596498551b1ad2b047c21796537ecf7fb3bbb82b17d119f94                                                             0.0s
 => => exporting config sha256:fb2389ccd16b79b93af9d48af07b6729e633df5a54835e9d7f83d72b2391b4cd                                                               0.0s
 => => exporting attestation manifest sha256:0d4ad240c33fb4e1aeb710eadeb25732fa70d96fac13ba356fb5de4c063d221f                                                 0.0s
 => => exporting manifest list sha256:988892ec29a19f8142d0aeecfc6d151cb0eba2bdeb6b29f064d654cd7a8a5d66                                                        0.0s
 => => naming to docker.io/library/lab3-migration:1.0                                                                                                         0.0s
 => => unpacking to docker.io/library/lab3-migration:1.0                                                                                                      0.0s
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab3$ minikube image load lab3-migration:1.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm upgrade --install shop ./lab3/install \
  --namespace lab3 \
  --create-namespace \
  --wait \
  --timeout 10m
Release "shop" does not exist. Installing it now.
NAME: shop
LAST DEPLOYED: Sun Sep 27 20:46:47 2026
NAMESPACE: lab3
STATUS: deployed
REVISION: 1
TEST SUITE: None
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm list -n lab3
NAME	NAMESPACE	REVISION	UPDATED                                	STATUS  	CHART     	APP VERSION
shop	lab3     	2       	2026-09-27 21:27:02.321763012 +0300 +03	deployed	shop-0.1.0	1.0        
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ kubectl get cluster,pods,pvc,svc,secrets -n lab3
NAME                                       AGE     INSTANCES   READY   STATUS                     PRIMARY
cluster.postgresql.cnpg.io/shop-postgres   3h13m   1           1       Cluster in healthy state   shop-postgres-1

NAME                                  READY   STATUS      RESTARTS   AGE
pod/shop-postgres-1                   1/1     Running     0          3h11m
pod/shop-schema-migration-1-0-dhfcs   0/1     Completed   0          153m

NAME                                    STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
persistentvolumeclaim/shop-postgres-1   Bound    pvc-71d6826c-8bcb-4492-8024-25766be5df1b   1Gi        RWO            standard       <unset>                 3h13m

NAME                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
service/shop-postgres-r    ClusterIP   10.110.62.221   <none>        5432/TCP   3h13m
service/shop-postgres-ro   ClusterIP   10.101.26.6     <none>        5432/TCP   3h13m
service/shop-postgres-rw   ClusterIP   10.104.177.2    <none>        5432/TCP   3h13m

NAME                                TYPE                       DATA   AGE
secret/sh.helm.release.v1.shop.v1   helm.sh/release.v1         1      3h13m
secret/sh.helm.release.v1.shop.v2   helm.sh/release.v1         1      153m
secret/shop-postgres-app            kubernetes.io/basic-auth   11     3h13m
secret/shop-postgres-ca             Opaque                     2      3h13m
secret/shop-postgres-replication    kubernetes.io/tls          2      3h13m
secret/shop-postgres-server         kubernetes.io/tls          2      3h13m
```
</details>

Проверим существование таблицы `orders` на схеме `shop`:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ kubectl exec -n lab3 shop-postgres-1 -- \
  psql -d shop -tAc \
  "SELECT to_regclass('public.orders') IS NOT NULL;"
Defaulted container "postgres" out of: postgres, bootstrap-controller (init)
t
```
Таблица успешно создана. Для подключения к базе через PGAdmin получим логин/пароль схемы с помощью команд:
- `kubectl get secret shop-postgres-app --namespace lab3 --output jsonpath='{.data.username}' | base64 --decode`
- `kubectl get secret shop-postgres-app --namespace lab3 --output jsonpath='{.data.password}' | base64 --decode`
- `kubectl port-forward --namespace lab3 service/shop-postgres-rw 5433:5432`

Теперь создадим Deployment и Service конфигурации для приложений `api` и `worker` (см. подробнее [install](install)). Конфигурация Deployment [api-deployment.yaml](install/templates/api-deployment.yaml) приложения `api` и [worker-deployment.yaml](install/templates/worker-deployment.yaml) приложения `worker` содержит секцию `initContainers`; данная секция необходима для проверки окончания проведения миграции схемы данных ДО запуска приложения.

Пусть запросы на реплики `api` будут идти на порт `30082`, запросы на реплики `worker` - на порт `30083`.

Соберем docker-образы `api` и `worker`, импортируем их в `minikube` и обновим релиз:
<details>
<summary>Результат</summary>


```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ 
docker build \
  --file lab3/api/Dockerfile \
  --tag lab3-api:1.0 \
  lab3/api
[+] Building 168.5s (16/16) FINISHED                                                                                docker:default
 => [internal] load build definition from Dockerfile                                                                          0.1s
 => => transferring dockerfile: 406B                                                                                          0.0s
 => [internal] load metadata for docker.io/library/eclipse-temurin:21-jre-jammy                                               1.8s
 => [internal] load metadata for docker.io/library/maven:3.9-eclipse-temurin-21                                               1.8s
 => [internal] load .dockerignore                                                                                             0.1s
 => => transferring context: 59B                                                                                              0.0s
 => [build 1/6] FROM docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a  0.1s
 => => resolve docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b51  0.1s
 => [internal] load build context                                                                                             0.1s
 => => transferring context: 7.82kB                                                                                           0.0s
 => [stage-1 1/4] FROM docker.io/library/eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813  0.1s
 => => resolve docker.io/library/eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf412  0.1s
 => CACHED [build 2/6] WORKDIR /app                                                                                           0.0s
 => [build 3/6] COPY pom.xml .                                                                                                0.1s
 => [build 4/6] RUN mvn dependency:go-offline                                                                               158.8s
 => [build 5/6] COPY src ./src                                                                                                0.2s 
 => [build 6/6] RUN mvn clean package -DskipTests                                                                             4.2s 
 => CACHED [stage-1 2/4] WORKDIR /app                                                                                         0.0s 
 => CACHED [stage-1 3/4] RUN useradd --system --uid 10001 appuser                                                             0.0s 
 => [stage-1 4/4] COPY --from=build /app/target/lab3-api-1.0.0.jar app.jar                                                    0.4s 
 => exporting to image                                                                                                        2.3s 
 => => exporting layers                                                                                                       1.7s 
 => => exporting manifest sha256:8170bec052d0e372759318c14bcb6a364c632d77616a365d8585bd34e4ce17fe                             0.0s
 => => exporting config sha256:9374a8419d4a850aa1b3f06118224929179ce2b78bb3b585b8b9c0a6243a8cf5                               0.0s
 => => exporting attestation manifest sha256:8ee0f6ee7b9cdce9bbe4f37b337f1fc5cc73c97c18ea5b0f4479aeab0c3ac537                 0.1s
 => => exporting manifest list sha256:c555d91ea80edaef8ed98fab6bd0f79a3b7c17977f535823e8e802637b76c6aa                        0.0s
 => => naming to docker.io/library/lab3-api:1.0                                                                               0.0s
 => => unpacking to docker.io/library/lab3-api:1.0                                                                            0.3s
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ docker build \
  --file lab3/worker/Dockerfile \
  --tag lab3-worker:1.0 \
  lab3/worker
[+] Building 210.3s (16/16) FINISHED                                                                                docker:default
 => [internal] load build definition from Dockerfile                                                                          0.1s
 => => transferring dockerfile: 397B                                                                                          0.0s
 => [internal] load metadata for docker.io/library/eclipse-temurin:21-jre-jammy                                               1.0s
 => [internal] load metadata for docker.io/library/maven:3.9-eclipse-temurin-21                                               0.9s
 => [internal] load .dockerignore                                                                                             0.1s
 => => transferring context: 59B                                                                                              0.0s
 => [build 1/6] FROM docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a  0.1s
 => => resolve docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b51  0.1s
 => [stage-1 1/4] FROM docker.io/library/eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813  0.1s
 => => resolve docker.io/library/eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf412  0.1s
 => [internal] load build context                                                                                             0.1s
 => => transferring context: 8.11kB                                                                                           0.0s
 => CACHED [build 2/6] WORKDIR /app                                                                                           0.0s
 => [build 3/6] COPY pom.xml .                                                                                                0.6s
 => [build 4/6] RUN mvn dependency:go-offline                                                                               200.8s
 => [build 5/6] COPY src ./src                                                                                                0.2s 
 => [build 6/6] RUN mvn clean package                                                                                         4.3s 
 => CACHED [stage-1 2/4] WORKDIR /app                                                                                         0.0s 
 => CACHED [stage-1 3/4] RUN useradd --system --uid 10001 appuser                                                             0.0s 
 => [stage-1 4/4] COPY --from=build /app/target/lab3-worker-1.0.0.jar app.jar                                                 0.5s 
 => exporting to image                                                                                                        2.1s 
 => => exporting layers                                                                                                       1.7s 
 => => exporting manifest sha256:732cf296c8f1cb8f3550b5a746ff52b70e7bff4fd66f5cdc4aea39a256769835                             0.0s
 => => exporting config sha256:40996619122d4112dacc992e6dd5851361064db4ae803950c0dca591feb39438                               0.0s
 => => exporting attestation manifest sha256:b961eafb7dccfd4a02534557bc581be479c799eaba795c0d5693e4a0da04895f                 0.1s
 => => exporting manifest list sha256:39ca1638d673b9f58f35a800ca0c231dabdcf04afabfa1286509fdcb6534c10b                        0.0s
 => => naming to docker.io/library/lab3-worker:1.0                                                                            0.0s
 => => unpacking to docker.io/library/lab3-worker:1.0                                                                         0.2s
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ minikube image load lab3-api:1.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ minikube image load lab3-worker:1.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ minikube image load lab3-migration:1.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$  helm upgrade --install shop ./lab3/install \
  --namespace lab3 \
  --create-namespace \
  --wait \
  --wait-for-jobs \
  --timeout 10m
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Tue Sep 29 23:06:29 2026
NAMESPACE: lab3
STATUS: deployed
REVISION: 3
TEST SUITE: None
```
</details>

Проверим статус Pod-ов и сервисов, а также отправим запрос `GET /health` и проверим работоспособность приложения:
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ kubectl get clusters,pods,jobs,services,pvc -n lab3
NAME                                       AGE    INSTANCES   READY   STATUS                     PRIMARY
cluster.postgresql.cnpg.io/shop-postgres   2d2h   1           1       Cluster in healthy state   shop-postgres-1

NAME                                  READY   STATUS      RESTARTS      AGE
pod/shop-api-59746975c8-4m4x6         1/1     Running     0             47s
pod/shop-api-59746975c8-9wqgq         1/1     Running     0             47s
pod/shop-api-59746975c8-sfgw5         1/1     Running     0             47s
pod/shop-postgres-1                   1/1     Running     1 (12m ago)   2d2h
pod/shop-schema-migration-1-0-dhfcs   0/1     Completed   0             2d1h
pod/shop-worker-848c4d6668-gmnp6      1/1     Running     0             47s
pod/shop-worker-848c4d6668-zkftd      1/1     Running     0             47s

NAME                                  STATUS     COMPLETIONS   DURATION   AGE
job.batch/shop-schema-migration-1-0   Complete   1/1           13s        2d1h

NAME                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)          AGE
service/shop-api           NodePort    10.105.6.87     <none>        8080:30082/TCP   47s
service/shop-postgres-r    ClusterIP   10.110.62.221   <none>        5432/TCP         2d2h
service/shop-postgres-ro   ClusterIP   10.101.26.6     <none>        5432/TCP         2d2h
service/shop-postgres-rw   ClusterIP   10.104.177.2    <none>        5432/TCP         2d2h
service/shop-worker        NodePort    10.111.198.83   <none>        8080:30083/TCP   47s

NAME                                    STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
persistentvolumeclaim/shop-postgres-1   Bound    pvc-71d6826c-8bcb-4492-8024-25766be5df1b   1Gi        RWO            standard       <unset>                 2d2h
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ minikube service shop-api --namespace lab3 --url
http://192.168.49.2:30082
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl http://192.168.49.2:30082/health
ok
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl http://192.168.49.2:30083/health
ok
```
</details>

Проверим, что приложение `api` создает заказы, а `worker` корректно их обрабатывает:
- получим все существующие заказы - `curl http://192.168.49.2:30082/orders`
- создадим новый заказ - `curl -X POST http://192.168.49.2:30082/order -H "Content-Type: application/json" -d '{"description": "заказ 1"}'`
- получим список существующих заказов - `curl http://192.168.49.2:30082/orders`
- обработаем созданный заказ - `curl -X POST http://192.168.49.2:30083/orders/1/process`
- получим обновленный список заказов - `curl http://192.168.49.2:30082/orders`

<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl http://192.168.49.2:30082/orders
[]
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl -X POST http://192.168.49.2:30082/order -H "Content-Type: application/json" -d '{"description": "заказ 1"}'
{"orderNumber":1,"description":"заказ 1","status":0,"createdAt":"2026-09-29T20:25:30.374206Z","processedAt":null}
ona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl http://192.168.49.2:30082/orders
[{"orderNumber":1,"description":"заказ 1","status":0,"createdAt":"2026-09-29T20:25:30.374206Z","processedAt":null}]
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl -X POST http://192.168.49.2:30083/orders/1/process
{"orderNumber":1,"description":"заказ 1","status":1,"createdAt":"2026-09-29T20:25:30.374206Z","processedAt":"2026-09-29T20:26:35.99847Z"}
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl http://192.168.49.2:30082/orders
[{"orderNumber":1,"description":"заказ 1","status":1,"createdAt":"2026-09-29T20:25:30.374206Z","processedAt":"2026-09-29T20:26:35.99847Z"}]
```

![images/part0_ordersPG.png](images/part0_ordersPG.png)
</details>

Таким образом приложение и база данных корректно работают в Kubernetes.