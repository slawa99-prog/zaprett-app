# zaprett
## О приложении

![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/CherretGit/zaprett-app/total)
![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/CherretGit/zaprett-app/latest/total)
![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/CherretGit/zaprett-app/workflow.yml)

Приложение разработано для работы с модулем [zaprett](https://github.com/egor-white/zaprett)
> [!IMPORTANT]
> 📢 [Официальный Telegram-канал приложения](https://t.me/zaprett_module)

> [!CAUTION]
> ⚠️ Для корректной работы приложения **желательны root-права**, однако предусмотрен режим без root на основе **byedpi**

---

На данный момент приложение умеет:
* Запускать, останавливать и перезапускать сервис
* Работа с листами (добавление, включение и выключение, загрузка из репозитория)
* Работа с айписетами (добавление, включение и выключение, загрузка из репозитория)
* Работа с стратегиями (добавление, выбор, загрузка из репозитория)
* Авто обновление приложения

### Подбор стратегии в форке

В режиме root с модулем `zaprett` экран «Подбор стратегии» может проверить
установленные стратегии `nfqws` или `nfqws2` и кнопкой «Подобрать и включить»
сохранить вариант, для которого открылось больше доменов из включённых списков.
Проверяются первые 20 уникальных доменов: приложение запускает стратегии
по очереди, затем восстанавливает прежние настройки, если ни одна проверка
не прошла. Для работы заранее установите стратегии и включите список доменов.

Проверка основана на HTTPS-ответе сайта и не подтверждает воспроизведение
видео в SmartTube или YouTube. После подбора проверьте нужные приложения на
своей приставке. Это первый этап, а не полный перебор параметров `blockcheck`.

Тестовая сборка имеет отдельный идентификатор `com.slawa99.zaprett.auto` и
устанавливается рядом с исходным приложением. Обе программы обращаются к одному
модулю `zaprett` и его настройкам: не запускайте перебор в двух приложениях
одновременно. Обновление от автора исходного приложения и его Firebase в
тестовой сборке отключены.

---

## [Репозиторий с хостами и стратегиями](https://github.com/CherretGit/zaprett-hosts-repo)

---

#### Данное приложение является ремейком [приложения](https://github.com/egor-white/zaprett-app) от [egor-white](https://github.com/egor-white), разработка которого была прекращена в пользу этого приложения

---

## Скриншоты:
<p align="center">
  <img src="images/1.png" width="180">
  <img src="images/2.png" width="180">
  <img src="images/3.png" width="180"><br>
  <img src="images/4.png" width="180">
  <img src="images/5.png" width="180">
  <img src="images/6.png" width="180"><br>
  <img src="images/7.png" width="180">
  <img src="images/8.png" width="180">
  <img src="images/9.png" width="180">
</p>
