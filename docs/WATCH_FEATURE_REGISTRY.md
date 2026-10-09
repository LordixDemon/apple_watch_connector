# Реестр функций Watch7,5 / watchOS26.2 / 23S303

Исходный объём: все26 групп [плана](WATCH_FEATURE_COVERAGE_PLAN.md).
Это очередь конкретных функций, не заявление о100% совместимости.
Инвентаризация применимых глав руководства watchOS26/Ultra2 ещё открыта:
новые обнаруженные функции добавлять сюда, отсутствующие не считать готовыми.

Каждая строка — отдельный ID. Поля native service, direction, HAL/APK,
Companion, permission/dependency наследуются из описания группы, пока не
установлена собственная проверенная схема. «Не установлен» означает
неизвестный протокол, а не отсутствующую функцию. Колонка «Приёмка» задаёт
конкретный результат, требуемый дополнительно к общим режимам плана:
фон/keyguard/offline/reconnect/update/restart/repeat/conflict.

Статусы: R=исследование; I=реализация/частичная проверка; H=узкая hardware
приёмка именно указанной строки; E=подтверждённая внешняя зависимость.
У H есть evidence; такой статус не закрывает соседние строки/группу.
R/I не входят в число работающих функций. Процент ещё не вычисляется:
нет завершённого знаменателя и hardware evidence для всех leaf-сценариев.
Факт автономной поддержки не равен интеграции с Android.

## G01 Настройка и пара — этап1

