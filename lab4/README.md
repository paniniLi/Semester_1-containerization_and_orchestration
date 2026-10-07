# Лабораторная работа №4 - SLA критического пути под перегрузкой

## Часть 0 - Сервис batch
Создадим сервис `batch` для моделирования работы компоненты аналитики. Нагрузка сервиса настраивается через параметры окружения:
- `BATCH_CPU_THREADS` - количество занимаемых ядер
- `BATCH_MEMORY_MEGABYTES` - количество удерживаемых Мегабайт

## Часть 1 - Выбор механизма разнесения

Для размещения реплик `api` по разным нодам добавим возможность выбора между двумя механизмами Kubernetes:

- `topologySpreadConstraints`
- `podAntiAffinity`

Выбор механизма задается в `values.yaml`:

```yaml
api:
  spreadMechanism: topologySpreadConstraints
```

В качестве основного механизма выберем `topologySpreadConstraints`.

В лабораторной используются 4 реплики `api` и 2 worker-ноды. `topologySpreadConstraints` позволяет равномерно распределять несколько реплик между ограниченным количеством нод. При четырех репликах ожидаемое распределение между двумя worker-нодами составляет `2 + 2`.

Для `api` зададим следующие параметры:

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app.kubernetes.io/name: shop-api
        app.kubernetes.io/instance: {{ .Release.Name }}
```

`topologyKey: kubernetes.io/hostname` означает, что доменом распределения является отдельная нода Kubernetes.

`maxSkew: 1` ограничивает максимальную разницу в количестве подходящих Pod-ов между нодами единицей.

`whenUnsatisfiable: DoNotSchedule` запрещает размещение нового Pod-а, если его запуск нарушит заданное ограничение распределения.

В качестве альтернативы поддерживается `podAntiAffinity`:

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: shop-api
              app.kubernetes.io/instance: {{ .Release.Name }}
```

Для `podAntiAffinity` используется мягкое правило `preferredDuringSchedulingIgnoredDuringExecution`. Строгий вариант `requiredDuringSchedulingIgnoredDuringExecution` в данном случае не подходит: при 4 репликах `api` и только 2 worker-нодах Kubernetes смог бы разместить не более одной подходящей реплики на каждой ноде, а остальные Pod-ы остались бы в состоянии `Pending`.

Проверим корректность Helm-chart:

```bash
helm lint lab4/install
```

Результат:

```text
1 chart(s) linted, 0 chart(s) failed
```

Проверим рендеринг основного механизма:

```bash
helm template shop lab4/install \
  --set api.spreadMechanism=topologySpreadConstraints |
grep -A12 topologySpreadConstraints
```

Helm формирует блок `topologySpreadConstraints` с `maxSkew: 1` и `topologyKey: kubernetes.io/hostname`.

![Проверка рендеринга topologySpreadConstraints](images/part1-topology-spread.png)

Также проверим переключение на альтернативный механизм:

```bash
helm template shop lab4/install \
  --set api.spreadMechanism=podAntiAffinity |
grep -A15 podAntiAffinity
```

При изменении значения параметра Helm формирует блок `podAntiAffinity`.

![Проверка рендеринга podAntiAffinity](images/part1-pod-antiaffinity.png)

Таким образом, Helm-chart поддерживает оба механизма разнесения, а в качестве основного выбран `topologySpreadConstraints`, поскольку он позволяет равномерно распределить все реплики `api` между небольшим количеством worker-нод.

## Часть 2 - Тесная площадка
Создадим кластер на 3 ноды (см. конфигурацию кластера в [kind.yaml](kind.yaml)):
- нода, на которой будет расположен `control plane` - защитим ее `taint = lab4-control-plane` для того, чтобы на нее не ставились поды приложения
- 2 ноды, на которые будем ставить поды приложения `shop`

Также предустановим в кластер оператор БД CloudNativePG, поскольку в предыдущих лабораторных он устанавливался в `minikube`:
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl -Lo kind https://kind.sigs.k8s.io/dl/v0.33.0/kind-linux-amd64
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100    97  100    97    0     0    441      0 --:--:-- --:--:-- --:--:--   442
  0     0    0     0    0     0      0      0 --:--:-- --:--:-- --:--:--     0
100 10.0M  100 10.0M    0     0   895k      0  0:00:11  0:00:11 --:--:--  915k
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ chmod +x kind
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ sudo mv kind /usr/local/bin/kind
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ kind version
kind v0.33.0 go1.26.7 linux/amd64
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ env \
  -u HTTP_PROXY \
  -u HTTPS_PROXY \
  -u ALL_PROXY \
  -u http_proxy \
  -u https_proxy \
  -u all_proxy \
  -u NO_PROXY \
  -u no_proxy \
  kind create cluster \
    --name lab4 \
    --config ./kind.yaml \
    --wait 5m
