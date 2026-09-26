# Лабораторная работа №1 - Свой Docker

## Часть 0 - Сервис

Для выполнения лабораторной работы используется HTTP-сервис на Java 21 с использованием Spring Boot.

Сервис предоставляет следующие эндпоинты:

- `GET /health` - проверка работоспособности приложения;
- `GET /eat?mb=N` - выделение и удержание N МБ оперативной памяти;
- `GET /burn` - создание нагрузки на одно ядро CPU;
- `GET /changeTime` - попытка изменения системного времени для проверки capabilities и seccomp;
- `POST /system/execute` - выполнение системной команды внутри среды приложения.

См. реализацию сервиса в [src](src).

## Часть 1 - Запуск напрямую

На первом этапе сервис запускается напрямую на хостовой системе как обычный Linux-процесс, без контейнерной изоляции и ограничений ресурсов.
Проект был собран с помощью Maven `mvn clean package -DskipTests`. После сборки сервис был запущен командой `./bin/startup.sh`.

### Проверка работоспособности

В браузере был открыт адрес `http://127.0.0.1:8080/health`. Сервис успешно ответил:
<details>
<summary>Результат</summary>

![Проверка эндпоинта health](images/part1-health.png)
</details>

### Процесс на хостовой системе

Процесс приложения был найден командой `pgrep -af 'lab1-1.0.0.jar'`. Для просмотра PID, пользователя и команды запуска использовалась команда `ps -o pid,user,cmd -p $(pgrep -f 'lab1-1.0.0.jar' | head -n1)`.
<details>
<summary>Результат</summary>

![Процесс приложения на хосте](images/part1-process.png)
</details>

На данном этапе приложение является обычным процессом Linux. Оно видно среди остальных процессов хостовой системы, имеет обычный PID и запускается от имени пользователя хоста. Изоляция с помощью namespaces и ограничения ресурсов с помощью cgroups ещё не применяются.

## Часть 2 - Изоляция с помощью namespaces

Namespaces позволяют изолировать различные части окружения процесса. Процесс внутри namespace может видеть другое представление процессов, сети, hostname, пользователей и других ресурсов, хотя физически он продолжает выполняться на том же Linux-ядре.

### 2.1. PID namespace

PID namespace изолирует пространство идентификаторов процессов.

Один и тот же процесс может иметь:
- один PID с точки зрения хостовой системы;
- другой PID внутри созданного PID namespace.

Для создания отдельного PID namespace была выполнена команда `sudo unshare --pid --fork --mount-proc bash`. Параметры команды:
- `sudo` - команда выполняется с повышенными привилегиями;
- `unshare` - создаёт новые namespaces для запускаемого процесса;
- `--pid` - создаёт новый PID namespace;
- `--fork` - запускает дочерний процесс внутри нового namespace;
- `--mount-proc` - монтирует отдельный `/proc`, соответствующий новому PID namespace;
- `bash` - запускает оболочку Bash внутри созданного namespace.

Без `--mount-proc` команда `ps` могла бы продолжить читать старый `/proc` хоста и показывать процессы всей системы, поэтому для корректной демонстрации PID namespace используется отдельный `/proc`.

После входа в новый namespace был проверен PID текущей оболочки `echo $$`, результат - `1`.  Текущий `bash` внутри namespace получил PID `1`.

PID 1 является первым процессом в данном PID namespace. Для процессов внутри этого namespace он играет ту же роль, которую init-процесс играет в обычной Linux-системе.

Затем был просмотрен список доступных процессов `ps -ef`
<details>
<summary>Результат</summary>

```text
UID    PID    PPID    CMD
root     1       0    bash
root     8       1    ps -ef
```
</details>

Внутри namespace видны только процессы, принадлежащие этому PID namespace. Процессы основной Ubuntu в данном списке отсутствуют.
<details>
<summary>Результат</summary>

![Процессы внутри PID namespace](images/part2-pid-namespace.png)
</details>

Для сравнения тот же процесс был найден снаружи namespace, в обычном терминале хостовой системы `ps -o pid,ppid,user,cmd -p 12975`. В данном запуске Bash, который внутри namespace имел PID `1`, на хосте имел PID `12975`.
<details>
<summary>Результат</summary>

![PID процесса на хосте](images/part2-pid-host.png)
</details>

Таким образом, PID процесса не является абсолютным значением для всей системы. Его значение зависит от PID namespace, из которого процесс наблюдается.

Также благодаря отдельному `/proc` процессы внутри namespace не видят полный список процессов хостовой системы.

### 2.2. UTS namespace

UTS namespace изолирует hostname и domain name системы.

Сначала был проверен hostname хостовой системы командой `hostname`, результат - `itmo-containers`

После этого был создан отдельный UTS namespace `sudo unshare --uts bash`. Сразу после создания namespace hostname внутри него совпадал с hostname хоста (с помощью команды `hostname` получаем результат `itmo-containers`)

Затем hostname был изменён только внутри созданного namespace: `hostname lab1-container`. После изменения результат команды `hostname` выводит `lab2-container`.
<details>
<summary>Результат</summary>

![Hostname внутри UTS namespace](images/part2-uts-namespace.png)
</details>

В отдельном терминале на хостовой системе hostname остался прежним: результат команды `hostname` - `itmo-containers`
<details>
<summary>Результат</summary>

![Hostname хоста](images/part2-uts-host.png)
</details>

Таким образом, изменение hostname внутри UTS namespace не влияет на hostname основной системы. Процессы в разных UTS namespaces могут видеть разные имена хоста, хотя работают на одном Linux-ядре.

### 2.3. Network namespace

Network namespace изолирует сетевой стек процесса: сетевые интерфейсы, IP-адреса и таблицу маршрутизации.

Сначала на хостовой системе был просмотрен список сетевых интерфейсов:

```bash
ip addr
```

На хосте присутствовал интерфейс `enp0s3` с IP-адресом `10.0.2.15`.
<details>
<summary>Результат</summary>

![Сеть хостовой системы](images/part2-net-host.png)
</details>

После этого был создан отдельный network namespace:

```bash
sudo unshare --net bash
```

Внутри него снова был выполнен:

```bash
ip addr
```

В новом namespace присутствовал только loopback-интерфейс `lo`, находившийся в состоянии `DOWN`.