Native: NR/IDS/PBBridge/NPS; двусторонне. HAL/APK: pairing/operational service;
Companion: существующее подключение/PIN. Bluetooth/root; данные пары защищены.
Evidence H: [SUMMARY](../SUMMARY.md), [357](live-20261005-sync/CLOCKFACE-357-COMPANION-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G01.pin | PIN и новое сопряжение | I | Воспроизводимое fresh pairing с правильным/неверным PIN; отдельное разрешённое испытание |
| G01.activation | Активация текущей пары | H | Пользователь подтвердил активацию; current pair действует, повторяемая полная цепочка отдельно |
| G01.setup | Setup до циферблата | I | Автоматически воспроизвести успешную цепочку, не запускать на текущей паре |
| G01.wifi_credentials | Передача Wi-Fi credentials | I | Native V2 ADD реализован; suites/all-saved/change hooks/ранний Setup hook/Companion UI остаются открытыми |
| G01.wifi_current_wpa2 | Текущая сеть WPA2-PSK/CCMP | H | [376](live-20261005-sync/WIFI-376-HARDWARE-EVIDENCE.md): user verified сеть на Watch и подключение без ввода пароля; другие режимы отдельно |
| G01.persist | Сохранение активированной пары | H | Same-pair stop/start сохраняет identity и committed данные |
| G01.reconnect | Устойчивый reconnect | I |20 циклов и24h, offline/процесс/телефон; короткие циклы уже подтверждены |
| G01.multiple | Несколько пар и выбор активной | R | Переключить две разрешённые пары без пересечения данных |
| G01.transfer | Перенос пары | R | Новый Android host читает те же данные и сохраняет identity |
| G01.restore | Восстановление пары | R | Восстановить согласованный набор секретов/состояния на разрешённом тесте |

## G02 Транспорт — этапы1–2

Native: HCI/ERTM/NR/IDS, network/resource пути уточняются; двусторонне.
HAL/APK: RootBluetoothHalHost/IdsModernSessionCoordinator; Companion: состояние
связи. Bluetooth/root/Android network; service topics не равны отдельным TCP.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G02.bluetooth | Bluetooth/HAL/NR | I | Бounded очереди, ACL credits, потери/MTU и долгий фон |
| G02.ids | IDS C/D и классы защиты | I | Каждая нужная служба, lock/unlock и отказ неподходящего класса |
| G02.wifi | Wi-Fi путь | R | Реальный IDS/data обмен при уходе Bluetooth |
| G02.internet | Интернет часов через Android | R | Реальная Watch загрузка через phone network |
| G02.dns | DNS | R | Native resolve и доставка ответа, ошибки/тайм-ауты |
| G02.network_change | Смена сети | R | Wi-Fi↔cellular Android без потери пары/передачи |
| G02.queue | Очереди/корреляция/тайм-аут | I | UUID/epoch/deadline, повтор/late ACK/response;361 hardware UNKNOWN после ACK |
| G02.resources | Большие ресурсы | R | Hash совпадает; bounded multipart и реальные payload размеры |
| G02.resume | Возобновление передач | R | Разрыв в середине ресурса→продолжение без дублирования/потери |

## G03 Состояние устройства — этап2

Native: systemsettings About5→6, NR/deviceinfo; Watch→HAL/APK→Companion.
NSS observation/projection реализованы; подписанный IPC/Bluetooth.
Evidence H: [346](live-20261005-sync/BATTERY-346-HARDWARE-EVIDENCE.md),
[347](live-20261005-sync/DEVICE-347-ABOUT-UI-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G03.battery | Чтение заряда | H | Native100 до APK/Companion, визуальный UI отдельно |
| G03.charging | Чтение charging | H | Nativefalse до APK/Companion; переход true/false отдельно |
| G03.battery_health | Состояние аккумулятора | R | Native capacity/health и отсутствие поля без подстановки |
| G03.model | Модель | I | NR/deviceinfo Watch7,5; полная UI/readback цепочка |
| G03.version | Версия прошивки | I |26.2/23S303 из текущего устройства до UI |
| G03.storage | Свободное место | H | Native About39911964672B до Companion; изменение/визуальная проверка отдельно |
| G03.apps_count | Число пользовательских приложений | H | Native About0 без invented installed list |
| G03.songs_count | Число песен | H | Native About0; перенос музыкальных файлов отдельно |
| G03.photos_count | Число фотографий | H | Native About0; передача фото отдельно |
| G03.connection | Состояние связи | I | Реальная Binder подписка; live/stale/disconnect, не аппаратная connectivity матрица |
| G03.errors | Ошибки и диагностика | I | Bounded экспорт/коррелированный список/архив; полный live sysdiagnose ещё проверить |

## G04 Фоновая работа APK — этап2

Native: operational IDS; HAL/APK foreground service/lifecycle, Companion
subscribes/read/command. Android FGS/boot/энергосбережение; permission flow.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G04.fgs | Foreground service | I | Keyguard/screen off/фон24h без Activity owner |
| G04.status | Уведомление состояния | I | Правильные running/connected/error и действия stop |
| G04.process_death | Смерть процесса | I | Restore без Setup и повторного побочного действия |
| G04.boot | Запуск телефона | R | После reboot Android восстанавливается та же пара |
| G04.power | Энергопотребление | R | Измерить wakelocks/battery/idle за24h |
| G04.restrictions | Ограничения Android | I | Permission denial/background restrictions объясняются, связь восстанавливается |

## G05 Уведомления — этап3

Native: bulletindistributor/settings; двусторонне. HAL/APK identity/revision/
durable mirror/action claims; Companion settings/status. Notification access,
RemoteInput и разрешения конкретного приложения.
Evidence: [345](live-20261005-sync/NOTIFICATION-345-ACTION-CLAIMS-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G05.delivery | Уведомления всех приложений | I | Два разных приложения/несколько chats, фактические карточки на часах |
| G05.group | Группировка | R | Group/summary соответствуют Android |
| G05.update | Обновление карточки | I | Revision обновляется, старое действие не применяется |
| G05.remove | Удаление/dismiss | I | Точный publisherBulletinId в обе стороны, actual Watch tap |
| G05.icons | Иконки | R | Native ресурс отображается, отсутствующий не заменяется чужим |
| G05.attachments | Вложения | R | Фото/ресурс/hash/размер/ошибка загрузки |
| G05.per_app | Настройки по приложениям | I | Native settings readback и действие фильтра |
| G05.sound | Звук | R | Фактический звук и silent policy |
| G05.haptics | Вибрация | R | Фактическая haptic и silent policy |
| G05.focus | Focus | R | Режимы и исключения, двусторонние изменения |
| G05.actions | Действия | I | Actual Watch tap→тот же Android notification revision, restart/dedupe |
| G05.reply | Быстрые ответы | I | Actual Watch текст→RemoteInput нужного чата; не соседнего |

## G06 Общение — этапы3–4

Native: telephony/topic/schema не приняты, контакты/SMS service уточняется;
двусторонне. Existing TelephonyRelayCodec не доказывает native compatibility.
HAL/APK/Companion потоки ещё реализовать. Contacts/SMS/Phone/default dialer/
audio permissions и доступные Android notification actions.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G06.contacts | Контакты | R | Имя/номер/фото, добавление/изменение/удаление/conflict |
| G06.sms | SMS | R | Реальные отправка/приём/status/history |
| G06.history | История сообщений и звонков | R | IDs/timestamps/deletion, повтор без дубля |
| G06.reply | Ответ на сообщение | R | Watch compose/reply→правильный адресат и доставка |
| G06.incoming_call | Входящий звонок | R | Имя/номер/состояние реального Android звонка |
| G06.outgoing_call | Исходящий звонок | R | Watch dial→правильный Android вызов |
| G06.accept | Принять звонок | R | Actual Watch action переводит Android call в active |
| G06.reject | Отклонить звонок | R | Actual Watch action прекращает ringing |
| G06.end | Завершить звонок | R | Actual Watch action завершает active call |
| G06.audio | Аудиоканал звонка | R | Реальный звук/микрофон обе стороны, route/disconnect |
| G06.messengers | Мессенджеры | I | Поддерживаемые RemoteInput/actions, per-app actual Watch result |

## G07 Apple общение — этап9

Native service/auth не установлен; Apple Account/server/entitlement цепочки
исследовать. Android аналог не считается Apple сервисом. HAL/APK/Companion
ещё нет проверенного end-to-end пути.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G07.imessage | iMessage | R | Авторизованные реальные send/receive/history |
| G07.facetime | FaceTime Audio | R | Реальный call/audio/signaling |
| G07.walkie | Walkie-Talkie | R | Invitation/availability/реальный audio peer |
| G07.checkin | Check In | R | Session/status/доставка в безопасном сценарии |
| G07.namedrop | NameDrop | R | Фактический обмен контактом через подтверждённый native путь |

## G08 Время и настройки — этапы2,5

Native: timesync/timezonesync/NPS, domains/keys ещё сверять; двусторонне.
HAL/APK preference receiver/WatchSettingsObservation; Companion native settings
без optimistic success. Подписанный IPC; setting-specific dependencies.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G08.time | Время | I | Native обмен есть; фактическое правильное время/изменение на часах |
| G08.zone | Часовой пояс | R | Реальное изменение→native readback и Watch clock |
| G08.language | Язык | I | Native domain/type/consumer/readback/actual UI |
| G08.region | Регион | I | Двусторонний native readback и региональный эффект |
| G08.units | Единицы | I | Native value и изменение в целевом приложении |
| G08.orientation | Ориентация | I | Crown/wrist/readback и actual UI |
| G08.brightness | Яркость | R | Native настройка/readback/видимый эффект |
| G08.aod | Always On | R | Экран/режим/readback, power state |
| G08.text | Размер/стиль текста | R | Native readback и видимый текст |
| G08.sound | Системный звук | R | Native volume/silent и реальный звук |
| G08.haptics | Системные haptics | R | Native setting и физический эффект |
| G08.gestures | Жесты | R | Настройка и реальное действие |
| G08.action | Action button | R | Native mapping/readback и кнопка |
| G08.layout | Раскладка приложений | R | Native layout и actual launcher |
| G08.airplane | Авиарежим | R | Readback/отключение/возврат связи без потери пары |
| G08.low_power | Энергосбережение | R | Readback, автономный эффект, reconnect |

## G09 Циферблаты — этап5

Native: clockface.sync/SY V2/NTKDSyncMessage; двусторонне. HAL bounded
receiver/writer, APK Binder и Companion native collection panel. Подписанный
IPC, свежая complete collection; ресурсы/complication схема отдельно.
Evidence: [357](live-20261005-sync/CLOCKFACE-357-COMPANION-EVIDENCE.md),
[358–360](live-20261005-sync/CLOCKFACE-358-NATIVE-DELTA-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G09.collection | Получить реальную коллекцию | I | Исторически полный209/END подтверждён; текущий read135/noEND — regression |
| G09.refresh | Обновить коллекцию из Companion | I | Восстановить fresh full END, original timestamp не подменять |
| G09.select | Выбрать активный UUID | I | Native delta→новая complete selection; stale guard проверен |
| G09.add | Добавить/копировать | I | Leghorn duplicate→реальные новый UUID/order/config и END readback |
| G09.update | Изменить | I | Encoder/plan есть; IPC/UI и actual config/readback ещё нет |
| G09.remove | Удалить | I | Encoder/plan есть; IPC/UI и actual absence/readback ещё нет; original сохранить |
| G09.order | Порядок | I | Encoder/plan есть; IPC/UI и actual permutation/readback ещё нет |
| G09.photo | Фото циферблата | R | Ресурсы/hash/actual render, заменённое/удалённое фото |
| G09.complications | Усложнения | I | Rich descriptors читаются; реальный provider/resource/update на Watch ещё нет |
| G09.share | Обмен циферблатами | R | Native export/import, зависимые bundles/resources |
| G09.stack | Smart Stack | R | Порядок/данные/widgets/live updates/readback |

## G10 Повседневные данные — этап5

Native services/schema не установлены; двусторонне, конфликт/deletion/anchors.
HAL/APK adapters/database/Companion ещё не приняты. Android Calendar/contacts/
account permissions и выбранные data providers; онлайн/offline отдельно.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G10.calendar | Календарь | R | Event/recurrence/timezone/edit/delete обе стороны |
| G10.reminders | Напоминания | R | Create/complete/edit/delete/recurrence/conflict |
| G10.notes | Заметки | R | Текст/изменение/deletion/поиск/конфликт |
| G10.mail | Почта | R | Авторизованный inbox/body/actions/read/delete |
| G10.weather | Погода | R | Location/time/unit/native provider и реальное обновление |
| G10.stocks | Акции | R | Символ/время/quotes/native отображение |
| G10.tides | Приливы | R | Координаты/время/units/native отображение |
| G10.alarms | Будильники и расписания | R | Create/enable/recurrence/sound/delete/readback |

## G11 Автономные инструменты — этап5

Автономный Watch путь и Android управление разделены. Native service/HAL/APK/
Companion не установлены; R не отрицает встроенную функцию. Сначала actual
Watch проверка, затем companion command только при подтверждённом протоколе.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G11.alarm | Alarm | R | Автономный trigger/snooze/stop плюс G10.alarms |
| G11.timer | Timer | R | Start/pause/stop/multiple и сигнал |
| G11.stopwatch | Stopwatch | R | Start/lap/stop/reset, автономный результат |
| G11.world_clock | World Clock | R | Города/timezones/DST и native управление |
| G11.calculator | Calculator | R | Реальный ввод/вычисление |
| G11.flashlight | Flashlight | R | Реальный экран/режимы и native управление |
| G11.tips | Tips | R | Запуск/контент/сеть |
| G11.memoji | Memoji | R | Create/edit/display/send где поддержано |
| G11.input | Ввод текста | R | Keyboard/scribble/dictation, реальный ввод в получателя |

## G12 Активность и тренировки — этап6

Native: HealthDaemon/HDNanoSyncManager; настоящая схема исследуется.
Legacy HealthSyncCodec удалён362. Watch→APK data store/Companion ещё нет
верифицированного sample consumer; Health permission/device sensors.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G12.steps | Шаги | R | Actual samples/UUID/time/units и сумма без дублей |
| G12.distance | Расстояние | R | Actual quantity/unit/source и агрегация |
| G12.energy | Энергия | R | Active/basal quantities с original time/units |
| G12.rings | Кольца/цели | R | Реальные значения и native изменение целей |
| G12.load | Нагрузка | R | Native inputs/result/history |
| G12.types | Все применимые типы тренировок | R | Отдельный leaf-сценарий каждого доступного type, не один enum test |
| G12.plans | Планы тренировок | R | Create/sync/edit/start, фактический план |
| G12.intervals | Интервалы | R | Этапы/повторы/сигналы и recorded результат |
| G12.zones | Зоны пульса | R | Native zones/изменение/live/история |
| G12.running | Беговые метрики | R | Все доступные типы quantities/session linkage |
| G12.cycling | Велосипедные метрики | R | Все доступные quantities/sensors/session linkage |
| G12.swimming | Плавательные метрики | R | Length/stroke/distance/time/unit/session linkage |
| G12.routes | GPS-маршруты | R | Coordinates/timestamps/session, gaps/offline |
| G12.sensors | Внешние датчики | R | Actual pairing/live samples/units/source |
| G12.gymkit | GymKit | R | Авторизованный реальный equipment sync/история |

## G13 Здоровье — этап6

Native HealthDaemon/NanoSync, per-type schema/authorization ещё исследовать;
Watch→APK/database/Companion/Health Connect ещё не принято. Регион/вариант
устройства/permissions проверять отдельно; медицинские выводы не генерировать.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G13.heart | Пульс live и история | R | Настоящие samples, source/time/units, не messageID10 |
| G13.ecg | ECG | R | Реальный waveform/classification/metadata/export |
| G13.oxygen | Oxygen | R | Применимость конкретного варианта, actual readings/история |
| G13.temperature | Температура | R | Native quantity/baseline/unit/time |
| G13.sleep | Сон | R | Стадии/intervалы/source/timezones |
| G13.sleep_score | Оценка сна | R | Native result/дата/история |
| G13.apnea | Апноэ сна | R | Применимость/authorization/native results |
| G13.vitals | Vitals | R | Native series/ranges/история |
| G13.cycle | Цикл | R | Samples/edit/deletion/private sync |
| G13.medications | Лекарства | R | Schedule/log/reminders/edit/deletion |
| G13.noise | Noise | R | Actual samples/units/история/alerts |
| G13.mindfulness | Mindfulness | R | Session/log/metadata и sync |
| G13.daylight | Дневной свет | R | Actual quantities/time/source |
| G13.handwashing | Handwashing | R | Actual events/duration/history |
| G13.alerts | Health alerts | R | Каждый применимый alert отдельным безопасным native сценарием |

## G14 Хранилище здоровья — этап6

Native HealthDaemon/HDCodableNanoSyncMessage, health/persistent pairing UUID,
identity/version/changeSet/status; двусторонне. APK database/Companion не
готовы. Шифрованное приватное хранение/authorization/key protection.

375: typed encrypted defaults mirror с native nil-domain normalization/date
merge/tombstones;20реальных SQLite probes PASS, protected1integer из текущей
передачи и authenticated restart read. Это локальный mirror, не native receipt/
anchors/grants или clinical import. Native data transaction и остальные сценарии
группы открыты; статусы строк не повышены.
[375 evidence](live-20261005-sync/HEALTH-375-DEFAULTS-MIRROR-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G14.auth | Health authorization/identity | I |368 actual signed/decrypted Restore Finished2/version17/sequence1 matched; durable registry/store identity and reconnect accepted; sample authorization/grants ещё открыты |
| G14.anchors | Anchors/change sets | I |374 scoped manager preflight before373 ASM-backed engine sequence/range/dependency/speculative/version policy, received3/validated5; actual44retained events/72historical changes held STALE_EPOCH with matching Restore/protocol/identities; native data+anchors/partial-failure/obliteration-history/reply/resume ещё открыты |
| G14.uuid | UUID/источники | I |371 seven unique sample UUID observations scoped to authenticated peer/native identity; source/device/provenance original collections encrypted; cross-reference/type/clinical import ещё принять |
| G14.units | Единицы/время | I |372 exact23S303 HealthKit342-slot catalog/build+full-width code+class gates; phone query6Quantity canonical-unit definitions/finite raw values and1Category matched; NSDate-reference seconds/nil units retained; conversion/display/category semantics ещё принять |
| G14.corrections | Исправления/удаления | R | Tombstones/updates/versioning/confllicts обе стороны |
| G14.crypto | Шифрованные данные | I |371 local original-record database AES-GCM/AAD/HMAC with separate AndroidKeyStore storage keys; phone replay/authenticated queries/restart accepted narrowly; logs counts only; native grants/locked/offline ещё принять |
| G14.dedupe | Дедупликация | I |371 three original sequences/repeated replay/upgrade/force-stop yield same13encrypted observations, seven sample UUIDs/one variant each; clinical correction/deletion/anchor dedupe ещё принять |
| G14.database | База APK | I |372 bounded read-only query authenticates cells/AAD/variant HMAC/recomputed object HMAC, selects exact field/item and closes owned nodes;13records/types read after real force-stop,24576B/hash retained; clinical query/native data+anchor atomic commit ещё принять |
| G14.export | Экспорт | R | Export count/UUID/units/hash соответствуют database |
| G14.health_connect | Health Connect | R | Каждый поддержанный mapping/permission/dedupe/deletion |

## G15 Ultra и навигация — этапы5–6

Native services не установлены; автономный путь отдельно. HAL/APK/Companion
нет принятого end-to-end. Location/maps providers/sensors/permissions.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G15.gps | GPS | R | Реальные fix/precision/time/offline records |
| G15.compass | Compass | R | Heading/calibration/units/actual sensors |
| G15.waypoints | Waypoints | R | Create/edit/delete/readback/navigation |
| G15.backtrack | Backtrack | R | Record/возвращение/маршрут/offline |
| G15.maps | Карты/маршруты | R | Native карты/route/turn cues/Android delivery |
| G15.offline | Offline maps | R | Download/hash/use/delete без сети |
| G15.depth | Depth | R | Применимый безопасный actual sensor log |
| G15.water_temperature | Температура воды | R | Actual sensor quantity/unit/time |
| G15.dives | Журнал погружений | R | Original records/time/depth/units/history |
| G15.action | Action button Ultra mappings | R | Native settings/readback и actual button |
| G15.siren | Siren | R | Настройка/управление и разрешённый actual effect |

## G16 Медиа — этап7

Native services/schema/resource transfer уточняются; двусторонне.
APK MediaSession/DB/Companion не приняты; media/camera/storage permissions,
DRM/account/subscription пути отдельно.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G16.nowplaying | Now Playing Android | R | Правильный active player/metadata/artwork |
| G16.playpause | Play/pause | R | Actual Watch action меняет selected MediaSession |
| G16.seek | Seek | R | Native position/duration и actual playback |
| G16.volume | Volume | R | Actual route volume/readback |
| G16.music | Музыка | R | Transfer/hash/play/delete, DRM отдельно |
| G16.podcasts | Подкасты | R | Episodes/files/play/resume/delete |
| G16.audiobooks | Аудиокниги | R | Chapters/files/play/bookmark/delete |
| G16.photos | Фотографии | R | Transfer/hash/native album/display/delete |
| G16.voice_memos | Voice Memos | R | Actual Watch recording→правильный phone file/hash |
| G16.recognition | Music Recognition | R | Real capture/result и service dependency |
| G16.bluetooth_audio | Bluetooth audio devices | R | Actual pairing/route/playback/reconnect |

## G17 Камера и удалённое управление — этапы7,9

Native services не установлены; двусторонне. APK camera/control adapters,
Companion ещё не приняты; camera/microphone/network и device authorization.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G17.preview | Camera Remote preview | R | Actual Android camera frames/render/latency |
| G17.shutter | Camera shutter | R | Actual Watch tap→реальное сохранённое фото |
| G17.remote | Remote | R | Авторизованная команда целевому устройству/readback |
| G17.shortcuts | Shortcuts | R | Native request→доступное Android действие и результат |
| G17.home | Умный дом/другие устройства | R | Каждый adapter/action/authorization/readback отдельно |

## G18 Приложения — этапы8–9

Native AppConduit/installation proxy/WatchConnectivity/App Store исследовать;
двусторонне. APK/Companion installers нет. WatchOS подпись/entitlements,
account и actual compatible app; Android APK не является WatchOS app.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G18.catalog | Каталог установленного | R | Реальные bundle/version/storage, не About app count |
| G18.storage | App storage | R | Native size/readback/delete effect |
| G18.install | Установка | R | Авторизованный подписанный app installed/launch |
| G18.update | Обновление | R | Native version сменился, данные/пара сохранены |
| G18.remove | Удаление | R | Actual absence и корректные зависимые данные |
| G18.connectivity | WatchConnectivity/companion data | R | Message/file/userInfo/context/reachability/replay |
| G18.store | Native App Store | R | Авторизованные catalog/download/install/account |

## G19 Siri и интеллект — этапы7,9

Native services/account/network не установлены; autonomous и Android adapters
отдельно. HAL/APK/Companion нет. Apple Intelligence требования указаны в плане;
Android alternative имеет собственный ID, не считается native результатом.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G19.siri | Автономные Siri команды | R | Каждый native intent и actual результат |
| G19.dictation | Диктовка | R | Actual текст/язык/receiver |
| G19.translate | Translate | R | Native язык/input/output/offline support |
| G19.network | Siri через сеть | R | Real request/response/account/error |
| G19.android | Siri→Android действия | R | Native authorized intent→точное действие/result |
| G19.workout_buddy | Native Workout Buddy | E | Требования совместимого iPhone/Intelligence из плана; Android путь отдельно |
| G19.message_translation | Native перевод сообщений | E | Требования из плана; real authorized message result ещё нет |
| G19.summaries | Native summaries | E | Требования из плана; real notification summary ещё нет |

## G20 Поиск — этапы2,9

Native findmylocaldevice/NanoLeash для local Ping; остальные Find My services
не установлены. HAL/APK phone effect/durable claims, Companion stop/permission
реализованы. Audio/flash permission; Apple Account/UWB отдельно.
Evidence: [348](live-20261005-sync/PING-348-HARDWARE-EVIDENCE.md),
[349](live-20261005-sync/PHONE-PING-349-EVIDENCE.md),
[350](live-20261005-sync/PHONE-PING-350-COMPANION-EVIDENCE.md).

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G20.watch_ping | Найти часы | I | Native didPlay=true; точная корреляция/фактический звук ещё нет |
| G20.phone_ping | Найти телефон с часов | I | LocalProbe speaker/flash/durable replay проверены; actual Watch tap ещё нет |
| G20.stop | Остановить phone signal | I | Actual Companion stop localProbe проверен; Watch-originated session ещё нет |
| G20.people | Find My люди | R | Authorized location/share/readback |
| G20.devices | Find My устройства | R | Authorized actual device location/action |
| G20.airtag | AirTag | R | Authorized actual tag detection/location |
| G20.lost | Lost Mode | R | Authorized mode/readback без destructive test |
| G20.precise | Точный поиск | R | Actual supported UWB route/result, model applicability |

## G21 Безопасность и доступность — этапы5,9

Native services/domains не установлены; autonomous и phone пути отдельно.
HAL/APK/Companion нет принятой цепочки; owner credentials/permissions.
SOS проверять безопасным способом, без настоящего emergency call.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G21.passcode | Passcode | R | Authorized настройка/lock/unlock/readback |
| G21.wrist | Wrist detection | R | Actual lock/sensors/setting/readback |
| G21.medical_id | Medical ID | R | Private fields sync/display/edit без потери |
| G21.fall | Fall Detection | R | Settings/native status и безопасный documented test |
| G21.crash | Crash Detection | R | Settings/native status, безопасный test без аварии |
| G21.sos | SOS | R | Настройки/transport безопасным test, не real emergency |
| G21.assistive | AssistiveTouch | R | Native setting и actual gestures/actions |
| G21.voiceover | VoiceOver | R | Native setting/actual speech/navigation |
| G21.zoom | Zoom | R | Native setting/actual zoom |
| G21.rtt | RTT | R | Authorized test text-call route и actual data |
| G21.live_listen | Live Listen | R | Actual authorized audio route |
| G21.braille | Braille | R | Применимая keyboard/device и actual input/output |
| G21.keyboard | Клавиатура доступности | R | Настройка/native input/action |
| G21.mirroring | Mirroring | R | Actual frames/input/authorization/latency |

## G22 Apple Account и облако — этап9

Native identity/auth/server paths не установлены; двусторонне. HAL/APK/
Companion нет. Authorised Apple Account/2FA/entitlements/region исследовать;
не объявлять невозможность только из отсутствия текущей реализации.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G22.login | Вход Apple Account | R | Real authorization/session/ошибка/revoke |
| G22.2fa |2FA | R | Реальная approved challenge, secrets не в logs |
| G22.state | Состояние account | R | Actual login/token validity/expiry/logout |
| G22.icloud | iCloud | R | Каждая применимая data function и actual cloud result |
| G22.cloudkit | CloudKit | R | Authorized container/records/conflicts/deletion |
| G22.home | Home | R | Authorized native home/accessory/read/action |
| G22.handoff | Handoff | R | Native authorized continuity session |
| G22.unlock_mac | Разблокировка Mac | R | Real authorized proximity/auth/readback |
| G22.unlock_phone | Разблокировка iPhone | R | Native applicability/authorised actual outcome |
| G22.subscriptions | Подписки | R | Каждый доступный сервис entitlement/actual result |
| G22.regional | Региональные приложения | R | Применимость/availability/actual function per-app |

## G23 Wallet и платежи — этап9

Native provisioning/SE/account paths не установлены; двусторонне. HAL/APK/
Companion нет. Authorised bank/issuer/region/entitlement исследовать.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G23.passes | Passes/билеты | R | Signed pass import/display/update/delete |
| G23.transit | Транспорт | R | Authorized issuer provision/native use/readback |
| G23.keys | Ключи | R | Authorized issuer/device provision и actual supported use |
| G23.apple_pay | Apple Pay | R | Подтверждённая issuer/account/SE chain и authorised actual result |
| G23.cash | Apple Cash | R | Применимость/account/auth/actual authorised operation |
| G23.card | Apple Card | R | Применимость/account/auth/actual authorised operation |
| G23.bank | Bank provisioning | R | Issuer challenge/provision/readback без invented token |
| G23.se | Secure Element lifecycle | R | Реальная attestation/keys/authorised provisioning/revoke |

## G24 Cellular — этап9

Native modem/eSIM/operator entitlement paths не установлены. HAL/APK/
Companion нет. Operator account/tariff/region/device support исследовать.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G24.esim | eSIM | R | Authorized profile provision/native modem/readback |
| G24.entitlement | Operator entitlement | R | Real operator API/auth/entitlement |
| G24.tariff | Тариф | R | Real supported plan/status |
| G24.number | Совместный номер | R | Real operator number-sharing и actual incoming/outgoing |
| G24.dual_sim | Dual SIM | R | Применимость/selection/native status/routes |
| G24.calls | Звонки без телефона | R | Actual modem audio/call/signaling |
| G24.data | Данные без телефона | R | Actual Watch internet без phone connection |
| G24.modem | Состояние модема | R | Native signal/operator/profile/roaming/errors |

## G25 Семейные режимы — этап9

Отдельный режим, не менять текущую обычную пару ради проверки. Native
Tinker/FamilySetup/account paths исследовать; HAL/APK/Companion нет.
Authorised family roles/child account/entitlements/device applicability.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G25.setup | Family Setup/For Your Kids | R | Отдельное authorised setup, ordinary pair сохранить |
| G25.schooltime | Schooltime | R | Real schedule/enforcement/readback |
| G25.reports | Семейные отчёты | R | Authorized real child data/source/time |
| G25.limits | Ограничения | R | Native policies/enforcement/changes |
| G25.services | Семейные Apple сервисы | R | Каждый применимый authorised service отдельно |

## G26 Обслуживание — этап8

Native backup/restore/OTA/manifests/signature/rollback исследовать;
HAL/APK/Companion не приняты. Recovery/erase/OTA application требуют
отдельного разрешённого сценария, активированную пару текущего испытания сохранить.

| ID | Функция | Статус | Приёмка/оставшаяся проверка |
|---|---|---|---|
| G26.backup | Backup | R | Native согласованный snapshot/hash/приватные данные/identity |
| G26.restore | Restore | R | Authorised restore проверенного набора и actual readback |
| G26.ota_download | OTA download | R | Real applicable manifest/payload/hash/resume |
| G26.ota_verify | OTA verify | R | Signature/version/device compatibility/rollback gates |
| G26.ota_install | OTA install | R | Отдельное authorised actual update и preserved pair |
| G26.recovery | Штатное восстановление | R | Exact native flow на отдельном разрешённом испытании |
| G26.diagnostics | Экспорт диагностики | I | Actual bounded native inventory/архив и hash/privacy |
| G26.unpair | Unpair | R | Только явное owner action, фактическое removal/readback |
| G26.erase | Erase | R | Только явное owner action на отдельном испытании |