Creating cluster "lab4" ...
 ✓ Ensuring node image (kindest/node:v1.37.0) 🖼️
 ✓ Preparing nodes 📦 📦 📦  
 ✓ Writing configuration 📜 
 ✓ Starting control-plane 🕹️ 
 ✓ Installing CNI 🔌 
 ✓ Installing StorageClass 💾 
 ✓ Joining worker nodes 🚜 
 ✓ Waiting ≤ 5m0s for control-plane = Ready ⏳ 
 • Ready after 6s 💚
Set kubectl context to "kind-lab4"
You can now use your cluster with:

kubectl cluster-info --context kind-lab4

Not sure what to do next? 😅  Check out https://kind.sigs.k8s.io/docs/user/quick-start/
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ docker inspect lab4-control-plane \
  --format '{{range .Config.Env}}{{println .}}{{end}}' |
  grep -i proxy
HTTP_PROXY=
HTTPS_PROXY=
NO_PROXY=
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ kubectl taint node lab4-control-plane \
  node-role.kubernetes.io/control-plane=:NoSchedule \
  --overwrite
node/lab4-control-plane modified
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ kubectl get node lab4-control-plane \
  -o jsonpath='{.spec.taints}{"\n"}'
[{"effect":"NoSchedule","key":"node-role.kubernetes.io/control-plane"}]
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ helm upgrade --install cnpg \
  cnpg/cloudnative-pg \
  --namespace cnpg-system \
  --create-namespace \
  --wait \
  --timeout 5m
Release "cnpg" does not exist. Installing it now.
NAME: cnpg
LAST DEPLOYED: Tue Oct  6 00:35:39 2026
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
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ kubectl get pods -n cnpg-system
NAME                                   READY   STATUS    RESTARTS   AGE
cnpg-cloudnative-pg-7b5f5d7b65-9zxv7   1/1     Running   0          26s
```
</details>

Соберем docker-образ `batch` и добавим его в чарт `shop` (конфигурацию `batch` см. [install/templates/batch-deployment.yaml](install/templates/batch-deployment.yaml)):
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ cd lab4 && docker build -f ./batch/Dockerfile -t lab4-batch:1.0 ./batch
[+] Building 80.8s (16/16) FINISHED                              docker:default
 => [internal] load build definition from Dockerfile                       0.1s
 => => transferring dockerfile: 408B                                       0.0s
 => [internal] load metadata for docker.io/library/maven:3.9-eclipse-temu  1.2s
 => [internal] load metadata for docker.io/library/eclipse-temurin:21-jre  1.2s
 => [internal] load .dockerignore                                          0.1s
 => => transferring context: 59B                                           0.0s
 => [build 1/6] FROM docker.io/library/maven:3.9-eclipse-temurin-21@sha25  0.1s
 => => resolve docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e6  0.1s
 => [stage-1 1/4] FROM docker.io/library/eclipse-temurin:21-jre-jammy@sha  0.1s
 => => resolve docker.io/library/eclipse-temurin:21-jre-jammy@sha256:f04f  0.1s
 => [internal] load build context                                          0.1s
 => => transferring context: 7.12kB                                        0.0s
 => CACHED [stage-1 2/4] WORKDIR /app                                      0.0s
 => CACHED [stage-1 3/4] RUN useradd --system --uid 10001 appuser          0.0s
 => CACHED [build 2/6] WORKDIR /app                                        0.0s
 => [build 3/6] COPY pom.xml .                                             0.1s
 => [build 4/6] RUN mvn dependency:go-offline                             71.9s
 => [build 5/6] COPY src ./src                                             0.2s 
 => [build 6/6] RUN mvn clean package -DskipTests                          4.6s 
 => [stage-1 4/4] COPY --from=build /app/target/lab4-batch-1.0.0.jar app.  0.3s 
 => exporting to image                                                     1.7s 
 => => exporting layers                                                    1.3s 
 => => exporting manifest sha256:62f64b9bd5ae711e1f9b2552ca1f48ae695b2d1b  0.0s
 => => exporting config sha256:d81ea57c01dc54999309f5f0fca16b346538b9af73  0.0s
 => => exporting attestation manifest sha256:36e6093a5248ad6b0af0b0fcd421  0.1s
 => => exporting manifest list sha256:abc13e5e5a1572c2b15e3a8a4cc40711348  0.0s
 => => naming to docker.io/library/lab4-batch:1.0                          0.0s
 => => unpacking to docker.io/library/lab4-batch:1.0                       0.2s
```
</details>

Импортируем в kind образы для `shop` и установим приложение в кластер:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ docker image save \
  --platform linux/amd64 \
  --output /tmp/shop-images.tar \
  lab3-api:1.0 \
  lab3-worker:1.0 \
  lab3-migration:1.0 \
  lab4-batch:1.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab4$ kind load image-archive \
  /tmp/shop-images.tar \
  --name lab4
