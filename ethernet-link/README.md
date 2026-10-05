# Ethernet Link

Простое Android-приложение для проверки negotiated Ethernet link speed через USB-C → RJ45.

Показывает 10/100/1000 Мбит/с и Full/Half Duplex, если драйвер Samsung/USB-Ethernet разрешает чтение.
Использует sysfs и запасной native SIOCETHTOOL ioctl. Это не Speedtest.

CI: Android APK build enabled.

CI retry: explicit Android SDK path.
