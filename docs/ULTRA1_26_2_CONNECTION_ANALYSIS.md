# Ultra 1 vs Ultra 2: подключение на watchOS 26.2

Дата исследования: 13 октября 2026. Сравнивается сборка **23S303** на обеих
моделях. Это статический разбор прошивок; физическое сопряжение Ultra 1 пока
не проверено. Документ не расширяет список протестированного оборудования.

## Результат

Отдельного формата сопряжения Ultra 1 в изученных компонентах не обнаружено.
Семь исполняемых компонентов Ultra 1 **побайтно совпадают** с `arm64_32`-срезами
тех же компонентов в полном пакете Ultra 2. Все **101 определения IDS-служб**
совпадают после разбора plist. В девяти библиотеках совпадают наборы строк
протокола, имён классов и методов; различия инструкций ограничены полями
адресов и переходов, подходящими под перемещение внутри shared cache.

Главные подтверждённые препятствия в исходном подключении — фильтр только
`Watch7,5` и проверка только чипа `0x8310`. Ultra 1 имеет **Watch6,18 / 0x8301**.
Нельзя исправлять это подстановкой идентичности Ultra 2: следует выбирать
профиль по объявленной модели и проверять его по аутентифицированным данным.

Дополнение по [issue #4](https://github.com/LordixDemon/apple_watch_connector/issues/4):
тестер уже расширил фильтр на `6,18`, прошёл PIN/IKE и остановился при ожидании
SMP Pairing Response. Следовательно, снятие модельного gate само по себе
недостаточно. Этот отдельный блокер требует проверки перехода IKE → SMP.

Равенство совместимого 32-битного кода в пакете Ultra 2 не означает, что
физическая Ultra 2 исполняет именно этот срез: её основной cache — `arm64e`.
Также одинаковый код может выбирать разные ветки по ChipID и возможностям
устройства. Наличие общего кода не заменяет проверку на настоящей Ultra 1.

## Прошивка и происхождение

Скачан **полный OTA**, без prerequisite build, непосредственно с Apple CDN:

- [Apple CDN: полный пакет Ultra 1 / 23S303](https://updates.cdn-apple.com/2025FallFCS/mobileassets/093-42363/DEA934B3-D945-41A3-8883-A5DB305198ED/com_apple_MobileAsset_SoftwareUpdate/d3e2eade43b0e9d1c2f66c67fc73692c4746716b489e4698f21cb20871b5462b.aea).
- Размер: **3 690 987 520 байт**.
- SHA-256: `01cf9bac60ff3fcaedc0a713f3ac5b3f74623ba21b66291e8b92c2e857c3587f`.
- Манифест внутри пакета: `product_version=26.2`,
  `product_build_version=23S303`, `supported_product_types=[Watch6,18]`.
- AEA расшифрован `ipsw 3.1.706`, извлечены payload, dyld cache, службы и
  определения IDS. Библиотеки разделены штатным `dsc_extractor` из Xcode.
- Для сравнения использован ранее сохранённый полный OTA
  `Watch7,5_23S303_full.aea`; его манифест отдельно проверен на `Watch7,5 / 23S303`.

Исходные пакеты, извлечённые бинарники, cache и Ghidra-проекты остаются
локальными исследовательскими материалами вне публичного репозитория.
В репозиторий добавлены этот разбор и воспроизводимый инструмент сравнения.

## Различия оборудования

Данные следующей таблицы прочитаны из build identities обоих OTA.

| Поле | Ultra 1 | Ultra 2 | Значение для подключения |
| --- | --- | --- | --- |
| ProductType | `Watch6,18` | `Watch7,5` | Объявление при поиске и проверка authenticated identity |
| ApChipID | `0x8301` | `0x8310` | Проверка ChipID и расчёт совместимости |
| ApBoardID | `0x26` | `0x02` | Различие платы; не надо добавлять в пакет сопряжения без соответствующего поля протокола |
| ApTarget | `N199AP` | `N210AP` | Аппаратный профиль; не подменять модель часов |
| Основной пользовательский ABI/cache | `arm64_32` | `arm64e`, плюс совместимый `arm64_32` в пакете | Ширина указателей при анализе; не ширина сетевых полей |
| Bluetooth firmware | `BT_FW_m9.im4p` | `BT_FW_m11.im4p` | Разные контроллеры; параметры HCI/L2CAP требуется наблюдать на устройстве |
| Wi-Fi firmware | `wifi_armv7r_m9_rtkit_image.im4p` | `wifi_armv7r_m11_rtkit_image.im4p` | Разный аппаратный backend; IDS-профиль передачи сети совпадает |

## Сравнение компонентов

Сравнение выполнялось по **одной архитектуре**. Для универсального Mach-O
Ultra 2 извлекается соответствующий срез; SHA всего fat-файла здесь не подходит.

| Компонент | Результат Ultra 1 vs arm64_32 Ultra 2 |
| --- | --- |
| `Applications/Setup.app/Setup` | Полное побайтное совпадение |
| `usr/sbin/bluetoothd` | Полное побайтное совпадение |
| `usr/libexec/nanoregistryd` | Полное побайтное совпадение |
| `usr/libexec/terminusd` | Полное побайтное совпадение |
| `IDS.framework/identityservicesd.app/identityservicesd` | Полное побайтное совпадение |
| `PBBridgeSupport.framework/PBBridgeSupport` | Полное побайтное совпадение |
| `PairingProximity.framework/PairingProximity` | Полное побайтное совпадение |

SHA-256 трёх основных компонентов Ultra 1, совпадающий с SHA соответствующих
срезов Ultra 2:

```text
bluetoothd       3414506d876c328fcbee44a516ef4c849baecc9b46bed72e70442e981ecef34f
nanoregistryd    f78ab9c921ceb01c775172e5603aeb8aac97c8b6e40cc9f669c7e18f4153b547
terminusd        e8927d3dff3f7aae52c58fa0909d5984fa4ac5f78e36f60b0a093ed1120cbb6d
```

Дополнительно проверены библиотеки `NetworkRelay`, `NanoRegistry`,
`NanoSetupSupport`, `NanoPairedSync`, `NanoPreferencesSync`, `IDSFoundation`,
`IDS`, `Sharing`, `CoreBluetooth` из обоих 32-битных cache.
В каждой совпадают все четыре доступных набора:
`__cstring`, `__objc_classname`, `__objc_methname`, `__oslogstring`.

Побайтно библиотеки cache не совпадают: различается размещение данных и
переходов к общим функциям. Инструмент проверяет изменённые 32-битные слова
инструкций: opcode и регистры сохранены, меняются только адресные immediate
поля `ADRP`, `ADD/SUB`, unsigned load/store и `B/BL`.
Это **кандидаты на relocation**, а не формальное доказательство эквивалентности:
инструмент не разрешает каждую конечную цель ссылки, не сравнивает все данные
и не исполняет код.

## Цепочка подключения и найденные условия

| Этап | Прошивка / наблюдение | Что различается и что проверить в Companion |
| --- | --- | --- |
| 1. Объявление для setup | `NanoRegistry` содержит общий код `WatchSetupAdvertisementIdentifier` и `WatchSetupExtendedMetadata` | Модель в metadata должна быть `6,18`; strategy и pairing version нужно брать из фактического объявления |
| 2. Выбор устройства | Общие службы Bluetooth и pairing proximity | Наш Java-фильтр отбрасывает Ultra 1 до начала аутентификации |
| 3. NetworkRelay / PIN или optical auth | Общие `NRNetworkRelayPair`, `terminusd`, `PairingProximity` | Отличий алгоритмов в сравненных срезах не обнаружено; реальные предложения IKE, фрагментацию и параметры канала подтвердить на Ultra 1 |
| 4. Создание пары и IDS | Общие `nanoregistryd`, `identityservicesd`, `IDS`, `IDSFoundation` | Сохранять фактическую идентичность; ключи и состояние Ultra 2 не переносить |
| 5. Версии совместимости | `NRPairingCompatibilityVersionInfo` выбирает минимум в том числе по ChipID | Версии не считать свойством названия Ultra; не подставлять известный tuple Ultra 2 без проверки |
| 6. Class D / Class C и свойства | Те же классы и ключи NanoRegistry | Проверять `ProductType=Watch6,18`, `ChipID=0x8301`, runtime ModelNumber и capabilities выбранного профиля |
| 7. Активация | Общие службы пары и IDS; идентичность оборудования различается | Activation request строить по аутентифицированным значениям этой пары; успешный ACK на Ultra 2 не подтверждает Ultra 1 |
| 8. Настройки / Wi-Fi / initial sync | Общие PSY/NPS библиотеки и совпадающие определения IDS-служб | Передавать сеть автоматически в существующем setup lifecycle; готовность определяется наблюдениями часов |
| 9. Завершение setup | `Setup` и `PBBridgeSupport` побайтно совпадают | Сохранить подтверждение выхода из setup; не объявлять OPERATIONAL только по активации |
| 10. Повторное подключение | Общие daemon-срезы и библиотеки транспорта | Отдельно проверить восстановление той же пары, D/C и IDS после отключения/перезагрузки |

Декодер `WatchSetupExtendedMetadata` проверен отдельно: принимает минимум
7 байт и извлекает pairing version из верхних 6 бит первого байта, product
major/minor из следующих битовых полей. Это соответствует нашему
`WatchSetupMetadataCodec`; ABI с 32-битными указателями не меняет этот wire layout.
Декодер идентификатора по-прежнему принимает 4 байта. Нового формата metadata
для `Watch6,18` в этих функциях не обнаружено.

### Конкретные функции Ultra 1

Адреса ниже несдвинутые, для **arm64_32 Ultra 1 / 23S303**; это не адреса
арм64е-декомпиляции Ultra 2 и не offsets protobuf.

| Функция | Адрес | Установленное поведение |
| --- | --- | --- |
| `-[NRNetworkRelayPair _generateWatchSetupExtendedMetadata]` | `0x0001bc88` | Получает runtime compatibility version и формирует metadata через `WatchSetupExtendedMetadata` |
| `-[NRPairingDaemon pairingCompatibilityVersionInitWithCollection:]` | `0x00027898` | Делегирует инициализацию версий, вместо единого зашитого номера в рекламе |
| `-[NRPairingDaemon minPairingCompatibilityVersion]` | `0x0003fee0` | Делегирует чтение минимальной версии объекту compatibility info |
| `-[NRPairingDaemon maxPairingCompatibilityVersion]` | `0x0003ff1c` | Делегирует чтение максимальной версии тому же объекту |
| `+[NRLocalPropertyMonitor initializeGetters]` | `0x000879dc` | Создаёт runtime getters для свойств, а не описание вымышленной Ultra 2 |
| `-[NRPairingCompatibilityVersionInfo minPairingCompatibilityVersionForChipID:name:defaultVersion:]` | `0x29698ce8` | Разбирает override-строку по идентификатору чипа, иначе возвращает переданный default |
| `-[WatchSetupAdvertisementIdentifier packedIdentifierData]` | `0x296986f4` | Сериализация setup identifier |
| `-[WatchSetupExtendedMetadata packedExtendedMetadataData]` | `0x29698ad4` | Сериализация расширенной setup metadata |

Точные версии из рекламы и authenticated IDS/property exchange **Ultra 1
на устройстве не измерены**. Диапазон `25..26` в нашем коде относится к текущему
профилю iOS 26.6 / Ultra 2; совпадение сборки watchOS не даёт права считать
этот диапазон подтверждённым для новой пары Ultra 1.

### IDS-службы

Каталог `System/Library/IdentityServices/ServiceDefinitions` содержит одинаковые
101 имени plist в обоих пакетах. Для каждого сравнивались разобранные значения,
а не форматирование XML/bplist. Нет добавленных, удалённых или изменённых
определений. В частности совпали:

- `com.apple.private.alloy.clockface.sync`;
- `com.apple.private.alloy.bulletindistributor` и `.settings`;
- `com.apple.private.alloy.wifi.networksync`;
- `com.apple.private.alloy.location.wifitilesync`.

Это подтверждает общие определения сервисов, но не доступность каждой функции
на любом оборудовании: runtime capabilities и разрешения проверяются отдельно.

## Ограничения исходного Bridge 6485f975

Следующие ограничения зафиксированы до изменений модельного отбора. Их
текущее состояние описано в разделе «Внесённые изменения» ниже.

1. [WatchSetupMetadataCodec.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/WatchSetupMetadataCodec.java):
   `isUltra2NetworkRelayCandidate` допускает только major/minor `7,5`.
2. [HciCodec.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/HciCodec.java):
   `AdvertisingReport.isExpectedTarget()` использует этот фильтр.
   [WatchSetupTargetSelector.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/WatchSetupTargetSelector.java)
   не выбирает отброшенное устройство.
3. [AppleWatchSetupCoordinator.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/AppleWatchSetupCoordinator.java):
   `beginUltra2Generation()` использует тот же gate;
   `onFullWatchClassCSnapshot()` требует `Watch7,5` и `0x8310`, иначе выдаёт
   `TARGET_IDENTITY_MISMATCH`.
4. [NanoRegistryClassDCodec.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/NanoRegistryClassDCodec.java):
   `modernIos26_6Ultra2()` использует profile range `25..26`.
   [AppleWatchPostCommitCoordinator.java](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/AppleWatchPostCommitCoordinator.java)
   также проверяет этот диапазон.
5. [Rust advertisement decoder](../crates/watch-protocol/src/advertisement.rs)
   уже декодирует произвольные product major/minor для strategy 4. Это не
   снимает последующий Java gate общего runtime Linux/Windows/Android.
6. [watch_connection_state.dart](../apple-watch-companion/lib/models/watch_connection_state.dart)
   именует только `Watch7,5` как Ultra 2;
   [bridge_observation.dart](../apple-watch-companion/lib/services/bridge_observation.dart)
   привязывает `49 mm` только к этой модели. UI не должен показывать Ultra 1
   как Ultra 2 или выводить возможности по единственному названию.

Дополнительные Health gates на `Watch7,5 / 23S303` следует расширять только
после отдельной проверки профиля Health; это не причина отказывать в самом
сопряжении. Статическое сходство библиотек не доказывает покрытие всех функций.

## Внешняя проверка Ultra 1: issue #4

[Сообщение тестера](https://github.com/LordixDemon/apple_watch_connector/issues/4)
описывает Linux / Intel AX210, сборку `6485f975`, Ultra 1 на `26.2 / 23S303`.
После локального расширения фильтра устройства два свежих запуска дошли через
PIN/2, IKE и private-notify exchange до сохранения checkpoint. Затем запрос
SMP на LE CID `0x0006` остался без ответа, и сработал таймаут 10 секунд.
Ни активация, ни Class C identity check ещё не достигнуты.

`protocol=24` в приведённом логе — значение `TERMINUS_VERSION`, которое читает
`ApplePairingNotifyPayloads.parsePeerBatch()`. В нашем codec
`CURRENT_PROTOCOL_VERSION` тоже равен 24. Это **не** advertised pairing version
и не доказательство расхождения с диапазоном NanoRegistry `25..26`.

Последовательность нашего runtime: persist checkpoint → ответ на Watch IKE
request и ожидание ACK → SMP request → ожидание SMP response. Следующая
проверка должна установить состояние Watch Bluetooth на этой границе, реальную
доставку ACL и входящие SMP/security events. Другие значения AuthReq, key
distribution и иной CID в issue предложены как гипотезы; их необходимость
пока не подтверждена. Одного лога TX недостаточно, чтобы установить причину
молчания часов. Полный HCI trace нужен для отделения ошибки доставки/приёма
от несовпадения порядка и состояния процедур.

Этот отчёт тестера уточняет статическое исследование, но не является нашей
собственной аппаратной проверкой. Заявлять готовую поддержку Ultra 1 пока рано.

## Допуск моделей в 0.2.428

Bridge **0.2.428 / 628**, Companion **1.0.86+87**:

- `WatchSetupMetadataCodec.isNetworkRelayAdvertisement()` принимает любую
  модель с корректными полями поддерживаемого NetworkRelay объявления.
  Discovery не отбрасывает часы по диапазону версии setup: несовместимая версия
  выдаёт явный отказ на этапе проверки совместимости.
- `AppleWatchSetupCoordinator.beginGeneration()` связывает новое поколение
  с выбранной моделью. [WatchPairingIdentity](../apple-watch-bridge/core/src/main/java/dev/applewatchandroid/bridge/WatchPairingIdentity.java)
  проверяет совпадение authenticated ProductType с объявлением, валидные
  ModelNumber/ChipID и неизменность уже принятой идентичности. Нет списка
  разрешённых моделей или таблицы обязательных ChipID.
- Новый generation удаляет прежнюю привязку; сообщения старого поколения
  не могут подтвердить новую пару. Locale и capabilities по-прежнему берутся
  из аутентифицированного сообщения, без подстановки ожидаемых значений.
- При реконнекте удалены fallback metadata `Watch7,5 / version 25` и
  синтетический setup identifier. Если setup-рекламы нет, начальная настройка
  использует ProductType из sealed pair record и версию из текущего IDS Hello.
  До получения Hello версия считается неизвестной; data-first восстановление
  повторяет запуск setup после Hello, не подставляя локальную версию телефона.
- Обработчики совместимости и post-commit больше не называются Ultra 2 и
  используют политику текущего phone/protocol backend, не список моделей.
- Логи discovery и identity checkpoint больше не утверждают, что найдено
  `Watch7,5 / 0x8310`, когда выбраны другие часы.
- Flutter [WatchModelCatalog](../apple-watch-companion/lib/models/watch_model_catalog.dart)
  используется только для оформления известных моделей. Ultra 1 показывается
  как Apple Watch Ultra; неизвестные модели сохраняют свой ProductType.
  Отсутствие модели в каталоге не запрещает соединение или функции.
- Общий runtime применяется на Android, Linux и Windows. Rust discovery уже
  работал с произвольными product major/minor; добавлен тест соответствия
  Java/Rust для Ultra 1, Ultra 2, другой серии и неизвестной модели.

Проверены portable Java tests, Linux worker tests, компиляция Windows worker,
35 Flutter tests, Flutter analyzer и 33 Rust protocol tests. Bridge APK
`0.2.428` собран. Это не аппаратная проверка нового сопряжения.

Ограничения протокола сохранены: реализован NetworkRelay strategy 4; для setup
текущий phone backend поддерживает pairing versions `25..26` и требует
PairingSessionID capability. Старые стратегии/версии требуют отдельной
реализации. Модельный отбор не заменяет поддержку другого wire-протокола.
Снятие модельного отбора само по себе не устраняет SMP timeout из issue #4.

## Переход IKE → SMP в 0.2.429

В общем runtime Android/Linux/Windows устранены обнаруженные потери пакетов:

- SMP, приходящий во время control/pairing IKE или до последнего transport ACK,
  сохраняется по активному connection handle в ограниченную очередь. Порядок
  сохраняется; ожидание SMP последовательно читает очередь и живой поток.
- Валидный Security Request (`0x0b`) при ожидании Pairing Response обрабатывается
  как запрос безопасности в уже начатой процедуре. Pairing Request не дублируется,
  исходный deadline не продлевается. На последующих этапах обмена ключами этот
  opcode остаётся неожиданным. Переполнение очереди или поток из более 16
  Security Requests завершает текущую сессию с явной ошибкой.
- Ранние пакеты распределения ключей во время Encryption Change также
  сохраняются с проверкой переполнения. Отложенные буферы обнуляются после
  чтения, при разрыве связи и очистке сессии.
- Логи содержат возраст последнего IKE ACK и отправки SMP request, количество
  входящих/отложенных SMP PDU, состояние ACL credits и именованную причину
  Pairing Failed. Закрытие HCI-брокера передаётся runtime как ошибка транспорта,
  вместо ожидания SMP timeout. Штатное закрытие приложения не считается сбоем.
- Linux Rust-брокер выдаёт `HCI_BROKER_V1` метаданные SMP после успешного
  `send()`/`recv()` на Bluetooth socket; Java переносит только этот ограниченный
  формат в `protocol.log`. Также отмечаются completion/encryption/disconnection
  events во время переговоров и LE Start Encryption command. Нет MAC-адресов,
  PIN, OOB, nonce, ключей или сырых payload. Это не полный HCI dump: continuation
  fragments не разбираются брокером как отдельные SMP заголовки.

`SMP REQUEST SUBMITTED` означает возврат записи в транспорт. На Linux это
запись в pipe брокера; строка `HCI_BROKER_V1:TX ACL ... cid=0x0006 opcode=0x01`
дополнительно подтверждает успешный socket send. Ни одна из этих строк сама
по себе не подтверждает приём запроса часами. `RX` от брокера и runtime позволяет
отделить отсутствие входящего SMP от его потери внутри host parser. HCI
Number Of Completed Packets возвращает credits контроллера, не является SMP ACK.

AuthReq `0x2d`, OOB, Secure Connections, размер ключа 16 и key distribution
`0x07/0x07` сохранены. Автоматических переборов параметров, переключения на
BR/EDR, повторных pairing request, стирания или перезагрузки часов не добавлено.

Проверки: 1210 core Java tests, 23 protocol-runtime tests, включая 21 synthetic
handoff trace; Rust metadata tests и Linux-target Clippy. Прогоны используют
искусственные ACL/events и не подтверждают аппаратную совместимость Ultra 1.
Окончательный результат для таймаута из issue #4 требует нового hardware run.

### Проверка Ultra 1 на Linux

Закрыть Companion, собрать на Linux и обновить установленный брокер вместе с
приложением: Java-only обновление не добавит socket diagnostics в старый broker.
Из корня репозитория:

```bash
(cd apple-watch-bridge && ./gradlew :core:test :protocol-runtime:test :core:desktopRuntime)
cargo test --locked -p watch-transport-linux
python3 tools/build_linux_protocol.py --test
python3 tools/build.py linux
python3 tools/install_linux.py apple-watch-companion/build/linux/x64/release/bundle
apple-watch-companion/build/linux/x64/release/bundle/apple_watch_companion
```

Выбрать нужный адаптер и ожидающие новую пару часы в приложении, пройти PIN.
После попытки сохранить весь `protocol.log`; для быстрой проверки нужных строк:

```bash
rg -n 'PRIVATE-NOTIFIES|SMP |HCI_BROKER_V1|ENCRYPTION|SESSION FAILED|CLEANUP' \
  "${XDG_STATE_HOME:-$HOME/.local/state}/watch-companion/protocol.log"
```

Сначала проверить SMP Pairing Response, публичный ключ/DHKey Check и Encryption
Change. Затем — IDS Class D/C, activation, initial sync, циферблат, автоматический
Wi-Fi и reconnect после закрытия/повторного запуска Companion без удаления пары.
Для Android дополнительно проверить camera pairing; Linux использует PIN.

## Оставшиеся проверки поддержки

1. На физической Ultra 1 проверить PIN, optical pairing, IKE proposals,
   activation, Class D/C, initial sync, Wi-Fi и устойчивый reconnect.
2. Запустить обновлённый runtime и Linux broker, сверить новую диагностику
   перехода IKE → SMP; при сохраняющемся таймауте дополнить полным HCI trace.
3. Подтвердить фактическую версию и chip-specific compatibility policy по
   authenticated IDS/property exchange, не ослабляя проверки несовпадения.
4. После рабочего циферблата отдельно подтвердить notifications, native faces,
   настройки и Health по заявляемым capabilities.

Статическое исследование выполнялось без перепрошивки и изменения рабочей пары.
После изменений кода hardware pairing также не запускался.

## Повторение сравнения

Инструмент [compare_watch_firmware.py](../tools/compare_watch_firmware.py)
использует стандартную библиотеку Python, выбирает срез из fat Mach-O,
проверяет границы структур и сравнивает sections. Пример для ранее извлечённых
корней файловой системы:

```bash
python3 tools/compare_watch_firmware.py \
  --left /path/to/23S303__Watch6,18 \
  --right /path/to/23S303__Watch7,5 \
  --arch arm64_32 \
  --component usr/sbin/bluetoothd \
  --component usr/libexec/nanoregistryd \
  --component usr/libexec/terminusd \
  --component Applications/Setup.app/Setup

python3 -m unittest discover -s tools -p 'test_compare_watch_firmware.py'
```

Для библиотек передать корни результатов `ipsw dyld split` и соответствующие
пути framework. Сравнивать одну и ту же архитектуру; не сравнивать SHA
`arm64_32` Ultra 1 с SHA `arm64e` Ultra 2 как доказательство изменения протокола.

Для собственного извлечения использовать полный OTA, публичный ключ AEA из
[каталога этой версии](https://theapplewiki.com/wiki/OTA_Updates/Apple_Watch/26.2#Apple_Watch_Ultra),
`ipsw fw aea`, `ipsw ota extract` и `ipsw dyld split`. Проверка модели и
сборки по внутреннему OTA manifest обязательна. Актуальный `ipsw dyld extract
--objc` некорректно обработал часть arm64_32 metadata; успешный обход — штатное
разделение cache через Xcode и анализ `AARCH64:LE:32:ilp32`.

Форматы указателей сверялись с первичными исходниками Apple:
[chained fixups](https://github.com/apple-oss-distributions/dyld/blob/main/include/mach-o/fixup-chains.h)
и [shared-cache Objective-C metadata](https://github.com/apple-oss-distributions/dyld/blob/main/include/objc-shared-cache.h).
Для анализа daemon сделана отдельная копия с разрешёнными rebase-указателями;
исходный подписанный бинарник не изменялся. Декомпиляция с `halt_baddata()` и
неверно прочитанными указателями не использовалась как доказательство поведения.