Таблица маршрутизации была проверена командой:

```bash
ip route
```

Она оказалась пустой.

Дополнительно была проверена доступность внешней сети:

```bash
ping -c 1 8.8.8.8
```

Результат:

```text
Сеть недоступна
```
<details>
<summary>Результат</summary>

![Сеть внутри network namespace](images/part2-net-namespace.png)
</details>

Таким образом, новый network namespace получил отдельный сетевой стек и не унаследовал сетевой интерфейс и маршруты хостовой системы.

### 2.4. User namespace

User namespace изолирует идентификаторы пользователей и групп (`UID` и `GID`).

Благодаря этому процесс может иметь UID `0` и считаться `root` внутри namespace, оставаясь при этом непривилегированным пользователем на хостовой системе.

Сначала были проверены пользователь и его идентификаторы на хосте:

```bash
whoami
id
```

Результат:

```text
anna
uid=1000(anna) gid=1000(anna) ...
```

Таким образом, на хостовой системе процесс выполняется от имени обычного пользователя `anna` с UID `1000`.
<details>
<summary>Результат</summary>

![Пользователь на хостовой системе](images/part2-user-host.png)
</details>

После этого был создан новый user namespace:

```bash
unshare --user --map-root-user bash
```

Параметры команды:

- `--user` - создаёт отдельный user namespace;
- `--map-root-user` - отображает текущего пользователя хоста в пользователя с UID `0` внутри namespace;
- `bash` - запускает оболочку внутри созданного namespace.

Внутри namespace снова были выполнены:

```bash
whoami
id
```

Результат:

```text
root
uid=0(root) gid=0(root) ...
```

Таким образом, внутри user namespace текущий пользователь имеет UID `0` и воспринимается как `root`.

Для просмотра отображения UID была выполнена команда:

```bash
cat /proc/self/uid_map
```

Результат:

```text
         0       1000          1
```

Значения означают:

- `0` - UID внутри namespace;
- `1000` - соответствующий UID на хостовой системе;
- `1` - количество отображаемых UID.

Следовательно, UID `0` (`root`) внутри namespace соответствует UID `1000` (`anna`) на хосте.

Дополнительно была предпринята попытка прочитать файл `/etc/shadow`:

```bash
cat /etc/shadow
```

Результат:

```text
cat: /etc/shadow: Отказано в доступе
```

Несмотря на то, что внутри namespace пользователь отображается как `root`, он не получает полномочия настоящего root-пользователя хостовой системы.
<details>
<summary>Результат</summary>

![Root внутри user namespace](images/part2-user-namespace.png)
</details>

Таким образом, user namespace позволяет изолировать пространство UID и GID. Процесс может обладать UID `0` внутри namespace, при этом снаружи оставаться обычным непривилегированным пользователем.

### 2.5. Mount namespace

Mount namespace изолирует таблицу монтирования файловых систем.

Это позволяет процессам внутри namespace иметь собственный набор точек монтирования, не изменяя представление файловых систем для процессов хостовой системы.

Для создания отдельного mount namespace была выполнена команда:

```bash
sudo unshare --mount --propagation private bash
```

Параметры команды:

- `--mount` - создаёт отдельный mount namespace;
- `--propagation private` - делает изменения монтирований приватными для созданного namespace;
- `bash` - запускает оболочку внутри нового namespace.

Внутри namespace была создана директория:

```bash
mkdir -p /tmp/lab1-mount
```

После этого в неё была примонтирована временная файловая система `tmpfs` размером 10 МБ:

```bash
mount -t tmpfs -o size=10M tmpfs /tmp/lab1-mount
```

Наличие монтирования было проверено командой:

```bash
mount | grep lab1-mount
```

Результат показал, что внутри namespace в `/tmp/lab1-mount` действительно примонтирован `tmpfs`.

Затем внутри примонтированной файловой системы был создан файл:

```bash
echo "inside mount namespace" > /tmp/lab1-mount/inside.txt
```

Проверка содержимого:

```bash
ls -la /tmp/lab1-mount
```

показала файл `inside.txt`.
<details>
<summary>Результат</summary>

![Монтирование внутри mount namespace](images/part2-mount-namespace.png)
</details>

После этого на хостовой системе была выполнена проверка:

```bash
mount | grep lab1-mount
```

Команда не вывела результатов, то есть монтирование `tmpfs`, созданное внутри namespace, на хосте отсутствует.

Также была проверена директория:

```bash
ls -la /tmp/lab1-mount
```

На хосте каталог существует, однако файл `inside.txt` отсутствует.
<details>
<summary>Результат</summary>

![Mount namespace с точки зрения хоста](images/part2-mount-host.png)
</details>

Сам каталог `/tmp/lab1-mount` виден на хосте, поскольку создание директории является обычной операцией с общей файловой системой. Однако монтирование `tmpfs` поверх этой директории существует только внутри отдельного mount namespace.

Таким образом, mount namespace изолирует точки монтирования: процессы внутри namespace могут видеть другое представление файловых систем, не изменяя таблицу монтирований хостовой системы.

### 2.6. IPC namespace

IPC namespace изолирует механизмы межпроцессного взаимодействия, в том числе System V shared memory, очереди сообщений и семафоры.

Для создания отдельного IPC namespace была выполнена команда:

```bash
sudo unshare --ipc bash
```

После этого внутри namespace был создан сегмент разделяемой памяти размером 1024 байта:

```bash
ipcmk -M 1024
```

Результат:

```text
ID разделяемой памяти: 0
```

Список сегментов shared memory внутри namespace был просмотрен командой:

```bash
ipcs -m
```

Внутри namespace отображался созданный сегмент с `shmid = 0` и размером `1024` байта.
<details>
<summary>Результат</summary>

![Shared memory внутри IPC namespace](images/part2-ipc-namespace.png)
</details>

После этого на хостовой системе была выполнена та же команда:

```bash
ipcs -m
```

На хосте присутствовали собственные IPC-объекты различных процессов, однако сегмент `shmid = 0` размером 1024 байта, созданный внутри IPC namespace, отсутствовал.
<details>
<summary>Результат</summary>

![IPC-объекты на хостовой системе](images/part2-ipc-host.png)
</details>

