# Лабораторная работа №5 - Zero-trust сеть с доказательством

## Часть 0 - Выбор CNI

Для лабораторной работы выбран CNI-плагин `Cilium`.

Стандартный CNI `kindnet`, используемый в kind по умолчанию, не подходит для этой лабораторной работы, поскольку он не обеспечивает необходимую поддержку Kubernetes NetworkPolicy.

В качестве вариантов рассматривались `Cilium` и `Calico`. Оба CNI поддерживают сетевые политики, однако для данной работы выбран `Cilium` по следующим причинам:

- Cilium использует eBPF для обработки сетевого трафика и применения политик;
- поддерживает стандартные Kubernetes NetworkPolicy;
- предоставляет Hubble для наблюдения за сетевыми соединениями между Pod-ами;
- Hubble будет полезен в Части 6 для поиска намеренно внесенной ошибки связности.

Таким образом, Cilium позволяет не только реализовать zero-trust политики, но и наблюдать фактический сетевой трафик, что соответствует задачам лабораторной работы.

Для проверки Cilium был установлен в кластер kind с отключенным стандартным CNI.

```bash
kubectl get pods -n kube-system | grep -E 'cilium|hubble'
kubectl get nodes
```

На каждой ноде запущен агент Cilium, а обе ноды находятся в состоянии `Ready`. Дополнительно включен `Hubble Relay`, который далее будет использоваться для наблюдения за сетевыми соединениями и диагностики сетевых политик.

![Cilium и Hubble в кластере](images/part0-cilium-ready.png)

## Часть 1 - Развертывание shop и исследование сети Pod-а

Приложение `shop` было развернуто в kind-кластере с Cilium в качестве CNI. В namespace `lab5` запущены сервисы `api`, `worker` и PostgreSQL.

Для исследования сети был выбран один из Pod-ов `api`.

Pod имеет следующие параметры:

```text
Node:   lab5-worker
Pod IP: 10.0.1.6
```

Параметры интерфейса `eth0` были получены через sysfs контейнера:

```bash
kubectl exec -n lab5 "$API_POD" -- cat /sys/class/net/eth0/ifindex
kubectl exec -n lab5 "$API_POD" -- cat /sys/class/net/eth0/iflink
```

Получены следующие значения:

```text
ifindex: 18
iflink:  19
```

Значение `iflink` указывает индекс парного сетевого интерфейса на ноде.

Парный интерфейс на worker-ноде был найден по значению `iflink`:

```bash
docker exec lab5-worker ip -o link | grep '^19:'
```

На ноде `lab5-worker` был найден соответствующий интерфейс:

```text
19: lxc83c8d62f079b@if18
```

Таким образом, интерфейс `eth0` внутри Pod-а и интерфейс `lxc83c8d62f079b` на ноде образуют veth-пару.

Маршрут до IP Pod-а на ноде:

```bash
docker exec lab5-worker ip route get 10.0.1.6
```

Результат:

```text
10.0.1.6 dev cilium_host src 10.0.1.225
```

Это показывает, что обработка трафика Pod-а на стороне ноды выполняется через сетевой datapath Cilium.

![Сеть API Pod-а](images/part1-pod-network.png)

Для проверки прямой связности Pod-ов на разных нодах были созданы два тестовых Pod-а:

```text
net-control   10.0.0.23    lab5-control-plane
net-worker    10.0.1.159   lab5-worker
```

Из Pod-а `net-control` был выполнен `ping` напрямую по Pod IP второго Pod-а:

```text
PING 10.0.1.159
4 packets transmitted, 4 packets received, 0% packet loss
```

Таким образом, Pod-ы, расположенные на разных Kubernetes-нодах, могут напрямую взаимодействовать по Pod IP. Маршрутизацию между ними обеспечивает установленный CNI Cilium.

![Связность Pod-ов между разными нодами](images/part1-cross-node-connectivity.png)