# Ethernet Link v2 — 2.0-test2

Android-приложение для чтения согласованной скорости Ethernet через USB‑C адаптер.
Целевой телефон для проверки: Samsung S24 Ultra, Android 16. APK: arm64-v8a, Android 8.0+.

## Test2: прямое чтение Realtek RTL8153

Проверка test1 на Samsung Android 16 подтвердила: разрешение INTERNET выдано,
сокет открывается, но sysfs и все SIOCETHTOOL-запросы возвращают EACCES.
Оценка NetworkCapabilities 100000 Kbps не доказывает линк 100 Мбит/с.

Для USB VID:PID `0bda:8153` добавлен отдельный метод USB Host API. Кнопка
«Разрешить USB» открывает стандартный запрос Android. После согласия читается
текущий PHY status адаптера через endpoint zero:

* IN / VENDOR / DEVICE (`0xc0`), request `0x05`;
* value `0xe908` (PLA_PHYSTATUS), index `0x0133` (PLA | BYTE_EN_WORD);
* 4 байта, little endian; два свежих чтения с таймаутом 500 мс на каждое;
* LINK_STATUS `0x02`, 10/100/1000 `0x04/0x08/0x10`, FULL_DUP `0x01`.

Протокол проверен по Linux v6.6 `r8152.c:ocp_read_word/rtl8152_get_speed` и
`include/linux/usb/r8152.h`. `Rtl8153Status` — самостоятельная реализация
протокола. Нет OUT-запросов, claimInterface, setConfiguration, reset, изменения
OCP page register или отсоединения сетевого драйвера. Открытый дескриптор
закрывается в finally. Доступ без разрешения не выполняется.

USB-результат относится к самому адаптеру: программа не приписывает его eth0,
поскольку Android может закрывать сопоставление USB и сетевого интерфейса.
При нескольких RTL8153 программа просит оставить один. Неполные ответы,
0xffff, неоднозначные флаги скорости, смена линка и отключение устройства во
время чтения не превращаются в «1000». Разрешение проверяется через
UsbManager.hasPermission после ответа Android и при каждом обновлении.
После отключения адаптера Android может потребовать разрешение заново.

Test2 обновляется поверх test1: applicationId тот же, versionCode 3, ключ подписи
тот же. Реальная работа USB-запроса на телефоне пока требует повторной проверки.

## Что исправлено

В v1 был только ACCESS_NETWORK_STATE. Native-код открывал AF_INET/SOCK_DGRAM без
INTERNET и скрывал ошибку. Добавлено нормальное разрешение INTERNET: оно требуется
для открытия сокета даже при локальном ioctl без отправки пакетов.

Способы чтения: sysfs speed/carrier/duplex; современный ETHTOOL_GLINKSETTINGS
с двухэтапным согласованием размера буфера; прежний ETHTOOL_GSET. ETHTOOL_GLINK
проверяется до и после чтения скорости. Есть IPv6 socket fallback и проверка
существования интерфейса через SIOCGIFINDEX. eth0/eth1 проверяются и без DHCP,
если Java не перечисляет интерфейс без IP-адреса.

* Число в главной карточке — только скорость из драйвера/sysfs.
* NetworkCapabilities bandwidth показан отдельно как оценка и не используется
  для выбора 100/1000. AOSP может использовать фиксированные значения.
* Нет интернета / нет DHCP не мешает прямому чтению линка.
* Явный link down скрывает старую скорость; неизвестный статус отличается от down.
* Противоречивые скорости во время автосогласования не выдаются за точное число.
* Проверки выполняются последовательно в фоне. Результаты из прошлого состояния
  Activity и до сетевого события отбрасываются.
* Диагностика содержит errno по каждому native-методу, ошибки sysfs, модель
  телефона, USB VID/PID и название адаптера. Не читает USB serial, IP или MAC.
* Приложение не запускает Speedtest, не меняет сеть, не запрашивает root,
  не отсоединяет USB-драйвер и не отправляет отчёты автоматически.

## Установка и проверка

v1 подписывался временным debug-ключом CI. Его закрытый ключ не сохранён.
Поэтому v2 имеет отдельный applicationId `com.slawa.ethernetlink.v2` и устанавливается
рядом с v1. Подпись v2 создаётся отдельно и сохраняется вне публичного репозитория.

Проверить на телефоне:

1. Один и тот же гигабитный USB адаптер: известный порт 1000 Мбит/с, исправный кабель.
2. Известный порт 100 Мбит/с или порт с фиксированными 100 Мбит/с.
3. Выдернуть RJ45, затем вернуть: старое число должно исчезнуть и обновиться.
4. По возможности повторить без DHCP/интернета; Wi‑Fi не должен подменять Ethernet.
5. Для RTL8153 нажать «Разрешить USB» и подтвердить окно Android.
6. Если остаётся `?`: «Диагностика» → «Копировать», передать отчёт для анализа.

Настольные тесты и успешная сборка не подтверждают работу драйвера/SELinux Samsung.
Test1 на S24 Ultra: sysfs/ioctl закрыты Android. Прямое USB-чтение test2 на устройстве ещё не проверено.

## Сборка

JDK 17, Gradle 8.9, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.

```sh
bash tests/run.sh
gradle :app:assembleRelease
```

GitHub Actions `.github/workflows/build-ethernet-link.yml` запускает регрессионные
проверки и собирает unsigned release APK. Для установки APK требуется подпись
через Android apksigner. Приватный ключ не включать в git или CI-артефакты.

## Проверки

`tests/native_test.c`: GLINKSETTINGS handshake, запись всех трёх bitmap-буферов под
ASan/UBSan, EACCES, некорректный размер handshake, неизвестная скорость и границы строк.
`tests/LinkDecisionTest.java`: 10/100/1000/2500, обрыв во время чтения, смена скорости,
отсутствие DHCP, отказ доступа, неизвестные значения и отсутствие подмены оценкой Android.
Манифест проверяется на разрешение INTERNET как регрессия исходного дефекта.

## Первичные источники

* https://developer.android.com/reference/android/Manifest.permission#INTERNET
* https://developer.android.com/reference/android/net/NetworkCapabilities#getLinkDownstreamBandwidthKbps()
* https://android.googlesource.com/kernel/common/+/2bc6262c6117dd18106d5aa50d53e945b5d99c51/include/uapi/linux/ethtool.h

`tests/Rtl8153StatusTest.java`: 10/100/1000, duplex, pause flags, короткие и
неудачные передачи, 0xffff, смена скорости, отключение, два свежих чтения,
точные поля единственного разрешённого IN-запроса и конечный таймаут.

USB источники:
* https://github.com/torvalds/linux/blob/v6.6/drivers/net/usb/r8152.c
* https://github.com/torvalds/linux/blob/v6.6/include/linux/usb/r8152.h
* https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#controlTransfer(int,int,int,int,byte[],int,int)
* https://developer.android.com/develop/connectivity/usb/host