Таким образом, IPC namespace предоставляет процессам отдельное пространство IPC-объектов. Объекты shared memory, созданные внутри namespace, не становятся видимыми процессам хостовой системы.

### 2.7. Запуск сервиса со всеми namespaces

После отдельной проверки каждого типа namespace сервис был запущен в окружении, объединяющем `user`, `pid`, `mount`, `net`, `uts` и `ipc` namespaces.

Для создания окружения использовалась команда:

```bash
unshare \
  --user --map-root-user \
  --pid --fork \
  --mount --mount-proc \
  --net \
  --uts \
  --ipc \
  bash
```

Внутри созданного окружения были выполнены проверки:

```bash
echo $$
ps -ef
hostname
hostname lab1-container
ip addr
ip link set lo up
whoami
id
cat /proc/self/uid_map
```

Результаты показали:

- текущий процесс внутри PID namespace имеет PID `1`;
- процессы хостовой системы внутри namespace не отображаются;
- hostname внутри namespace изменён на `lab1-container`;
- сетевой интерфейс хоста `enp0s3` отсутствует, доступен только отдельный loopback-интерфейс;
- пользователь внутри namespace отображается как `root` с UID `0`;
- UID `0` внутри namespace отображается в UID `1000` пользователя `anna` на хостовой системе.
<details>
<summary>Результат</summary>

![Проверка объединённых namespaces](images/part2-all-namespaces-inside.png)
</details>

После настройки окружения текущая оболочка была заменена процессом Java-сервиса:

```bash
exec ./bin/startup.sh
```

Использование `exec` позволяет заменить текущий процесс Bash на процесс Java без создания дополнительного дочернего процесса. Поэтому сервис наследует PID `1` внутри созданного PID namespace.

Снаружи namespace сервис был найден командой:

```bash
pgrep -af 'lab1-1.0.0.jar'
```

На хостовой системе процесс имел обычный PID `7668` и выполнялся от имени пользователя `anna` с UID `1000`.

Соответствие PID внутри и снаружи namespace было проверено командой:

```bash
grep NSpid /proc/$PID/status
```

Результат:

```text
NSpid:  7668  1
```

Первое значение является PID процесса на хостовой системе, второе - PID этого же процесса внутри созданного PID namespace.

Hostname процесса был проверен через его UTS namespace:

```bash
sudo nsenter -t "$PID" -u hostname
```

Результат:

```text
lab1-container
```

При этом hostname хостовой системы остался прежним:

```text
itmo-containers
```

Работоспособность сервиса внутри его network namespace была проверена командой:

```bash
sudo nsenter -t "$PID" -n curl http://127.0.0.1:8080/health
```

Ответ:

```text
Приложение работает
```
<details>
<summary>Результат</summary>

![Сервис внутри namespaces с точки зрения хоста](images/part2-service-host-view.png)
</details>

Таким образом, один процесс приложения одновременно работает в отдельных PID, mount, network, UTS, IPC и user namespaces. Внутри окружения сервис видит себя как PID `1` и root-пользователя, имеет собственный hostname и изолированную сеть, тогда как на хостовой системе тот же процесс остаётся обычным непривилегированным процессом пользователя `anna`.

## Часть 3 - Ограничение ресурсов с помощью cgroups v2

Cgroups позволяют ограничивать количество ресурсов, доступных процессам. В данной части были проверены ограничения оперативной памяти, CPU и количества процессов.

### 3.1. Ограничение памяти

Для проверки ограничения оперативной памяти была создана отдельная cgroup:

```bash
sudo mkdir /sys/fs/cgroup/lab1-memory
```

Для неё был установлен максимальный объём памяти 300 МБ:

```bash
echo $((300 * 1024 * 1024)) | sudo tee /sys/fs/cgroup/lab1-memory/memory.max
```

Полученное значение:

```text
314572800
```

Также для cgroup был запрещён swap:

```bash
echo 0 | sudo tee /sys/fs/cgroup/lab1-memory/memory.swap.max
```

Чтобы сервис с самого запуска учитывался внутри созданной cgroup, он был запущен непосредственно в `lab1-memory`.

Для эксперимента JVM был явно задан максимальный размер heap 512 МБ:

```text
-Xmx512m
```

Это необходимо, поскольку JVM учитывает ограничения cgroups и при обычном запуске автоматически уменьшает максимально доступный heap. Без явного увеличения heap исключение `OutOfMemoryError` внутри JVM могло возникнуть раньше, чем процесс достиг бы ограничения `memory.max`.

После запуска было проверено, что процесс находится в необходимой cgroup:

```bash
cat /proc/$PID/cgroup
```

Результат:

```text
0::/lab1-memory
```

Текущее потребление памяти процессом:

```bash
cat /sys/fs/cgroup/lab1-memory/memory.current
```

Результат:

```text
120721408
```

Максимально разрешённая память:

```bash
cat /sys/fs/cgroup/lab1-memory/memory.max
```

Результат:

```text
314572800
```

Также был проверен максимальный размер Java heap:

```bash
jcmd "$PID" VM.flags | grep -o 'MaxHeapSize=[0-9]*'
```

Результат:

```text
MaxHeapSize=536870912
```

Таким образом, JVM могла использовать heap до 512 МБ, тогда как cgroup ограничивала весь процесс 300 МБ памяти.

Перед созданием нагрузки были проверены события cgroup:

```bash
cat /sys/fs/cgroup/lab1-memory/memory.events
```


До превышения лимита:

```text
max 0
oom 0
oom_kill 0
```

После этого сервису был отправлен запрос на выделение и удержание дополнительных 250 МБ памяти:

```bash
curl 'http://127.0.0.1:8080/eat?mb=250'
```

С учётом уже использовавшейся процессом памяти суммарное потребление превысило установленный лимит 300 МБ.

Соединение было разорвано:

```text
curl: (52) Empty reply from server
```

После запроса процесс Java больше не отображался в списке процессов:

```bash
pgrep -af '^java .*lab1-1\.0\.0\.jar$'
```

Повторная проверка `memory.events` показала:

```text
max 23
oom 1
oom_kill 1
```

Значение `oom = 1` означает, что cgroup столкнулась с нехваткой доступной памяти, а `oom_kill = 1` подтверждает, что процесс был завершён OOM killer ядра Linux вследствие превышения ограничения `memory.max`.
<details>
<summary>Результат</summary>

