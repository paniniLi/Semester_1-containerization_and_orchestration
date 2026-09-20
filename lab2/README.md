# Лабораторная работа №2 - Мониторинг сервиса: метрики, логи, трейсы

## Часть 0 - Свой сервис

Подготовлен Spring Boot сервис, который используется как основа для дальнейшей настройки мониторинга.

Реализованы HTTP-endpoint'ы:

- `GET /health` - проверка доступности сервиса;
- `GET /fail` - возвращает HTTP 500 и увеличивает счётчик ошибок;
- `GET /slow` - имитирует медленный запрос с задержкой 1–3 секунды;
- `GET /load` - генерирует дополнительные запросы к сервису для создания нагрузки;
- `GET /metrics` - экспортирует метрики в формате Prometheus.

Для сервиса настроены:
- RED-метрики через Micrometer;
- отдельный счётчик ошибок `app_errors_total`;
- structured JSON logs;
- `trace_id` и `spanId` для корреляции логов и трейсов;
- OpenTelemetry tracing;
- Dockerfile для сборки и запуска сервиса в контейнере.

однимем локальный Kubernetes-кластер с помощью `minikube` и установим приложение через `helm`. В запущщеном контейнере будет работать приложение из [src](src):
1. Запустим кластер: `minikube start --driver=docker --cpus=4 --memory=6144`
<details>
<summary>Результат</summary>

```bash
😄  minikube v1.39.0 on Ubuntu 22.04
✨  Using the docker driver based on user configuration
📌  Using Docker driver with root privileges
👍  Starting "minikube" primary control-plane node in "minikube" cluster
🚜  Pulling base image v0.0.51 ...
💾  Downloading Kubernetes v1.37.0 preload ...
    > preloaded-images-k8s-v18-v1...:  347.58 MiB / 347.58 MiB  100.00% 2.44 Miк
    > gcr.io/k8s-minikube/kicbase:  507.60 MiB / 507.61 MiB  100.00% 2.92 MiB p
🔥  Creating docker container (CPUs=4, Memory=6144MB) ...
❗  Local proxy ignored: not passing HTTP_PROXY=http://127.0.0.1:10809/ to docker env.
❗  Local proxy ignored: not passing HTTPS_PROXY=http://127.0.0.1:10809/ to docker env.
❗  Local proxy ignored: not passing HTTP_PROXY=http://127.0.0.1:10809/ to docker env.
❗  Local proxy ignored: not passing HTTPS_PROXY=http://127.0.0.1:10809/ to docker env.
🌐  Found network options:
    ▪ HTTP_PROXY=http://127.0.0.1:10809/
❗  You appear to be using a proxy, but your NO_PROXY environment does not include the minikube IP (192.168.49.2).
📘  Please see https://minikube.sigs.k8s.io/docs/handbook/vpn_and_proxy/ for more details
    ▪ HTTPS_PROXY=http://127.0.0.1:10809/
    ▪ NO_PROXY=localhost,127.0.0.0/8,::1
    ▪ HTTP_PROXY=http://127.0.0.1:10809/
    ▪ HTTPS_PROXY=http://127.0.0.1:10809/
    ▪ NO_PROXY=localhost,127.0.0.0/8,::1
❗  Failing to connect to https://registry.k8s.io/ from inside the minikube container
💡  To pull new external images, you may need to configure a proxy: https://minikube.sigs.k8s.io/docs/reference/networking/proxy/
📦  Preparing Kubernetes v1.37.0 on containerd 2.3.4 ...
    ▪ env NO_PROXY=localhost,127.0.0.0/8,::1
🔗  Configuring CNI (Container Networking Interface) ...
🔎  Verifying Kubernetes components...
    ▪ Using image gcr.io/k8s-minikube/storage-provisioner:v5
🌟  Enabled addons: storage-provisioner, default-storageclass
🏄  Done! kubectl is now configured to use "minikube" cluster and "default" namespace by default
```
</details>

2. Проверим статус кластера:
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$ minikube status
minikube
type: Control Plane
host: Running
kubelet: Running
apiserver: Running
kubeconfig: Configured
```
</details>

3. Проверим статус запущенных нод и подов:
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$ kubectl get nodes
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   56m   v1.37.0
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$ kubectl get pods -A
NAMESPACE     NAME                               READY   STATUS    RESTARTS   AGE
kube-system   coredns-559f6c778d-tmkhc           1/1     Running   0          56m
kube-system   etcd-minikube                      1/1     Running   0          56m
kube-system   kindnet-7k54d                      1/1     Running   0          56m
kube-system   kube-apiserver-minikube            1/1     Running   0          56m
kube-system   kube-controller-manager-minikube   1/1     Running   0          56m
kube-system   kube-proxy-qfpmd                   1/1     Running   0          56m
kube-system   kube-scheduler-minikube            1/1     Running   0          56m
kube-system   storage-provisioner                1/1     Running   0          56m
```
</details>

4. Соберем docker-образ сервиса: `cd lab2 && docker build -f Dockerfile -t lab2-api:1.0 .`
5. Импортируем docker-образ в список доступных образов `minikube`: `minikube image load lab2-api:1.0`
6. Развернем контейнер из образа, созданного в предыдущих пунктах, и проверим работоспособность сервиса:
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$ helm upgrade --install api ./install     --namespace lab2     --create-namespace
Release "api" does not exist. Installing it now.
NAME: api
LAST DEPLOYED: Sun Sep 20 20:58:37 2026
NAMESPACE: lab2
STATUS: deployed
REVISION: 1
TEST SUITE: None
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$  kubectl get service api -n lab2
NAME   TYPE       CLUSTER-IP     EXTERNAL-IP   PORT(S)          AGE
api    NodePort   10.108.72.20   <none>        8080:30080/TCP   13m
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab2$ curl "http://$(minikube ip):30080/health"
ok
```
</details>


## Часть 1 - Метрики (Prometheus + Grafana)

## Часть 2 - Логи (Loki + Grafana)

## Часть 3 - Трейсы (OpenTelemetry + Jaeger)

## Часть 4 - Алерты (Alertmanager + Karma)