# SV Pocket

Отдельное приложение для Android TV с root: интерфейс к установленному [Zapret Pocket от sevcator](https://github.com/sevcator/zapret-pocket). Пакет `com.slawa99.pockettv`; старое приложение Zaprett не заменяется.

## Оформление 1.0.4

Иконки используют вертикальную композицию: логотип воспроизведения по центру, широкая надпись SV Pocket под ним. Заголовки в боковом меню и разделе «О приложении» используют фирменный прозрачный wordmark с серебристой S и бирюзовой V. Изображения масштабируются с сохранением пропорций; название доступно также через contentDescription.

### Оформление 1.0.3

Название приложения — SV Pocket, подзаголовок — «от мастера Святослава». Версия без отметки test отображается внизу бокового меню и берётся из BuildConfig. Квадратная иконка использует предоставленный пользователем рисунок; баннер Android TV адаптирован из него встроенным Imagegen (широкая композиция с кнопкой воспроизведения, щитом и надписью SV Pocket). Кнопки внешних ссылок убраны из раздела «О приложении».

Идентификатор пакета, каталог данных и постоянный ключ подписи сохранены: обновление устанавливается поверх 1.0-test2.

## Установка и использование

1. Установите модуль **Zapret Pocket** через Magisk, включите и перезагрузите приставку. Остановите другие модули обхода перед использованием Pocket.
2. Установите APK SV Pocket и предоставьте ему root в Magisk.
3. В «Подборе» выберите YouTube или стандартные сервисы Pocket. Проверяется **весь каталог установленного модуля**, включая его nfqws2-варианты. Неподдерживаемый движок отмечается как пропущенный.
4. Выберите строку результата и «Выбрать и перезапустить». Затем проверьте видео в YouTube/SmartTube.

Команды проверены по исходникам Pocket v71. Для старых версий без `@@TESTING`/`@@RESULT` приложение показывает требование обновить модуль, не пытаясь угадать прогресс.

## Возможности и ограничения

- Старт, остановка и перезапуск сервиса Pocket; автозапуск обеспечивает сам Magisk-модуль.
- Каталог читается из `/data/adb/modules/zapret/strategy`; отдельное скачивание каждой стратегии не требуется.
- Запускается штатный **`zapret test`**. Профиль YouTube передаёт четыре адреса YouTube, профиль «Все сервисы» использует стандартные адреса Pocket. Дополнительные DPI-пробы выполняются самим Pocket.
- Рейтинг — доля успешных HTTP/TLS-проб из структурированных результатов Pocket, с отдельными числами по HTTP, TLS 1.2, TLS 1.3. **Это не тест воспроизведения видео** и не гарантия работы на другом провайдере. Дополнительные оценки Pocket остаются в журнале, не смешиваясь с процентом.
- Порядок и оценки сохраняются до нового подбора, в том числе после закрытия приложения. Активный выбор не меняется автоматически по баллам.
- Фоновый root-процесс продолжает проверку при закрытии APK. Перезагрузка приставки прерывает подбор; уже полученные результаты остаются. PID проверяется вместе со временем запуска и ID загрузки системы, чтобы не остановить другой процесс.
- На завершении/отмене останавливается тестовый стек и восстанавливается прежнее состояние сервиса. Не запускайте второй тестер из терминала/WebUI параллельно.
- Все root-команды выполняются вне UI-потока. Команды/имена экранируются; посторонний модуль с таким же ID отклоняется.
- Журнал доступен на экране, копируется с подтверждением и отправляется файлом .txt в выбранное приложение.

APK не содержит движки или стратегии Pocket и не является официальным приложением sevcator. Первая установка модуля выполняется через Magisk; начиная с SV Pocket 1.1.0 обновить установленный модуль можно из приложения. CI выдаёт APK с временной debug-подписью для проверки. Перед передачей пользователю, начиная с test2, APK подписывается постоянным ключом Pocket TV вне CI. Ключ и его резервная копия хранятся приватно вне репозитория. Будущие обновления должны подписываться этим же ключом. Ключ первой версии test1 не сохранился, поэтому переход test1 → test2 требует переустановки только APK; модуль Pocket и его выбранная стратегия остаются.

### Исправление curl в 1.0-test2

В упаковке Pocket v71 curl берётся из пакета Termux без его динамических библиотек. На Android без этих библиотек тестер останавливается до первой стратегии. APK теперь включает собственный curl 8.22.0 с OpenSSL 3.5.8, собранный Android NDK для arm64-v8a, armeabi-v7a, x86_64 и x86. libcurl и OpenSSL включены в исполняемый файл; зависимости от Termux отсутствуют. Исходные архивы закреплены SHA-256 в `scripts/build-curl.sh`, лицензии включаются в APK.

Перед новым подбором приложение проверяет встроенный curl и выбор через штатную функцию `resolve_downloader` Pocket. Рабочий резервный curl находится только в `/data/adb/pocket-tv/runtime` и доступен тестеру через PATH; файлы модуля не заменяются. Для TLS используются доверенные сертификаты Android, экспортированные в PEM. Проверка сертификатов включена.

Если подготовка не проходит, сервис и предыдущая сессия не изменяются. Техническая причина сохраняется в журнале. После завершения или ошибки плашка больше не обещает продолжение подбора.

## Сборка

Независимый Gradle-проект. CI сначала собирает собственный curl, затем APK. Для локальной сборки нужны NDK 27.2.12479018, CMake, Ninja и Perl:

```sh
for abi in arm64-v8a armeabi-v7a x86_64 x86; do bash pocket-tv/scripts/build-curl.sh "$abi"; done
cd pocket-tv && ../gradlew testDebugUnitTest assembleDebug
```

Проверки: unit-тесты парсинга и рейтинга; интеграционные тесты реального shell-моста; воспроизведение ошибки оригинального curl v71 и проверка резерва через настоящий resolver Pocket на Android 14; HTTPS с TLS 1.2/1.3 и отклонением недоверенного сертификата; тест кнопок пульта, сохранения результатов и сообщений об ошибке. Тестовый модуль не меняет firewall. Реальное ядро приставки, Magisk, NFQUEUE и работа видео требуют проверки на устройстве.

## SV Pocket 1.2.5 — автоматический быстрый запуск

Если SV Store установил APK поверх прежней версии, системное событие Android
ставит разовую задачу, даже когда экран приложения закрыт. При уже выданном
доступе Magisk она включает быстрый запуск в оригинальных Pocket 71/72.
Задача не запускает и не перезапускает обход; стратегию и рейтинг не меняет.
При занятом модуле она откладывается с ограниченным числом повторов. При отказе
в root фоновая задача не повторяет запросы. Обычная проверка при открытии
приложения подхватывает настройку (в том числе после выдачи доступа).
Отключённый, ожидающий удаления или неизвестный модуль она не меняет.

В **Настройки → Расширенные настройки** можно вернуть обычный запуск.
Выбор сохраняется после обновлений приложения. Фоновая установка нового APK
самим магазином и первый запуск Magisk-модуля остаются отдельными операциями.

## SV Pocket 1.2.4 — быстрый запуск на приставке

В версии 1.2.4 в разделе **Настройки → Быстрый запуск обхода** можно было включить
ускорение штатного запуска Pocket v71/v72. Это настройка установленного модуля:
она действует и в Magisk после перезагрузки, когда приложение закрыто.
Новая служба и фоновый опрос не устанавливаются. В 1.2.4 по умолчанию режим был выключен.

- Модуль по-прежнему ждёт завершения загрузки Android. Для Ethernet и Wi-Fi
  достаточно дважды с интервалом в секунду увидеть тот же рабочий маршрут
  ядра, исходный адрес и поднятый линк. Это признак готовности сети для запуска
  сервиса, а не подтверждение доступа в интернет или воспроизведения видео.
- Если маршрут подтвердить нельзя, остаются штатные внешние адреса. Запросы
  чередуются, их длительность входит в общий бюджет ожидания 120 с. Инициализация
  самого модуля, необязательная загрузка пользовательских списков и работа Magisk
  тоже могут занимать время. Они не входят в замер сетевого ожидания.
- Переключатель доступен только для двух проверенных выпусков с точной контрольной
  суммой common.sh. Запись атомарная, оригинал остаётся целиком в начале файла;
  отключение восстанавливает его байт в байт, с проверкой суммы и прав. При замене
  файлов обновлением настройку проверяют заново; неизвестные файлы не меняются.
- В журнале есть способ и длительность последнего ожидания, с идентификатором
  загрузки. Если Magisk не запустил модуль вообще, эта настройка его не подменяет.
- Каталог выводится одним пакетом. При выборе уже сохранённой стратегии не
  вызывается меню выбора модуля; остановленный обход запускается без лишней
  остановки. Явный повторный запуск работающего обхода по-прежнему доступен.

## SV Pocket 1.2.3 — cold startup status

- Reading status no longer sources Pocket `common.sh`, invokes its CLI, migrates
  its layout, or repeatedly scans processes with `pgrep`. One bounded Android
  process snapshot determines stopped/degraded/running using the module supervisor
  and nfqws process names. No startup/stop/network commands run during inspection.
  If the installed catalog is not initialized yet, bundled strategy files are
  read directly from the original directory without triggering a migration.
- Non-interactive root commands close their stdin immediately. If a command still
  times out, its last confirmed stage and partial response are retained in the
  diagnostic log, with a brief explanation in the interface.
- The journal includes boot uptime, module/strategy, boot-script presence, and
  any still-running module startup command. The boot flag reflects the actual
  service.sh file instead of an unconditional success value. This release does
  not install a second boot service or change the module's startup policy.
- Tests cover EOF-dependent shell commands, root-vs-module timeout diagnostics,
  process classification, a hanging process reader, and cold inspection of full
  original Pocket v71/v72 archives on Android 14 without modifying their files.
- This removes blocking paths and improves diagnosis; the exact failing stage on
  the reported Tanix/Magisk device was not available in the old 1.2.2 error.

## SV Pocket 1.2.2 — status and cancellation responsiveness

- Startup reads module, updater and tester state in one root invocation. For an
  initialized Pocket layout, process predicates replace the migrating CLI status
  command. Catalog hashing uses one batch with the previous fingerprint format.
- Cancellation has an independent executor and acknowledges the cancel flag
  without inspecting the catalog. Late status reads cannot undo that acknowledgement.
- The runner signals its verified, isolated tester process group immediately,
  gives it four seconds to exit, then forces termination if needed. Pocket still
  performs cleanup and restoration; network-dependent service startup can take time.
- The interface distinguishes stopping network tests, cleanup, restarting the
  previous service, and completion. Saved results and original strategy names remain.
- Regression coverage includes stale status/cancel races, cancellation during a
  blocked read, stubborn tester children, restoration, and catalog fingerprint compatibility.

## SV Pocket 1.2.1 — remote navigation fix

The test actions have explicit left/right navigation: YouTube → All services →
Stop (when enabled). Left returns to the sidebar only from the first available
action. Down enters the results; Up from the first result returns to the last
enabled action. Returning from the sidebar restores the previous control, and
opening the test screen initially focuses YouTube. Strategy names, results and
module commands are unchanged. Android instrumentation covers these transitions
with completed results, an empty test, and a running test.

## SV Pocket 1.2.0 — visual design

Native graphite/turquoise surfaces, silver SV branding, consistent action icons and
glass/metal status emblems implement the approved visual concept. The selected
navigation section has a left indicator; the remote's focused control has a separate
outline. Module/strategy commands, ratings and update authentication are unchanged.
Artwork uses Android Canvas/Drawable primitives, has no animation loop or network
assets, and the decorative emblem is omitted on narrower screens to protect text.
Dialogs, results and secondary screens share the same palette. Existing D-pad tests
also capture the focused Home action, Settings and update confirmation dialog.

## SV Pocket 1.1.0

The main navigation is Home, Help and Settings. Home distinguishes a running bypass
from user-confirmed video playback. Help checks connectivity and the module, offers
restart, opens YouTube/SmartTube, records a user's playback confirmation, and can try
the next strategy or restore the last confirmed strategy in the same catalog.
Strategy names remain exactly as supplied by Pocket. The catalog, full tester,
manual controls and log are in Settings → Advanced settings. Back returns to the
previous screen and restores its focus; all actions support a TV remote.

The root bridge reports a fingerprint of the installed strategy files. Test sessions
persist their module version/fingerprint. Older results remain visible but are labelled
as historical when the catalog changes. They are not used as fresh recommendations.
Old 1.0.x history with no fingerprint is preserved and explicitly requires rechecking.

### Updating the installed module

Settings → Update bypass checks the signed release channel, downloads the official
sevcator ZIP, verifies its pinned SHA-256, size, module identity and ZIP structure,
and asks for confirmation. The root bridge repeats the checksum after copying to a
private directory, saves a complete pre-update module archive, then launches
`magisk --install-module` in a detached session. It does not implement its own Magisk
installer. Pocket's installer migrates its own configuration. Closing the activity
must not interrupt installation. A foreground notification covers download/install.

After Magisk stages the update, the UI offers Reboot now or Later. Reboot is never
automatic. After the next boot the bridge checks the actual installed version before
reporting completion. A failed installer is not treated as a successful update even
if it left a staging directory; the log remains available and further changes are
blocked while staged files remain. Initial module installation and Magisk/root
installation are outside this updater's scope.

Automatic *checking* runs when the app opens, at most once per 24 hours, and can be
disabled. There is no unattended installation or background polling when the app is
closed. A cached authenticated catalog (including v72 in this APK) remains available
if GitHub is unreachable. APK updates remain a separate SV Store operation.

### Publishing a reviewed module version

`updates/stable.json` is the signed channel served from this repository's
`feature/pocket-tv` branch. Only explicitly published releases are offered; the
updater never blindly installs the upstream latest release. `updates/release.json`
is the human-readable source. Before promotion, inspect upstream changes, verify
the official asset digest/size and test on the intended TV firmware/provider.

The app embeds only an RSA public key. To publish, update `release.json`, then run
`scripts/sign-module-catalog.py` with the private delivery keystore and password
file supplied via their paths, outside the repository. Signatures cover the exact
payload bytes, including version, minimum app version, URL, digest and size. The
private key/password must never be committed. Future channels can require a newer
SV Pocket build via `minAppVersionCode`. Update the bundled catalog/public key only
as appropriate when releasing a new APK; retain the public key across app updates.

### Verification scope

CI tests root-bridge lifecycle and update guards using isolated fake module/Magisk
commands; it checks the real Pocket 72 ZIP in the Android update flow while replacing
only the Magisk boundary. Instrumentation runs at 1920×1080 / 320 dpi and captures
Home, Help, history, update available and reboot pending screens. These checks do
not install Pocket on a physical Tanix and do not establish provider/video success.

## SV Pocket 1.2.6 — проверка видео

«Подбор: YouTube» сначала проверяет каталог Pocket, после чего приложение на открытом
экране автоматически пробует видимый ролик YouTube группами по пять. Для завершённой
проверки того же каталога есть «Только видеотест». Общий подбор не менялся.
Результат — 20 секунд непрерывного хода медиавремени. При нескольких успешных
кандидатах действует время до первого движения; остальные остаются в списке.
Без победителя приложение возвращает прежний выбор и состояние сервиса.
Выход из плеера приостанавливает проверку; после возврата её можно продолжить.
Неподдерживаемое встраивание или недоступный WebView не считаются оценкой стратегии.

Используется видимый стандартный YouTube IFrame Player API (открытый фильм Blender; резерв — ролик из документации YouTube),
в отдельном процессе WebView без доступа из JavaScript к Android. Исходное приложение
и SmartTube могут работать иначе. Локальный медиатест в CI воспроизводит реальный
поток в WebView тестовой сборки; отдельный запуск штатного адаптера записывает факт
воспроизведения или причину недоступности YouTube на эмуляторе.

Если сам ролик недоступен для встраивания, до первого подтверждённого результата та же стратегия повторяется с резервным роликом. Все выигравшие стратегии в сессии сравниваются на одном и том же видео.

В 1.2.7 сокращена только служебная работа. Приложение опрашивает прогресс с прежней частотой, но читает короткие поля состояния без отдельного процесса для каждого поля и не перезаписывает неизменившуюся историю, если сохранённый файл совпадает побайтно. Обновления результата, журнала и отмены сохраняются сразу. При использовании встроенного curl запрос передаётся с теми же параметрами и набором доверенных сертификатов. Сами проверки Pocket, их порядок, сетевые лимиты и 20-секундный видеотест не менялись.

## SV Pocket 1.2.9 — как выбирается стратегия по видео

Стратегии проверяются по сетевому рейтингу группами по пять, пока ролик не воспроизведут
минимум три разные стратегии. Последнюю начатую группу приложение проверяет до конца,
даже если третья успешная стратегия нашлась в её начале. Затем из **всех** стратегий,
на которых ролик шёл 20 секунд без остановок, включается та, с которой видео началось
быстрее. Если время старта совпало, сохраняется порядок сетевого рейтинга.

Если кандидаты закончились и успешных меньше трёх, приложение возвращает исходную
настройку и состояние сервиса. Прошедшие стратегии остаются в результатах для ручного
выбора. В журнале диагностики указаны результат каждой завершённой попытки и время
старта у прошедших. Это работает и для сохранённой сессии 1.2.8. Новый видеотест
можно запустить через «Подбор: YouTube» → «Только видеотест», если сохранён
завершённый сетевой подбор для текущего каталога.

## SV Pocket 1.2.10 — совместимость с Pocket v73

Приложение распознаёт штатный v73 и v73-sv1 для автоматического быстрого запуска и видеотеста. Сохраняются проверки исходного файла, сети, сессии и выбранной стратегии. Результаты v72 или другой редакции v73 не используются для автоматического выбора после смены модуля: нужен новый подбор. Правила оценки воспроизведения не менялись. Каталог обновлений для клиентов остаётся отдельным.


## SV Pocket 1.2.11 — совместимость с Pocket v74

Приложение распознаёт v74 и v74-sv1, сохраняя поддержку 71–73. Сверяет файл быстрого запуска, а для видеотеста — версию, редакцию и список стратегий. Оценка от предыдущего модуля не включит новую стратегию. Для обычного просмотра после обновления достаточно сохранённой стратегии. Каталог обновлений не меняется.