![OOM при превышении ограничения памяти](images/part3-memory-oom.png)
</details>

Таким образом, cgroups v2 позволяют задать жёсткий предел потребления оперативной памяти процессом. При попытке превысить установленный `memory.max` ядро Linux завершает процесс с помощью OOM killer.

### 3.2. Ограничение CPU

Для проверки ограничения процессорного времени сервис был запущен в отдельной cgroup с ограничением CPU.

Чтобы корректно создать cgroup в системе, управляемой `systemd`, использовалась transient-служба:

```bash
sudo systemd-run \
  --unit=lab1-cpu \
  --property=User=anna \
  --property=CPUQuota=50% \
  --working-directory=/home/anna/projects/Semester_1-containerization_and_orchestration/lab1 \
  /home/anna/projects/Semester_1-containerization_and_orchestration/lab1/bin/startup.sh
```

Параметр:

```text
CPUQuota=50%
```

ограничивает сервис примерно половиной одного логического CPU.

После запуска был определён путь к cgroup сервиса:

```bash
CG=$(sudo systemctl show -p ControlGroup --value lab1-cpu.service)
echo "$CG"
```

Результат:

```text
/system.slice/lab1-cpu.service
```

Затем было проверено значение `cpu.max`:

```bash
cat "/sys/fs/cgroup$CG/cpu.max"
```

Результат:

```text
50000 100000
```

Первое значение задаёт процессорную квоту, второе - период в микросекундах.

Таким образом, за каждые `100000` мкс процессам cgroup разрешено использовать CPU в течение `50000` мкс, что соответствует примерно 50% одного логического процессора.

Перед созданием нагрузки была просмотрена статистика cgroup:

```bash
cat "/sys/fs/cgroup$CG/cpu.stat"
```

До нагрузки часть статистики имела вид:

```text
nr_periods 224
nr_throttled 113
throttled_usec 18685281
```

После этого был вызван эндпоинт `/burn`, который создаёт постоянную нагрузку на CPU:

```bash
curl http://127.0.0.1:8080/burn >/dev/null 2>&1 &
```

После 10 секунд работы:

```bash
sleep 10
cat "/sys/fs/cgroup$CG/cpu.stat"
```

были получены следующие значения:

```text
nr_periods 509
nr_throttled 248
throttled_usec 32090725
```

Количество событий throttling увеличилось:

```text
nr_throttled: 113 → 248
```

Также увеличилось суммарное время, в течение которого cgroup была ограничена процессорной квотой:

```text
throttled_usec: 18685281 → 32090725
```

Это подтверждает, что процесс пытался использовать больше CPU, чем разрешено установленным значением `cpu.max`, после чего ядро Linux временно приостанавливало выполнение процессов cgroup.
<details>
<summary>Результат</summary>

![CPU throttling при превышении квоты](images/part3-cpu-throttling.png)
</details>

Таким образом, cgroups v2 позволяют ограничить доступное процессорное время. При превышении установленной квоты процесс не завершается, а его выполнение периодически приостанавливается механизмом CPU throttling.

### 3.3. Ограничение количества процессов

Для проверки ограничения количества процессов была создана отдельная cgroup с помощью transient-службы `systemd`.

Тест был запущен командой:

```bash
sudo systemd-run \
  --unit=lab1-pids-test \
  --property=User=anna \
  --property=TasksMax=20 \
  --working-directory=/tmp \
  /usr/bin/stress-ng --fork 100 --timeout 120s
```

Параметр:

```text
TasksMax=20
```

устанавливает ограничение на максимальное количество задач внутри cgroup.

Для создания нагрузки использовался:

```text
stress-ng --fork 100
```

который пытается одновременно создавать большое количество дочерних процессов.

После запуска был определён путь cgroup:

```bash
CG=$(sudo systemctl show -p ControlGroup --value lab1-pids-test.service)
```

Значение ограничения было проверено командой:

```bash
cat "/sys/fs/cgroup$CG/pids.max"
```

Результат:

```text
20
```

Текущее количество задач:

```bash
cat "/sys/fs/cgroup$CG/pids.current"
```

Результат:

```text
20
```

Таким образом, количество процессов достигло установленного предела, но не превысило его.

Дополнительно была просмотрена статистика:

```bash
cat "/sys/fs/cgroup$CG/pids.events"
```

Результат:

```text
max 1399516
```

Положительное значение счётчика `max` означает, что процессы многократно пытались создать новые задачи после достижения `pids.max`, однако ядро Linux отклоняло эти попытки.

Статус службы также показывал:

```text
Tasks: 20 (limit: 20)
```
<details>
<summary>Результат</summary>

![Ограничение количества процессов](images/part3-pids-limit.png)
</details>

Таким образом, контроллер `pids` в cgroups v2 позволяет ограничить количество процессов и потоков внутри группы. После достижения значения `pids.max` дальнейшие попытки создания процессов блокируются ядром.


## Часть 4 - Права

### Сброс лишних `Capabilities`
Для демонстрации сброса лишних capabilities воспользуемся функцией `GET /changeTime`, которая меняет системное время на `19-09-2026 12:00:00`.

1. Запустим приложение и проверим, что смена системного времени проходит: `sudo java -jar lib/lab1-1.0.0.jar --server.port=9191`
2. Попробуем сменить системное время: `GET curl 'http://localhost:9191/changeTime'`
<details>
<summary>Результат</summary>

![images/task4_changeTime.png](images/task4_changeTime.png)
</details>

2. Сбросим соответствующий `capability` и перезапустим приложение: `sudo capsh --drop=cap_sys_time -- -c 'exec java -jar lib/lab1-1.0.0.jar --server.port=9191'`
3. Попробуем сменить системное время: `GET curl 'http://localhost:9191/changeTime'`
<details>
<summary>Результат</summary>

![images/task4_changeTimeNotSupported.png](images/task4_changeTimeNotSupported.png)
</details>

### Добавление `seccomp`-профилей
Для демонстрации добавления `seccomp`-профилей воспользуемся той же функцией, что и в предыдущем пункте по смене системного времени `GET /changeTime`:

