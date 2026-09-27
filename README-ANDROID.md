# Zapret для Android

Приложение повторяет логику десктопного zapret из этого репозитория: **каждый `.bat` — это стратегия**,
их можно переключать, автоматически протестировать и выбрать лучшую, а списки доменов/IP редактируются
прямо в приложении.

---

## 1. Как это работает

На Windows пакеты перехватывает `winws.exe` + WinDivert. На Android WinDivert не существует, поэтому
используются два движка — приложение само выбирает подходящий:

| Режим | Чем работает | Требования | Совместимость со стратегиями |
|---|---|---|---|
| **root** | `nfqws` (тот же проект bol-van/zapret, Linux-версия `winws`) + `iptables -j NFQUEUE` | root (Magisk/KernelSU) | 100 % — аргументы `.bat` передаются как есть |
| **без root** | `byedpi` (локальный SOCKS5) + `hev-socks5-tunnel` внутри `VpnService` | ничего | стратегия переводится в аналогичные параметры ByeDPI |

`--wf-tcp` / `--wf-udp` из `.bat` (фильтры WinDivert) в root-режиме превращаются в правила:

```
iptables -t mangle -I POSTROUTING -p tcp -m multiport --dports 80,443,... \
  -m mark ! --mark 0x40000000/0x40000000 \
  -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:8 \
  -j NFQUEUE --queue-num 200 --queue-bypass
```

Остальные аргументы (`--dpi-desync=...`, `--hostlist=...`, `--dpi-desync-fake-quic=...`) `nfqws` понимает
точно так же, как `winws`.

### Что теряется без root

ByeDPI работает на уровне TCP-потока, а не пакетов, поэтому переводятся не все техники:

| zapret | ByeDPI |
|---|---|
| `--dpi-desync=fake` | `--fake -1 --ttl N` (+ `--fake-data` если задан файл-пейлоад) |
| `multisplit` / `split2` | `--split <pos>` |
| `multidisorder` / `disorder2` | `--disorder <pos>` |
| `--dpi-desync-split-pos=sniext+1` | `--split 1+s` (`midsld` → `0+sm`, `host+1` → `1+h`) |
| `--dpi-desync-fooling=md5sig` | `--md5sig` |
| `--dpi-desync-repeats=N` | `--round 1-N` |
| `--hostlist=...` | `--hosts ...` (если включено в настройках) |
| `syndata`, `seqovl`, `ipfrag`, фейки для QUIC | **нет аналога** (частично компенсируется `--udp-fake`) |

Приложение честно показывает эти расхождения в карточке стратегии («Что изменится без root»),
а в настройках можно вписать свою строку аргументов ByeDPI, которая перекроет автоперевод.

---

## 2. Возможности

* **Стратегии** — список всех `.bat` из репозитория, краткое описание техник, число профилей,
  пометка «не рекомендуется», просмотр итоговой команды для обоих движков, импорт/создание своей стратегии.
* **Тест стратегий** — порт `utils/test zapret.ps1`: по очереди включает выбранные стратегии, проверяет
  адреса из `utils/targets.txt` (TLS-рукопожатие + HTTP-ответ + время), сравнивает с контрольным замером
  «без обхода», ранжирует по числу успехов и задержке, показывает лучшую и умеет применить её одной кнопкой.
  Отчёт сохраняется в `files/test_results/` в том же формате, что и на ПК.
* **Списки** — редактор `lists/*.txt`: добавление доменов по одному, вставка из буфера, ручная правка,
  счётчик записей. Файлы `*-user.txt` никогда не перезаписываются обновлением приложения.
* **Разное** — игровой фильтр (аналог Game Filter из `service.bat`), выбор движка, номер очереди NFQUEUE,
  порт SOCKS, DNS и IPv6 для туннеля, автозапуск при загрузке, плитка в быстрых настройках, журнал.

---

## 3. Сборка

### Самый простой способ — `build.py`

Нужны только **Python 3.8+, git и интернет**. Всё остальное скрипт поставит сам
(JDK 17, Android SDK + platform 35 + build-tools + NDK 27 + CMake, byedpi, hev-socks5-tunnel, nfqws):