```

## Часть 3 - Нагрузочное тестирование и подбор ресурсов с KRR

Для получения рекомендаций по ресурсам была создана нагрузка на критический путь приложения через `POST /order`.

Для генерации нагрузки использовался `hey`:

```bash
hey \
  -z 5m \
  -c 5 \
  -q 1 \
  -m POST \
  -T application/json \
  -d '{"description":"load-test"}' \
  http://127.0.0.1:8080/order
```

В течение 5 минут было получено около 5 запросов в секунду. Успешно обработано 1485 запросов со статусом `201`.

Для сбора метрик был развернут Prometheus. Проверено наличие метрик CPU и памяти контейнеров в namespace `lab4`.

KRR запускался с использованием Prometheus:

```bash
python krr.py simple \
  -c kind-lab4 \
  -n lab4 \
  -p http://127.0.0.1:9090 \
  --history-duration 1 \
  --timeframe-duration 5 \
  --points-required 3
```

Поскольку Prometheus был развернут непосредственно перед экспериментом, доступная история метрик была короче заданного часа, поэтому для лабораторного запуска порог `points-required` был уменьшен до 3.

Использовалась стратегия Simple:

- CPU request определяется по 95-му перцентилю использования CPU;
- memory request определяется как максимальное использование памяти + 15%;
- CPU limit стратегия KRR не устанавливает.

Рекомендации KRR:

| Компонент | Исходный CPU request | Новый CPU request | Исходная память | Новая память |
|---|---:|---:|---:|---:|
| API | 50m | 28m | 128Mi | 261Mi |
| Worker | 50m | 10m | 128Mi | 228Mi |
| Batch | 500m | 380m | 512Mi | 367Mi |

По результатам KRR первоначальные оценки ресурсов оказались достаточно консервативными по CPU, но заниженными по памяти для API и Worker. Для API CPU request снизился с 50m до 28m, при этом рекомендуемый объем памяти вырос со 128Mi до 261Mi, что связано с фактическим потреблением приложения под нагрузкой. Для Worker CPU request снизился до минимального значения 10m, однако потребление памяти потребовало увеличения request до 228Mi. Для Batch первоначальные значения 500m CPU и 512Mi памяти оказались завышенными: KRR рекомендовал 380m CPU и 367Mi памяти.

PostgreSQL развернут через CloudNativePG и не определяется KRR как стандартный Deployment/StatefulSet. Поэтому его использование ресурсов было получено непосредственно из Prometheus с применением того же подхода:

- CPU 95-й перцентиль: около 17m;
- максимальная память: около 132.5 MiB;
- с буфером 15%: 153Mi.

Для PostgreSQL были установлены:

```text
CPU request: 17m
Memory request: 153Mi
Memory limit: 153Mi
```

После получения рекомендаций значения были внесены в `values.yaml` и применены через:

```bash
helm upgrade --install shop ./lab4/install \
  -n lab4 \
  --wait \
  --wait-for-jobs \
  --timeout 10m
```

CPU limits оставлены равными `500m`, поскольку действующие политики Kubernetes требуют наличия limits, а стратегия KRR Simple не формирует рекомендацию для CPU limit.

После обновления были получены следующие значения:

```text
shop-api     CPU request 28m    memory 261Mi    CPU limit 500m
shop-worker  CPU request 10m    memory 228Mi    CPU limit 500m
shop-batch   CPU request 380m   memory 367Mi    CPU limit 500m
shop-postgres CPU request 17m   memory 153Mi    CPU limit 500m
```

Таким образом, исходные приблизительные значения ресурсов были заменены значениями, полученными на основании фактического потребления под нагрузкой.

![KRR recommendations](images/part3-krr.png)

![Applied resources](images/part3-resources.png)

## Часть 4 - Разнесение критического сервиса

Для сервиса `api` применим выбранный в Части 1 механизм `topologySpreadConstraints`.

В качестве топологии используется имя Kubernetes-ноды:

```yaml
topologyKey: kubernetes.io/hostname
```

Проверим размещение реплик `api` по нодам:

```bash
kubectl get pods -n lab4 \
  -l app.kubernetes.io/name=shop-api \
  -o wide
```

Дополнительно подсчитаем количество реплик на каждой worker-ноде:

```bash
kubectl get pods -n lab4 \
  -l app.kubernetes.io/name=shop-api \
  -o custom-columns='NODE:.spec.nodeName' \
  --no-headers | sort | uniq -c
```

В результате четыре реплики `api` распределились между двумя worker-нодами:

```text
2 lab4-worker
2 lab4-worker2
```

Таким образом, критический сервис не размещается целиком на одной ноде. При отказе одной worker-ноды часть реплик `api` продолжит работать на второй ноде, что повышает отказоустойчивость критического пути приложения.

Во время rolling update возможно временное неравномерное распределение уже запущенных Pod-ов. `topologySpreadConstraints` учитывается при планировании новых Pod-ов, но Kubernetes не выполняет автоматический ребаланс уже размещенных реплик. После пересоздания одной из реплик scheduler восстановил равномерное распределение `2 + 2`.

![Распределение реплик API по worker-нодам](images/part4-api-spread.png)