1. Запустим приложение без `seccomp`-профиля и проверим, что время опять можно изменить: `sudo systemd-run --unit=lab1-seccomp /bin/bash "$(realpath ./startup.sh)"`
2. При вызове `GET /changeTime` время меняется на `19-09-2026 12:00:00`
3. Запустим приложение с `seccomp`-профилем: `sudo systemd-run  --unit=lab1-seccomp -p SystemCallFilter='~clock_settime' -p SystemCallErrorNumber=EPERM   /bin/bash "$(realpath ./startup.sh)`
<details>
<summary>Результат</summary>

![images/part4_seccomp.png](images/part4_seccomp.png)
</details>

4. Попробуем сменить системное время
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ curl 'http://localhost:9191/changeTime'
Failed to change system time.
Exit code: 1
date: cannot set date: Operation not permitted
Sat Sep 19 12:00:00 PM +03 2026
```
</details>

`Capabilities` - это набор прав, доступных `root`-пользователю. В эксперименте по смене системного времени представлено, что при запуске приложения из под `root` пользователя, дочерний процесс (запущенное приложение) наследовало его `capabilities` и могло изменять системное время устройства. При удалении `capability` CAP_SYS_TIME приложение как и прежде запускается под `root` пользователем, но с меньшим диапазоном прав, вследствие чего попытка изменить системное время повторно завершилось с ошибкой.

`Seccomp`-профили ограничивают набор доступных процессу **системных вызовов**. Во втором эксперименте запуск приложения производится под `root` пользователем, с правами по смене времени `CAP_SYS_TIME`, но с запретом на системный вызов `clock_settime`, из-за чего смена времени для данного процесса запрещена.

Таким образом, `Capability` - определяют **права** процесса, в то время как `seccomp`-профили определяют набор **запрещенных** системных вызовов. Запрет на выполнение системного вызова **сильнее** `capability`.

## Часть 5 - Свой Docker

На предыдущих этапах были отдельно рассмотрены основные механизмы контейнеризации Linux: namespaces, cgroups, capabilities и seccomp. На данном этапе они были объединены в единый скрипт `mydocker.sh`.

Скрипт создаёт для Java-сервиса отдельные namespaces, задаёт ограничения ресурсов через cgroups и ограничивает доступные процессу привилегии.

### Запуск через `mydocker.sh`

Скрипт запускается командой:

```bash
./mydocker.sh
```

В процессе запуска создаются отдельные:

- PID namespace;
- user namespace;
- mount namespace;
- network namespace;
- UTS namespace;
- IPC namespace.

Для UTS namespace устанавливается hostname:

```text
lab1-container
```

Для Java-процесса было получено:

```text
Java host PID: 2755930

NSpid: 2755930 1
```

Это означает, что на хостовой системе процесс имеет PID `2755930`, а внутри собственного PID namespace является процессом с PID `1`.

Также были установлены ограничения ресурсов:

```text
Memory limit:
314572800

CPU limit:
50000 100000

PIDs limit:
64
```

То есть сервис ограничен 300 МБ оперативной памяти, половиной одного CPU и 64 задачами.

После применения изоляции и ограничений сервис успешно прошёл health-check:

```text
Приложение работает
Service is healthy
```

![Запуск сервиса через mydocker.sh](images/part5-mydocker-run.png)

### Проверка ограничений прав

Для итогового запуска у процесса отключается capability CAP_SYS_TIME, а seccomp-фильтр запрещает системный вызов clock_settime.

Попытка изменить системное время:

```bash
sudo nsenter -t "$PID" -n curl http://127.0.0.1:8080/changeTime
```

завершается ошибкой:

```text
Failed to change system time.
Exit code: 1
date: невозможно установить дату: Операция не позволена
```

После этого `/health` продолжает успешно отвечать:

```text
Приложение работает
```

Таким образом, ограничения прав применяются к процессу, но не мешают основной работе сервиса.

![Проверка ограничений прав в mydocker.sh](images/part5-mydocker-security.png)

### Запуск через Docker

Для сравнения тот же Java-сервис был собран в Docker-образ:

```bash
sudo docker build -f basic.dockerfile -t lab1-api .
```

Контейнер был запущен со схожими ограничениями:

```bash
sudo docker run --rm \
  --name lab1-docker \
  --memory=300m \
  --memory-swap=300m \
  --cpus=0.5 \
  --pids-limit=64 \
  --cap-drop=SYS_TIME \
  -p 8080:8080 \
  lab1-api
```

После запуска проверка:

```bash
curl http://127.0.0.1:8080/health
```

возвращает:

```text
Приложение работает
```

Попытка изменить системное время:

```bash
curl http://127.0.0.1:8080/changeTime
```

завершается ошибкой:

```text
Failed to change system time.
Exit code: 1
date: cannot set date: Permission denied
```

При этом повторный запрос `/health` снова успешно выполняется.

![Проверка ограничений прав в Docker](images/part5-docker-security.png)

### Проверка ограничений Docker

Параметры контейнера были проверены командой:

```bash
sudo docker inspect lab1-docker \
  --format 'Memory={{.HostConfig.Memory}} NanoCPUs={{.HostConfig.NanoCpus}} PidsLimit={{.HostConfig.PidsLimit}}'
```

Результат:

```text
Memory=314572800
NanoCPUs=500000000
PidsLimit=64
```

Java-приложение внутри контейнера является процессом с PID `1`:

```text
UID    PID   PPID   CMD
root     1      0   java -jar app.jar
```

Также значения cgroups v2 внутри контейнера совпадают с заданными ограничениями:

```text
memory.max:
314572800

cpu.max:
50000 100000