```bash
git clone https://github.com/DragonFly-LGBT-RGB/Zapret-Android
cd ZapretAndroid

python3 build.py            # Windows: py build.py
```

Готовый файл окажется в `out/ZapretAndroid-debug.apk`. Полезные ключи:

| Ключ | Что делает |
|---|---|
| `--release` | собрать release-вариант |
| `--install` | сразу поставить APK на подключённый телефон через adb |
| `--clean` | пересобрать с нуля |
| `--sdk-dir ~/Android/Sdk` | использовать уже установленный SDK |
| `--skip-native` | не перекачивать byedpi/hev/nfqws |
| `--nfqws-zip путь/zapret-v72.13.zip` | взять nfqws из локального архива |
| `--no-download-jdk` | использовать только системную Java |

Всё скачанное кэшируется в `.build-tools/`, повторный запуск продолжает с того же места.
Первая сборка занимает 10–20 минут (в основном скачивание SDK/NDK ~5 ГБ), последующие — меньше минуты.

**`Permission denied: getsockopt` / `Could not resolve ...` при сборке.** Это не блокировка со
стороны Google: сеть закрыта именно для `java.exe` (фаервол Windows, антивирус, корпоративный
прокси) — обычно при этом падает и `repo.maven.apache.org`. `build.py` сам повторяет сборку
до трёх раз (`--gradle-retries N`) и пробрасывает прокси из `HTTP_PROXY`/`HTTPS_PROXY`.
Если не помогло — разрешите `java.exe` (из каталога JDK, который показал скрипт) исходящие
соединения в фаерволе/антивирусе и запустите `python3 build.py` снова: уже скачанные
зависимости лежат в `%USERPROFILE%\.gradle` и заново не качаются.

**Про Windows.** В `hev-socks5-tunnel` заголовки в `include/` — симлинки, а git на Windows без
прав на их создание кладёт вместо них текстовые файлы с путём внутри (ndk-build падает с
`error: expected identifier or '('` в `hev-object-atomic.h` и т. п.). `build.py` и
`scripts/fetch-native-sources.sh` чинят это автоматически после клонирования, заменяя такие
файлы на `#include`-заглушки. Отдельно включать режим разработчика или права на симлинки не нужно.

### Сборка на GitHub Actions

В репозитории лежит готовый workflow — `ci/android-build.yml` (он просто вызывает `build.py`).
Скопируйте его в `.github/workflows/android.yml` (через веб-интерфейс GitHub: *Add file → Create new file*),
и при каждом пуше APK будет собираться автоматически: вкладка **Actions → Build APK → Artifacts →
ZapretAndroid-debug-apk**.

---

## 4. Использование

1. Установить APK, открыть приложение.
2. Вкладка **Стратегии** — выбрать стратегию (по умолчанию `general`).
3. Главная — **Запустить**. Без root система спросит разрешение на VPN.
4. Если что-то не открывается — вкладка **Тест**: отметить стратегии, «Проверить», затем
   «Применить лучшую».
5. Свои сайты добавлять во вкладке **Списки** → `list-general-user.txt`.

### Замечания

* В root-режиме нужно разрешить приложению доступ root в Magisk/KernelSU.
* Правила iptables снимаются при остановке; при перезагрузке они не сохраняются.
* Режим VPN занимает слот VPN — одновременно с другим VPN работать не будет.
* Стратегии с `syndata`/`seqovl` без root работают хуже — это ограничение уровня, а не реализации.

---

## 5. Сторонние компоненты

| Компонент | Лицензия | Назначение |
|---|---|---|
| [bol-van/zapret](https://github.com/bol-van/zapret) (`nfqws`) | MIT | root-движок |
| [hufrea/byedpi](https://github.com/hufrea/byedpi) | MIT | движок без root |
| [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT | tun2socks для VpnService |

Исходники этих проектов не хранятся в репозитории — они скачиваются скриптом
`scripts/fetch-native-sources.sh` по зафиксированным коммитам/версии.
