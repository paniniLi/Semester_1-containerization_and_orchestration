# Отчет по лабораторной работе №1 "Docker"

## Часть 0 - Свой сервис
Язык сервиса - `Java` (см. подробнее версию `jdk`, и версии используемых библиотек в @lab1/pom.xml)

Сервис реализует следующие `endpoints`:
- `GET /health` — возвращает ok;
- `GET /eat?mb=N` — выделяет N мегабайт памяти и держит их;
- `GET /burn` — нагружает одно ядро CPU в бесконечном цикле;

Дополнительные `endpoints,` реализованные для демонстрации результатов:
- (**пункт 4** - capabilities) `GET /readFile` - возвращает содержимое предварительно созданного файла `/tmp/secret.txt`
- (**пункт 4** - sepccomp-профили) `GET /socket/open` - создает клиентский TCP-сокет и пытается установить соединение с адресом `127.0.0.1:8080`
- (**пункт 6**) `POST /system/execute` - выполняет системную команду внутри среды, где запущено приложение и возвращает результат работы переданной команды

См. реализацию сервиса в @lab1/src.

## Часть 1 - Запуск приложения без изоляций
1. Собираем приложение: `mvn clean package`
2. Переходим в @lab1/bin и запускаем скрипт @lab1/bin/startup.sh: `./startup.sh --server.port=9191`
<details>
  <summary>Результат</summary>

```bash
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

:: Spring Boot ::                (v4.1.1)

2026-09-19 13:42:15.918 [main] INFO  org.panini.Application - Starting Application v1.0.0 using Java 21.0.12 with PID 21038 (/home/pona/Documents/Semester_1-containerization_and_orchestration/lab1/lib/lab1-1.0.0.jar started by pona in /home/pona/Documents/Semester_1-containerization_and_orchestration/lab1/bin)
2026-09-19 13:42:15.921 [main] DEBUG org.panini.Application - Running with Spring Boot v4.1.1, Spring v7.0.9
2026-09-19 13:42:15.921 [main] INFO  org.panini.Application - No active profile set, falling back to 1 default profile: "default"
2026-09-19 13:42:16.666 [main] INFO  o.s.boot.tomcat.TomcatWebServer - Tomcat initialized with port 9191 (http)
2026-09-19 13:42:16.678 [main] INFO  o.a.coyote.http11.Http11NioProtocol - Initializing ProtocolHandler ["http-nio-0.0.0.0-9191"]
2026-09-19 13:42:16.681 [main] INFO  o.a.catalina.core.StandardService - Starting service [Tomcat]
2026-09-19 13:42:16.681 [main] INFO  o.a.catalina.core.StandardEngine - Starting Servlet engine: [Apache Tomcat/11.0.24]
2026-09-19 13:42:16.713 [main] INFO  o.s.b.w.c.s.WebApplicationContextInitializer - Root WebApplicationContext: initialization completed in 735 ms
2026-09-19 13:42:16.754 [main] DEBUG org.panini.system.Logger - Filter 'logger' configured for use
2026-09-19 13:42:17.006 [main] INFO  o.a.coyote.http11.Http11NioProtocol - Starting ProtocolHandler ["http-nio-0.0.0.0-9191"]
2026-09-19 13:42:17.018 [main] INFO  o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 9191 (http) with context path '/'
2026-09-19 13:42:17.030 [main] INFO  org.panini.Application - Started Application in 1.542 seconds (process running for 2.086)
```
</details>

3. Вызываем `curl 'http://localhost:9191/health'` и получаем ответ: `Приложение работает`
<details>
<summary>Результат</summary>

![doc/task1_health.png](doc/task1_health.png)
</details>

4. Вызываем `pgrep -af '^java .*lab1-1.0.0.jar'`, получаем PID процесса `21038`
<details>
<summary>Результат</summary>

```bash
21038 java -jar /home/pona/Documents/Semester_1-containerization_and_orchestration/lab1/bin/../lib/lab1-1.0.0.jar --server.port=9191
```
</details>

5. Убьем принудительно данный процесс через `shell`: `sudo kill -9 21038`, приложение завершилось, в логах видим: `Killed`
<details>
<summary>Результат</summary>

![doc/task1_kill.png](doc/task1_kill.png)
</details>

## Часть 4 - Права

### Сброс лишних `Capabilities`
Для демонстрации сброса лишних capabilities воспользуемся функцией `GET /changeTime`, которая меняет системное время на `19-09-2026 12:00:00`.

1. Запустим приложение и проверим, что смена системного времени проходит: `sudo java -jar lib/lab1-1.0.0.jar --server.port=9191`
2. Попробуем сменить системное время: `GET curl 'http://localhost:9191/changeTime'`
<details>
<summary>Результат</summary>

![doc/task4_changeTime.png](doc/task4_changeTime.png)
</details>

2. Сбросим соответствующий `capability` и перезапустим приложение: `sudo capsh --drop=cap_sys_time -- -c 'exec java -jar lib/lab1-1.0.0.jar --server.port=9191'`
3. Попробуем сменить системное время: `GET curl 'http://localhost:9191/changeTime'`
<details>
<summary>Результат</summary>

![doc/task4_changeTimeNotSupported.png](doc/task4_changeTimeNotSupported.png)
</details>