pids.max:
64
```

![Проверка cgroup-лимитов Docker](images/part5-docker-limits.png)

### Сравнение `mydocker.sh` и Docker

| Механизм | `mydocker.sh` | Docker |
|---|---|---|
| PID namespace | создаётся через `unshare` | создаётся автоматически |
| UTS namespace | создаётся через `unshare` | создаётся автоматически |
| Network namespace | создаётся через `unshare` | создаётся автоматически |
| Mount namespace | создаётся через `unshare` | создаётся автоматически |
| IPC namespace | создаётся через `unshare` | создаётся автоматически |
| User namespace | создаётся вручную | зависит от конфигурации Docker |
| Ограничение памяти | `MemoryMax=300M` | `--memory=300m` |
| Ограничение CPU | `CPUQuota=50%` | `--cpus=0.5` |
| Ограничение процессов | `TasksMax=64` | `--pids-limit=64` |
| Capabilities | задаются через systemd | управляются через `--cap-drop` |
| Seccomp | фильтр задаётся вручную | Docker применяет собственный seccomp-профиль |
| Root filesystem | отдельный rootfs не создаётся | создаётся из Docker image |
| Сеть наружу | проброс портов вручную не реализован | используется `-p 8080:8080` |
| Образы и слои | отсутствуют | используются Docker images и layers |
| Управление контейнером | реализовано скриптом и systemd | выполняется Docker |

Таким образом, `mydocker.sh` вручную объединяет базовые механизмы ядра Linux, используемые для контейнеризации: namespaces, cgroups, capabilities и seccomp.

Docker использует те же фундаментальные механизмы, но дополнительно автоматизирует создание изолированной файловой системы, работу с образами и слоями, сетевую конфигурацию, проброс портов и управление жизненным циклом контейнера.


## Часть 6 - Образы
См. реализацию одноэтапного `dockerfile` в файле [basic.dockerfile](basic.dockerfile), реализацию `multi-stage` сборки в файле [multistage.dockerfile](multistage.dockerfile).

### Одноэтапный образ
1. Соберем `docker`-образ: `sudo docker build -f basic.dockerfile -t lab1-basic-image:1.0 .`
<details>
<summary>Результат</summary>

![images/part6-buildBasicImage.png](images/part6-buildBasicImage.png)

</details>

2. Следующим соберем multi-stageобраз: `sudo docker build -f multistage.dockerfile -t lab1-multistage-image:1.0 .`, можно заметить, что при сборке `multi-stage` образа переиспользовался закэшированный шаг `WORKDIR /app`, остальные слои, так как располагаются выше и отличаются от одноэтапной сборки, берутся не из кэша:
<details>
<summary>Результат</summary>

![images/part6-buildMultistage1.png](images/part6-buildMultistage1.png)

![images/part6-buildMultistage2.png](images/part6-buildMultistage2.png)

![images/part6-buildMultistage3.png](images/part6-buildMultistage3.png)
</details>

3. При повторной сборки `multi-stage` образа можно заметить, что из кэша берутся все слои, так как исходники кода (и `dockerfile`) не менялись:
<details>
<summary>Результат</summary>

![images/part6-rebuildMultistage.png](images/part6-rebuildMultistage.png)
</details>

4. Сравним размер и число слоев собранных образов в пунктах 1 и 3:
    * sudo docker images -> размер одинаковый, так как в `nultistage` сборке в образ сохраняется только результат последнего этапа
    * `sudo docker image inspect lab1-basic-image:1.0 --format '{{len .RootFS.Layers}}'` и `sudo docker image inspect lab1-multistage-image:1.0 --format '{{len .RootFS.Layers}}'` - количество слое одинаковое (8), учитываются как собственные слои, так и слои используемых образов `eclipse-temurin:21-jre`
<details>
<summary>Результат</summary>

![img_1.png](images/part6_dockerImage.png)
![img.png](images/part6_imageLayers.png)
</details>

5. Запустим контейнер на основе образа `lab1-multistage-image:1.0` и запишем туда файл:
<details>
<summary>Результат</summary>

![images/part6_createFile1.png](images/part6_createFile1.png)
![images/part6_createFile2.png](images/part6_createFile2.png)
</details>

6. Пересоздадим контейнер и убедимся, что файл удален, так как при удалении контейнера записываемые в него данные не сохраняются, контейнер пересоздается на основе ранее созданного **нередактируемого** образа:
<details>
<summary>Результат</summary>

![images/part6_createFile1.png](images/part6_createFile3.png)
![images/part6_createFile2.png](images/part6_createFile4.png)
</details>

7. Создадим том, запустим контейнер с подключенным томом и затем пересоздадим контейнер:
- `sudo docker volume create lab1-data` - создание тома lab1-data
- `sudo docker run --name lab1-multistage-container -p 8080:8080 -v lab1-data:/data lab1-multistage-image:1.0` - запуск нового контейнера с подключенным томом
- `sudo docker exec lab1-multistage-container   sh -c 'echo "I will survive" > /data/test.txt'` - запись в том файла `test.txt`
- `sudo docker rm -f lab1-multistage-container` - остановка и удаление контейнера
- `sudo docker run --name lab1-multistage-container -p 8080:8080 -v lab1-data:/data lab1-multistage-image:1.0` - запуск нового контейнера с подключением того же тома
- `sudo docker exec lab1-multistage-container cat /data/test.txt` - проверяем, сохранился ли файл:
<details>
<summary>Результат</summary>

![images/part6_tom1.png](images/part6_tom1.png)
</details>

## Часть 7 - Gvisor
Для исследования окружения контейнера реализован endpoint `POST /system/execute`, который запускает shell-команды внутри контейнера.

### Сравнение Gvisor и стандартного контейнера
1. Соберем новый образ с добавленным endpoint-ом: `sudo docker build --progress=plain -f multistage.dockerfile -t lab1-multistage-image:1.1 .`
2. Запустим 2 контейнера с приложением:
* Контейнер БЕЗ изоляции gvisor будет принимать http-запросы на порту 8080: `sudo docker run --rm --name lab1-container -p 8080:8080 lab1-multistage-image:1.1`
* Контейнер с изоляцией gvisor будет принимать http-запросы на порту 9090: `sudo docker run --rm --runtime=runsc --name lab1-container-gvisor -p 9090:8080 lab1-multistage-image:1.1`

Выполним команду `uname -a` в каждом из контейнеров:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ echo '{"command": "uname -a"}' | curl -X POST http://localhost:8080/system/execute -H "Content-Type: application/json" -d @-
Linux 4499f72d22c5 6.8.0-138-generic #138~22.04.1-Ubuntu SMP PREEMPT_DYNAMIC Fri Aug  7 13:43:15 UTC  x86_64 GNU/Linux

pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ echo '{"command": "uname -a"}' | curl -X POST http://localhost:9090/system/execute -H "Content-Type: application/json" -d @-
Linux a730ad1bbd44 4.19.0-gvisor #1 SMP Sun Jan 10 15:06:54 PST 2016 x86_64 GNU/Linux
```
Замечаем, что системная информация у контейнеров отличается, несмотря на то, что фактически они запущены на одной машине. Системные вызовы в первом случае выполняются ядром Linux, во втором - виртуализированным Linux-интерфейсом (Sentry), который перехватывает системные вызовы приложения и самостоятельно выполняет их. Таким образом, `Gvisor` выступает в роли **прослойки** между ядром хоста и контейнером и уменьшает поверхность взаимодействия приложения с ядром хоста. Это снижает риск эксплуатации уязвимостей ядра, что может повлечь за собой "побег" процесса из контейнера.

