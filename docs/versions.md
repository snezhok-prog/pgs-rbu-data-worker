# Версии и границы совместимости

Корневой Spring-проект основан на `business-example-service` Эволенты. При переносе в назначенный репозиторий сохраняются структура `src`, контракты SDK и корпоративный процесс сборки. Имя cloud-config для нового сервиса должно быть создано командой развёртывания.

| Компонент | Версия | Основание |
| --- | --- | --- |
| JDK для проверенного окружения | Temurin 25.0.5+8 | Локальная установка и проверенный дистрибутив; целевая корпоративная сборка требует Java 25 |
| Bytecode общих классов | `--release 21` | Настройка шаблона; не означает, что SDK можно запускать на Java 21 |
| Maven | 3.9.16 | Проверенное локальное окружение |
| Корпоративный parent `core-commons` | 8.0.7 | POM шаблона Эволенты |
| `gostech-commons` | 8.0.5 | POM и артефакт шаблона |
| `sier-core-external-api` | 8.0.14 | POM и артефакт шаблона |
| `sier-filestorage-api` | 8.0.11 | POM и артефакт шаблона |
| `sier-storage-ftp` | 8.0.11 | POM и артефакт шаблона |
| `sier-storage-s3` | 8.0.8 | POM и артефакт шаблона |
| Jackson Databind/Core 2 | 2.21.5 | Совпадает с SDK-артефактом шаблона; пакет API `com.fasterxml.jackson` |
| Jackson Annotations 2 | 2.21 | Версия SDK и транзитивная зависимость Jackson 2.21.5 |
| Commons Codec | 1.21.0 | Пересекающаяся зависимость POI закреплена по SDK |
| Commons Collections 4 | 4.6.0 | Пересекающаяся зависимость POI закреплена по SDK |
| Commons IO | 2.22.0 | Пересекающаяся зависимость POI закреплена по SDK |
| Commons Lang 3 | 3.20.0 | Пересекающаяся зависимость POI закреплена по SDK |
| Log4j API | 2.25.5 | Пересекающаяся зависимость POI закреплена по SDK |
| Log4j-to-SLF4J | 2.25.5 | Runtime-мост SDK для standalone; переводит сообщения POI в SLF4J |
| SLF4J API | 2.0.18 | Runtime-версия закреплена по SDK, без второго logging backend |
| Logback Classic/Core | 1.5.38 | Runtime-реализация SDK; самостоятельный runner выводит сообщения только в консоль |
| Apache POI OOXML | 5.4.1 | Прикладная зависимость парсера торговли из предыдущей реализации; в SDK-шаблоне отсутствует |
| JUnit Jupiter | 5.12.2 | Только самостоятельные тесты |
| Surefire | 3.5.3 | Выполнение JUnit 5 при `mvn test` |
| Exec Maven Plugin | 3.6.4 | Четыре main-набора общей логики/рекламы встроены в фазу `test` |

Предыдущая сверка 258 SDK JAR с артефактом GitLab job 242851 подтвердила побайтовое соответствие скачанного SDK. Это утверждение не относится к новым прикладным зависимостям POI и тестовому JUnit. Оно также не доказывает, что произвольный набор библиотек на DEV идентичен этому артефакту.

Повторная проверка распакованного SDK из CMD, без Maven и токенов:

```cmd
java scripts\VerifySdk.java docs\sdk-library-lock.json "C:\SDK\BOOT-INF\lib"
```

Команда сверяет SHA-256 каждого JAR с [зафиксированным SDK](sdk-library-lock.json), обнаруживает отсутствующие, изменённые и лишние библиотеки. Сами JAR в репозитории не хранятся. Дополнительные библиотеки приложения не следует подкладывать в папку SDK для этой проверки: у них отдельные Maven-зависимости.

Самостоятельный runner использует Maven Central и не включает корпоративные библиотеки. Корпоративный Spring-сервис использует SDK и приватный Maven Registry. Проверка компиляции с распакованными настоящими JAR и полная Maven/Spring-сборка — разные проверки; результаты фиксируются раздельно в протоколе.

