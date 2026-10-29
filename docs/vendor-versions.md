# Версии поставки

Основа — `business-example-service` Эволенты, commit `b716f78a17540b89b0d883f9079189b9fbeca523`.

| Компонент | Версия |
|---|---|
| Проверенный JDK | Temurin 25.0.5+8 |
| Java release исходников | 21, по шаблону; SDK исполнялся на Java 25 |
| Maven | 3.9.16 |
| core-commons | 8.0.7 |
| gostech-commons | 8.0.5 |
| sier-core-external-api | 8.0.14 |
| sier-filestorage-api / sier-storage-ftp | 8.0.11 |
| sier-storage-s3 | 8.0.8 |
| Jackson Databind/Core / Annotations | 2.21.5 / 2.21 |
| Apache POI | 5.4.1 |
| Commons Codec / Collections / IO / Lang | 1.21.0 / 4.6.0 / 2.22.0 / 3.20.0 |
| Log4j API | 2.25.5 |
| JUnit Jupiter / Surefire | 5.12.2 / 3.5.3 |

Все 258 JAR исходного SDK совпали по SHA-256 с артефактом Эволенты. 12 пересекающихся библиотек standalone совпали по имени, версии и байтам; 8 дополнительных относятся к Excel/OOXML и его зависимостям. Это не утверждение, что в SDK изначально был POI.

Закрепления dependencyManagement действуют и с профилем `gostech`. Версии JDK/Maven соответствуют использованному окружению и Java 25 в CI-шаблоне; точное содержимое устанавливаемого образа проверяется целевым CI. Результаты компиляции с SDK и корпоративной Maven-сборки разделены в протоколе.
