# Лабораторная работа №4 - SLA критического пути под перегрузкой

## Часть 0 - Сервис batch
Создадим сервис `batch` для моделирования работы компоненты аналитики. Нагрузка сервиса настраивается через параметры окружения:
- `BATCH_CPU_THREADS` - количество занимаемых ядер
- `BATCH_MEMORY_MEGABYTES` - количество удерживаемых Мегабайт

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