Посмотрим, какие `capabilities` и `seccomp`-профили навешаны на оба контейнера:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$  curl -X POST http://localhost:8080/system/execute \
    -H 'Content-Type: application/json' \
    --data '{"command":"grep -E \"^(Seccomp|Cap)\" /proc/self/status"}'
CapInh:	0000000000000000
CapPrm:	00000000a80425fb
CapEff:	00000000a80425fb
CapBnd:	00000000a80425fb
CapAmb:	0000000000000000
Seccomp:	2
Seccomp_filters:	1

pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$  curl -X POST http://localhost:9090/system/execute \
    -H 'Content-Type: application/json' \
    --data '{"command":"grep -E \"^(Seccomp|Cap)\" /proc/self/status"}'
CapInh:	0000000000000000
CapPrm:	00000000a80405fb
CapEff:	00000000a80405fb
CapBnd:	00000000a80405fb
CapAmb:	0000000000000000
Seccomp:	0
```
Видим, что в обычном Docker-контейнере выставлен режим работы SECCOMP_MODE_FILTER (Seccomp:2), а количество выставленных фильтров на системные вызовы - 1 (Seccomp_filters:1), в то время как в контейнере, запущенном из-под gvisor, выключены seccomp-профили. Отсутствие профилей относится к изоляции относительно Linux-интерфейса (Sentry), а не относительно Linux-ядра. Сам процесс Sentry запускается на хосте с отдельным seccomp-фильтром и ограниченным набором capabilities (PID процесса - 39023):
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ sudo grep -E 'Seccomp' /proc/39023/status
CapInh:	0000000000000000
CapPrm:	000000000008001f
CapEff:	000000000008001f
CapBnd:	000000000008001f
CapAmb:	0000000000000000
Seccomp:	2
Seccomp_filters:	1
```
Сравним процессы, видимые на хосте:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ sudo docker inspect --format '{{.State.Pid}}' lab1-container
14878
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ sudo docker inspect --format '{{.State.Pid}}' lab1-container-gvisor
39023
```
Посмотрим, что представляют из себя эти процессы на хосте:
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration/lab1$ sudo ps -ef | grep -E '[j]ava|[r]unsc'
root       14878   14855  0 12:23 ?        00:00:59 java -jar app.jar
root       39023   38998  2 22:01 ?        00:02:25 runsc-sandbox --root=/var/run/docker/runtime-runc/moby --log=/run/containerd/io.containerd.runtime.v2.task/moby/a730ad1bbd44352a089ccf163704896deab375ceb3153777c97a5c9af4d33d68/log.json --log-format=json --systemd-cgroup=true --log-fd=3 boot --bundle=/run/containerd/io.containerd.runtime.v2.task/moby/a730ad1bbd44352a089ccf163704896deab375ceb3153777c97a5c9af4d33d68 --gofer-mount-confs=lisafs:self,lisafs:none,lisafs:none,lisafs:none --apply-caps=true --setup-root --total-host-memory 16591843328 --cpu-num 8 --cpu-period 100000 --total-memory 16591843328 --io-fds=4 --io-fds=5 --io-fds=6 --io-fds=7 --dev-io-fd=-1 --gofer-filestore-fds=8 --mounts-fd=9 --start-sync-fd=10 --pin-ring-fd=11 --controller-fd=12 --spec-fd=13 --stdio-fds=14 --stdio-fds=15 --stdio-fds=16 a730ad1bbd44352a089ccf163704896deab375ceb3153777c97a5c9af4d33d68
```
Видим, что первый процесс, соответствующий контейнеру без gvisor изоляции, относится к java-приложению, в то время как процесс контейнера `lab1-container-gvisor` относится к запущенному Sentry.

Таким образом, в случае стандартного запуска Docker-контейнера, получаем изоляцию с помощью namespace, cgroups, capabilities и seccomp, **но сами системные вызовы выполняются на общем ядре хоста (предел контейнерной изоляции)**. В случае запуска контейнера под gvisor ядром выступает сам gvisor, самостоятельно обрабатывающий большую часть системных вызовов.

## Часть 8 - Мониторинг
Запустим приложение в `minikube` кластере и в отдельном namespace-е поднимем `Grafana` и `Prometheus` для настройки мониторинга приложения (подробнее про поднятие observability-стека смотри в отчете по [Лабораторной работе №2](../lab2/README.md)).

Запустим приложение `api` в namespace `lab1`: 
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm upgrade --install api ./lab1/install \
  --namespace lab1 \
  --create-namespace \
  --wait \
  --timeout 5m
Release "api" has been upgraded. Happy Helming!
NAME: api
LAST DEPLOYED: Fri Sep 25 18:52:40 2026
NAMESPACE: lab1
STATUS: deployed
REVISION: 2
TEST SUITE: None
```
Также поднимем `Grafana` и `Prometheus` в namespace `monitoring`: 
```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ helm upgrade monitoring ./lab2/observability \
  --namespace monitoring \
  --wait \
  --timeout 10m