Runtime самостоятельного runner содержит 20 JAR: 12 совпадают с SDK по имени, версии и SHA-256; 8 — отдельные зависимости Excel-парсера (POI/OOXML/Lite, XMLBeans, Commons Compress/Math, curvesapi, SparseBitSet). Тестовый JUnit в этот runtime не входит. Использование `log4j-to-slf4j` вместе с Logback соответствует [схеме подключения Apache Log4j](https://logging.apache.org/log4j/2.x/manual/installation.html#impl-logback). Второй backend `log4j-core` не добавлялся.

У самостоятельного runner свой `local-runner/src/main/resources/logback.xml`: современный UTF-8 encoder, INFO, только ConsoleAppender. Корпоративный `src/main/resources/logback.xml` сохранён отдельно. Main также показывает `worker ready`, `received` и `completed` с ID задачи/процесса, направлением, профилем, операцией и длительностью; содержимое файлов и реквизиты доступа в эти строки не включаются.

## Проверка окружения 28.10.2026

- 258 распакованных SDK JAR повторно сверены с исходным артефактом: SHA-256 совпали, лишних и отсутствующих библиотек нет. Отрицательная проверка пустой папки SDK отклонена.
- Текущие исходники сервиса скомпилированы напрямую JDK 25 с настоящим SDK и дополнительными зависимостями POI. Это не Maven-сборка корпоративного сервиса.
- Короткий локальный Spring bootstrap с выключенным worker, cloud-config, discovery и Eureka остановился на обязательной настройке SDK `spring.rabbitmq.username` в `rabbitConfig`. Серверных параметров и подставных SDK-компонентов для получения PASS не добавляли; процесс остановлен.
- Корневая Maven-проверка не разрешила parent `ru.evolenta.sier:core-commons:8.0.7`: запрос к корпоративному Maven Registry завершился тайм-аутом. Успешная полная Spring/Maven-сборка не заявляется.
- Новая Maven/CMD-инфраструктура отдельно проверена на неизменённом рекламном baseline: 203 проверки фактически выполнились в фазе `test`, `clean verify` завершился успешно. Проверки расширенного торгового пакета фиксируются отдельно после интеграции.
- После интеграции торговли, канонизации чисел в business hash, безопасной HTTP-диагностики и настройки logging выполнен `mvn verify` без очистки: 62 JUnit-теста и 203 проверки четырёх main-наборов прошли. Отдельный logging smoke в новой JVM подтвердил работу Apache POI, реальный SLF4J provider, читаемый INFO и отсутствие WARN/ERROR/созданных log-файлов в standalone. Отдельный CMD-клиент прошёл 138 локальных контрактных проверок без обращений к DEV; его адаптер файлов скомпилирован с настоящим SDK. Публичный CI настроен для Linux и Windows; его фактический результат фиксируется отдельно от локальных проверок.

Источники: [шаблон сервиса Эволенты](https://git.evolenta.ru/sier/pgs/back-end/business-example-service), [exec:java](https://www.mojohaus.org/exec-maven-plugin/java-mojo.html), [настройка тестового classpath](https://www.mojohaus.org/exec-maven-plugin/examples/example-exec-or-java-change-classpath-scope.html).

## Проверка 29.10.2026

Повторно подтверждены258SDK SHA-256, standalone142JUnit+204assertions, CMD144checks,93main SDKsourcecompile+21platformtests. НастоящиеFileStorageServiceImpl/StorageEngineS3Impl иproductionSdkFilePgsApiпрочитали39файловDEVссовпавшимSHA. Корпоративныйparent8.0.7теперьвернулявныйHTTP401; полнаяMaven-сборкаиобразнеподтверждены. POMdependencyManagementвынесенизdefaultвкорень,чтобызакреплённыеверсииприменялисьтакжеприкорпоративном-Pgostech.