Release "monitoring" has been upgraded. Happy Helming!
NAME: monitoring
LAST DEPLOYED: Fri Sep 25 20:17:23 2026
NAMESPACE: monitoring
STATUS: deployed
REVISION: 12
```

Создадим дашборд для отслеживания потребление памяти и CPU-ресурсов подом `api`.

### Метрики анализа потребления CPU-ресурсов
При превышении Pod-ом допустимых лимитов используемого процессорного времени возникает явление `Throttling`, при котором контейнер продолжает свою работу, но с задержками. Для отслеживания подобной ситуации можно использовать следующие метрики:
1. `CPU Throttled Periods` - процент throttled-периодов за последние N минут 
2. `CPU Throttled Time Rate` - скорость роста throttled-времени за последние N минут
3. `CPU Usage` - потребление CPU

Для вычисления первой метрики воспользуемся следующей формулой:
<details>
<summary>Формула CPU Throttling Periods метрики</summary>

```text
100 *
sum by (pod) (
  rate(container_cpu_cfs_throttled_periods_total{
    namespace="lab1",
    container="api"
  }[5m])
)
/
sum by (pod) (
  rate(container_cpu_cfs_periods_total{
    namespace="lab1",
    container="api"
  }[5m])
)
```
</details>

`Container_cpu_cfs_throttled_periods_total` - это общее количество CPU-периодов, в которых процесс прерывался. `Container_cpu_cfs_periods_total` - общее количество CPU-периодов, прошедшее за все время работы приложения. Данная метрика показывает, какой процент CPU-периодов за последние 5 минут был прерван в следствии превышения процессом лимита на CPU-ресурсы.

Для вычисления второй метрики воспользуемся следующей формулой:
<details>
<summary>Формула CPU Throttled Time Rate метрики</summary>

```text
sum by (pod) (
  rate(container_cpu_cfs_throttled_seconds_total{
    namespace="lab1",
    container="api"
  }[5m])
)
```
</details>

`Container_cpu_cfs_throttled_seconds_total` возвращает общее количество времени, которое процесс провел в состоянии throttled. Таким образом, представленная метрика отображает среднюю скорость роста времени, в течение которого процесс провел в состоянии throttled, за последние 5 минут.

Комбинация метрик 1 и 2 позволит отлавливать следующие ситуации:
- `throttling` возникает часто (`CPU Throttled Periods` высокий), но время, в течение которого ограничивались CPU-ресурсы процесса, небольшое (`CPU Throttled Time Rate` низкий) - возможно требуется увеличить лимиты для приложения
- `throttling` возникает редко (`CPU Throttled Periods` низкий), но время, в течение которого ограничивались CPU-ресурсы процесса, длительное (`CPU Throttled Time Rate` высокий) - возможно требуется детально проанализировать кейс и оптимизировать код в местах неэффективного использования ресурсов

Описанные ситуации не относятся к критичным, поэтому требуется использовать обе метрики для формирования информативного алерта. К примеру, если одновременно `CPU Throttled Periods` и `CPU Throttled Time Rate` высокие, то для всех пользователей наблюдается резкий спад скорости работы приложения.

Также необходимо создать метрику `CPU Usage`, которая будет отображать среднее количество ядер, используемых для работы приложения, за последние N минут:
<details>
<summary>Формула CPU Usage метрики</summary>

```text
sum by (pod) (
  rate(container_cpu_usage_seconds_total{
    namespace="lab1",
    container="api"
  }[2m])
)
```
</details>

Данная метрика необходима для корректной настройки requests и limits пода.

### Метрики анализа потребления памяти
В работе контейнеров важно отслеживать потребление памяти, так как при превышении установленного лимита, контейнер перезапускается. При регулярных утечках памяти, либо низких лимитах по памяти, приложение будет перезапускаться часто, что также повлечет за собой проблемы со скоростью работы приложения на стороне пользователей.

Для анализа потребления памяти создадим 2 метрики, которые отслеживают факты возникновения событий OOMKilled и сколько памяти потребляет данный контейнер:
- `Memory usage` - сколько памяти потребляет данный контейнер
- `OOMKilled Events` - признак перезагрузки контейнера по причине нехватки памяти за последние N минут

Для расчета признака перезагрузки контейнера `OOMKilled events` по причине OOMKilled воспользуемся метриками контейнера `kube_pod_container_status_restarts_total`, которая фиксирует количество перезапуском контейнера, `kube_pod_container_status_last_terminated_reason`, которая фиксирует последнюю причину перезагрузки контейнера. Таким образом следующая формула определяет, был ли перезагружен контейнер за последние 2 минуты из-за превышения лимита по памяти:
<details>
<summary>Формула OOMKilled Events метрики</summary>

```text
(
  (
    increase(
      kube_pod_container_status_restarts_total{
        namespace="lab1",
        container="api"
      }[2m]
    ) > bool 0
  )
  and on(namespace, pod, container)
  (
    kube_pod_container_status_last_terminated_reason{
      namespace="lab1",
      container="api",
      reason="OOMKilled"
    } == 1
  )
)
or on(namespace, pod, container)
(
  0 * kube_pod_container_status_restarts_total{
    namespace="lab1",
    container="api"
  }
)
```
</details>

Метрика `Memory Usage` определяет сколько памяти потребляет контейнер в данный момент:
<details>
<summary>Формула Memory usage метрики</summary>

```text
sum by (pod) (
  container_memory_working_set_bytes{
    namespace="lab1",
    container="api"
  }
  and on(namespace, pod, container, id)
  topk by(namespace, pod, container) (
    1,
    container_start_time_seconds{
      namespace="lab1",
      container="api"
    }
  )
)
```
</details>

### Эксперимент
Запустим `GET /burn` и посмотрим как меняются метрики потребления CPU-ресурсов с течением времени:
<details>
<summary>Результат</summary>

![images/part8_burn.png](images/part8_burn.png)
</details>

Сгенерируем OOMKilled event засчет вызова `GET /eat`
<details>
<summary>Результат</summary>

```bash
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl "http://$(minikube ip):30081/eat?mb=100"
Выделено 100 МБ
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl "http://$(minikube ip):30081/eat?mb=100"
Выделено 100 МБ
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl "http://$(minikube ip):30081/eat?mb=100"
Выделено 100 МБ
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl "http://$(minikube ip):30081/eat?mb=100"
curl: (52) Empty reply from server
pona@pona-RedmiBook-14:~/Documents/Semester_1-containerization_and_orchestration$ curl "http://$(minikube ip):30081/health"
Приложение работает
```

![images/part8_eat.png](images/part8_eat.png)
</details>

Полный дашборд выглядит следующим образом:
<details>
<summary>Результат</summary>

![images/prat8_dashboard.png](images/prat8_dashboard.png)
</details>