# План поддержки Apple Watch Ultra 2 на Android

Цель: обеспечить максимальную функциональность Apple Watch Ultra 2 Watch7,5 с
watchOS 26.2 (23S303) через Android Bridge и Companion. План охватывает все
группы возможностей этой модели; наличие пункта в плане не означает, что он
уже работает. Полную совместимость с iPhone нельзя обещать до проверки
протоколов, Apple Account, серверных сервисов и требований оператора.

Текущее состояние после387: установлены Bridge0.2.387(587)/Companion1.0.16(17),
1054Java tests/180suites, Flutter65/analyze0, release/app+HAL lint PASS.
Исправлена цепочка отображения setup: отдельные активация, отправленная
синхронизация и ожидание экрана часов; живой HAL больше не означает работу
настройки. Поздние события/квитанции не возвращают старый этап ожидания.
Подтверждение видимого циферблата доступно во время setup и штатно закрывает
соединение перед сохранением normal mode. Новое физическое сопряжение387 пока
не повторялось; действующая активированная пара сохранена, после установки
IDS READY и Connected подтверждены в20:25:19.
Камера→PSK→новая пара→активация ранее
подтверждены реальным протоколом и пользовательским наблюдением циферблата.
Затем пользователь снова сбросил часы. Новая камера-пара создана на385/15
в19:44 (36frames). После программного реконнекта той же пары в19:52 обмен IDS
завершился, активация подтверждена в19:52:31. Автоматическая отправка Wi-Fi
получила APP_ACK в19:52:32 без кнопки; пользователь подтвердил циферблат,
работающий интернет и появление приложений. Обычное подключение той же пары
в19:57 показывает Connected.386 исправляет ошибочный UserDirected=true,
который native wifid преобразует в hiddenState. Исправленный профиль автоматически
передан после reconnect в20:02:25, APP_ACK получен20:02:28. Повторный ADD не
убрал прежнюю подпись; пользователь удалил сеть на часах и отправил заново,
подтвердив исправление. Новые профили корректны; автоматическая миграция старой
записи hidden пока не доказана.
язык на дисплее ещё не подтверждён отдельно.
Язык настройки теперь берётся из подтверждённых свойств самих часов.385 добавляет
автоматическую передачу текущей поддерживаемой Wi-Fi сети прямо в setup после
активации/language/Normal и correlated PrepareInitialSync response; отдельное
operational-подключение и кнопка не нужны. Root-only чтение/кодирование текущей
сети на телефоне PASS1226bytes/sent=false. Fresh-pair доставка доказана APP_ACK,
интернет на часах подтверждён пользователем; источник передачи — setup auto worker.
Статусы остальных feature groups не повышены этим изменением.
[Текущие доказательства](live-20261006-optical-pairing/RESULT.md).

6 октября 2026 пользователь подтвердил: «часы активировались». Рабочая пара
получена на установленной 0.2.332 после дополнительных публикаций через
обычный NPS. Исправление порядка Buddy/PSY включено в текущую 0.2.379.
Автоматическое повторение всей успешной последовательности ещё проверить.
Короткие повторные подключения той же активированной пары подтверждены;
приёмка20reconnect/24h ещё не выполнена.

Предыдущая установка379: Bridge 0.2.379 (579), Companion 1.0.9 (10). Английский UI
вынесен в Android resources/Flutter ARB; English fallback проверен на ru-UA.
[Инструкция по новым языкам](LOCALIZATION.md). Реализованы HCI
ACL credits, защищённый Messenger IPC и наблюдаемое connection state;
997 Java tests/166suites, app/HAL lint (0errors/8+4warnings), debug/release build PASS379;
20 Android SQLite probes PASS379;
Companion:45 Flutter tests/analyze (0issues)/release build PASS1.0.9.
Companion1.0.9: отдельные владельцы app scope, native observations, local library,
platform channels/receipts, archive cache, vector painters и Android Binder.
На телефоне actual Flutter IPC/2faces/refresh delivery stages/Home/Back/reopen
прошли; Bridge/root HAL не перезапускались, ключи пары сохранены. Модели/экраны/
локализация прежние; feature groups не объявлены завершёнными этим рефакторингом.
[Архитектура Companion](COMPANION_ARCHITECTURE.md),
[1.0.9 evidence](live-20261006-companion-refactor/RESULT.md).
379: Bridge разделён на core/hal/app; экран, setup, журнал, Health, Find Phone и
уведомление получили отдельных владельцев. Протоколы и данные сохранены;
та же пара IDS READY, Companion fresh2faces,185original observations/44private
defaults доступны. Back/Open Bridge/Copy log проверены на телефоне. Fresh pairing
не повторялся. Это архитектурное изменение, статус feature groups прежний.
[Архитектура](BRIDGE_ARCHITECTURE.md),
[379 evidence](live-20261006-bridge-refactor/RESULT.md).
378: native Bridge redesign, shared styles/buttons, collapsible setup/tools and
native notification icon/status/Open Bridge/Disconnect. Actual Copy log crash
fixed with a bounded recent tail; full journal stays on disk. Hardware UI QA and
same activated pair reconnect PASS. [Design evidence](live-20261006-bridge-design/QA.md).
Сохранена активированная
пара.376 передаёт текущую WPA2-PSK/CCMP сеть через native wifi.networksync V2 ADD;
пользователь подтвердил наличие сети и Watch join без ввода пароля. Native начальный
триггер — NR devicedidpair; в376 обмен добавлен при operational READY,
в385 также в самой setup-сессии. Кнопка повторной отправки есть в Flutter Companion.
Остальные suites/список сохранённых сетей/change hooks/fresh Setup hardware proof
остаются открытыми. [376 hardware evidence](live-20261005-sync/WIFI-376-HARDWARE-EVIDENCE.md).
Companion показывает полученные native показатели, отдельно отмечает
неизвестные и не превращает QUEUED в ложный APPLIED. Hardware Binder subscribe
и переход connected=false→true подтверждены.
Request UUID/epoch/deadline проходят HAL; точный IDS app ACK запроса реестра
подтверждён в Companion. Correlated response реестра и применение его изменений
пока не наблюдались. Native consumers и большинство feature groups ещё предстоят;
цель всего плана остаётся активной.

375: отдельная зашифрованная typed defaults mirror, native category1/105,
приоритет типов, tombstones/updatePolicy2/date merge и доказанная nil→empty
нормализация домена.20проверок реальной SQLite на телефоне,975Java tests PASS.
Седьмая свежая передача44→51events: protected1integer реально сохранён/прочитан;
обычные4пары требуют следующего свежего пакета после исправления nil-domain.
После обновления/reconnect authenticated query13observations+1mirror, хеши
и активированная пара сохранены. Mirror не означает принятие native data+
anchors/sequence/identity/grants; клинические consumers/full transaction/replies
и остальные26групп/9этапов остаются открытыми.
[375 evidence](live-20261005-sync/HEALTH-375-DEFAULTS-MIRROR-EVIDENCE.md).

374: incoming manager preflight build/protocol17/finished Restore/both native
pairing UUIDs/current epoch/direction. Native Watch manager and iOS counterpart
pass original changes into engine; forwarding speculative copies happens later.
Replay44twice/72object-bearing historical changes held STALE_EPOCH, all profile/
protocol/identity matches true; query13/hash/пара сохранены. New native data+
anchor transaction, per-change partial failure, obliteration history and replies
ещё открыты.956tests/lint/assemble PASS, no new pull/reply.
[374 evidence](live-20261005-sync/HEALTH-374-MANAGER-PREFLIGHT-EVIDENCE.md).

373: native engine-stage policy sequence/range/received3/validated5/dependencies/
speculative/version, exact ASM and uint32 range schema. Реальные37cipher records
пяти исходных волн разобраны60раз:40control-eligible/20static Source/Device
unsupported speculative plans. Это локальная модель engine, не ответы часов/
причина Error3. Atomic data+anchor backend/native manager/reply ещё открыты;
тот же typed query13/hash/пара.946tests/lint/assemble PASS.
[373 evidence](live-20261005-sync/HEALTH-373-RECEIVE-CONTROLS-EVIDENCE.md).

372: точная342-slot таблица HealthKit23S303 (267defined/75reserved), native
type/class/canonical-unit mapping с проверкой build/64-bit code. Authenticated
read-only query AES-GCM/AAD/variant+object HMAC, owned collection selector/lifetime.
На телефоне13original records:6Quantity+1Category definitions matched,6canonical
units/finite values; original value/unit отсутствуют. После upgrade/force-stop
тот же query/hash/пара; четыре исходных передачи дают30retained cipher events,
те же13строк. Grants/native data+anchors/value conversion/Companion ещё открыты.
[372 evidence](live-20261005-sync/HEALTH-372-TYPED-QUERY-EVIDENCE.md).

371: зашифрованная SQLite-база исходных наблюдений Health, UUID/HMAC dedupe,
AES-GCM/AAD, immutable variants, bounded worker вне main thread. Реальные три
передачи Watch из сохранённых23cipher records дают13строк:6Quantity UUIDs,
1Category UUID, Source1/Devices2/defaults2/protectedDefaults1. Повторные replay,
upgrade и force-stop/start не добавляют дубли; private24576B/hash неизменны.
Это OBSERVED, не принятие данных протоколом/клинических измерений. Actual Watch
speculative/sequence/anchor transaction chain исследована; native data+anchors,
grants, type/unit mapping, query/Companion/Health Connect остаются открытыми.
[371 evidence](live-20261005-sync/HEALTH-371-ENCRYPTED-DATABASE-EVIDENCE.md).

370: точные Source/Device/defaults/ObjectCollection/sample/provenance/metadata
схемы реализованы; entity identifier выбирает consumer раньше legacy objectType.
Сохранённые18 objectData blobs двух исходных передач368/369 разобраны на телефоне
без ошибок: в каждой передаче шесть QuantitySample и одна CategorySample с
полными UUID/type/date headers. Уникальность и clinical/type/unit mapping ещё
не проверены; replay старого epoch не превращается в свежие/принятые данные.
Пара, Health files/hashes, cipher inbox и циферблат retained. Atomic database,
anchors, authorization, dedupe/query/Companion measurements ещё впереди.
[370 evidence](live-20261005-sync/HEALTH-370-OBJECT-DECODE-EVIDENCE.md).

369: native Health ChangesRequested4/empty outgoing Finished2 отправлен после
принятой Restore; реальный ответ Continue1 и44 reported anchors расшифрован.
Watch также передаёт speculative Changes: Sources/Devices/defaults/QuantitySamples/
CategorySamples. Сохранены оригинальные encrypted records; objectData blobs ещё
не декодированы в измерения. Field11 syncIdentity в ответе допустим для read-only
наблюдения, не усваивается. Исправление установлено после истечения первой
сессии; EXPIRED не превращён в успех через replay, автоматического resend нет.
Восстановление пары/старого циферблата и сохранность Health state подтверждены.
Данные+anchors транзакционно не приняты; вся группа Health остаётся открытой.
[369 evidence](live-20261005-sync/HEALTH-369-CHANGES-EVIDENCE.md).

368: первая реальная нативная Restore-заявка Health принята часами.
415B зашифрованный ответ проверен по подписи, расшифрован и сопоставлен
с Health/Restore/IDS UUID/epoch/sequence1/version17; Finished2 сохранён.
Health state/исходный encrypted inbox сохранены при software reconnect,
новая Restore не отправлена; прежний циферблат retained. Локальный NR UUID
привязан по native ASM, сборка из authenticated pairing record, модель из
свежего MiniStore; отсутствующие peer build/pairingID не подменены. Измерения,
authorization/grants, Changes/anchors и query/database ещё не приняты.
[368 evidence](live-20261005-sync/HEALTH-368-FIRST-NATIVE-RESTORE-EVIDENCE.md).

367 добавляет durable Health identity/session store, private ciphertext send
IPC и ClassC request с заранее сохранённым IDS UUID. Native initial empty
outgoing profile=Finished2/sequence1/priority0; ACK не завершает Restore.
Сопоставление локального NRDevicePropertyPairingID ещё не установлено;
initial store/send удержан. Свежий полный ClassC registry snapshot пришёл,
но не прошёл проверку typed subset модели/сборки. Семнадцать новых тестов,
новая APK/реальный reconnect/CompanionUID подтверждены. Health measurements/
anchors/native Restore acceptance ещё открыты.
[367 evidence](live-20261005-sync/HEALTH-367-DURABLE-RESTORE-EVIDENCE.md).

На345 ответы привязаны к ревизии карточки; durable claims записываются до
действий Android и переживают перезапуск. Миграция очереди и5 native appACK
подтверждены на текущей паре, фактический ответ с часов ещё не проверен.
На346 реализованы native About5→6 и поля батареи5/6 точной23S303; два
correlated ответа часов100/charging=false дошли до APK и Companion state.
Это приёмка чтения native показаний; визуальная проверка UI и смена состояния
зарядки ещё впереди. [346 evidence](live-20261005-sync/BATTERY-346-HARDWARE-EVIDENCE.md).
[345 evidence](live-20261005-sync/NOTIFICATION-345-ACTION-CLAIMS-EVIDENCE.md).

347 переносит целиком native About snapshot в Companion: доступное место,
пользовательские приложения, песни, фотографии, заряд и время чтения. Одно
чтение при подключении, ручное обновление без фиктивного успеха, отметка об
устаревших данных через5min, очистка при разрыве связи. Native ответ часов
39911964672B/userApps0/songs0/photos0 подтверждён до Companion; визуальная
приёмка на телефоне ещё не выполнена (keyguard). [347 evidence](live-20261005-sync/DEVICE-347-ABOUT-UI-EVIDENCE.md).

348 исправляет Ping по точному NanoLeash23S303: topic findmylocaldevice,
ClassD, request1 с double Unix timestamp непосредственно при отправке,
response1 с boolean didPlay; неверные duration/vibration/stop3 удалены.
Ответ больше не запускает ложный PingPhone. Native callback часов получен
с didPlay=true; UUID ответа отсутствует, точная корреляция и фактический
звук пока не приняты. ACK не означает звук.
[Native schema](live-20261005-sync/PING-348-NATIVE-SCHEMA.md).
[Hardware](live-20261005-sync/PING-348-HARDWARE-EVIDENCE.md).

349 реализует обратный Ping в APK: private HAL IPC, native response1/2
после реального результата Android, durable claims до эффекта и отказ от
повторного воспроизведения. На заблокированном телефоне локально проверены
AudioTrack/встроенный динамик/завершение, фонарик on/off, отсутствие
разрешения, busy и остановка владельцем. Это localProbe, реальное нажатие
«Найти iPhone» на часах ещё ожидается; группа «Поиск» не закрыта.
Повтор того же локального запроса после реального force-stop/start APK
отказал в повторном звуке; durable claim reload подтверждён на телефоне.
[349 evidence](live-20261005-sync/PHONE-PING-349-EVIDENCE.md).

350 переносит phone-owned observation в Companion, добавляет остановку
сигнала через защищённый Binder и экран разрешения фонарика. Actual Companion
UID read→stop→read на заблокированном телефоне подтвердил остановку звука/
фонарика и active=false; источник localProbe показан явно. Состояние не
подменяется ACK/queue receipt, unknown/stale сохраняются. Визуальный UI,
положительный permission flow и реальная кнопка часов ещё не приняты.
[350 evidence](live-20261005-sync/PHONE-PING-350-COMPANION-EVIDENCE.md).

353 добавляет session-ordered приём реальных циферблатов, bounded native
JSON/ZIP/UUID payloads, zero-based multipart и публикацию durable snapshot
только после успешного END. Отмена/ошибка/пропуски не публикуют staging.
Найденные старые capture gaps50/59 не позволяют считать коллекцию полной;
timestamp overwrite устранён. На OnePlus проверены локальный отказ при gap,
сохранение одной исторической конфигурации после END и reload новым процессом.
Это не live/current inventory. IDS READY06:21:50.709/About52.672 подтверждены;
production face session ещё не пришла. Native запросы V1=0x01/V2=0x65
установлены по исходникам, но не отправлялись: следующий шаг — правильный
sender/header/sequence и current collection в Companion. Outgoing face
operations, resources/complications и остальные группы остаются в работе.
[353 evidence](live-20261005-sync/CLOCKFACE-353-SESSION-EVIDENCE.md).

## Правила покрытия и приёмки

366 добавляет точный read-only Restore decoder, required fields/full64bit/
UUID16B validation и разбор native Health identity/default source/NPS store.
Для Watch23S303 native Health protocol=17. Initial persistent fallback использует
hd_pairingID; отдельный healthDatabaseUUID сохраняется в pairing entity.
Native blacklist match удаляет Health profile; mismatch запускает provenance/
companion-change цепочку. Ничего из этого не отправлено. Actual same-pair
READY/About/Companion read и reload public peer key подтверждены; Health inbox0.
Настоящая сессия/authorization/samples/database/anchors и весь план остаются
открытыми. [366 evidence](live-20261005-sync/HEALTH-366-RESTORE-IDENTITY-EVIDENCE.md).

365 добавляет native Health authorization3/4 decoder и16B inline A-over-C
producer с native integrity tag/canonical hashes. Независимый JCA receiver
проверяет подпись, tag и шифрование; это не реальная приёмка Watch. Saved
peer key действительно загружен после APK restart до нового command12.
HealthDaemon initial sync проходит отдельную Restore1 цепочку; идентификаторы,
version/store и сохранение данных ещё исследуются. Authorization UI/grants,
настоящие Health packets/samples/database/anchors и остальной план не завершены.
[365 evidence](live-20261005-sync/HEALTH-365-AUTHORIZATION-AOVERC-EVIDENCE.md).

364: exact23S303 Health ChangeSet/Change/Status/Anchor/EntityIdentifier parser
сохраняет64-битные значения и отсутствие полей, ограничивает весь вложенный
разбор; objectData остаются непрочитанными binary records. Native wrapper
подтверждает16B AESkey/zero-IV CBC/PKCS7. First live correlated IDS command12
09:20:08.407 сохраняет public Class-A key той же пары; peerInfo=true и
localInfoDelivered=true. Реальных Health packets/decryption/measurements ещё
нет, replay inspected0. Anchors не продвигаются; Health auth/identity/session
consumers и все оставшиеся группы продолжаются. Причина появления ключей
не установлена. [364 evidence](live-20261005-sync/HEALTH-364-NATIVE-CHANGES-EVIDENCE.md).

361 исправляет global request lifecycle: appACK остаётся pending до
response/expiry/disconnect; late reply не оживляет UNKNOWN. Actual Companion
read b7af208a-60d4-4b2e-a973-e2cbec5bdc58 получает ACK08:17:43.861 и
UNKNOWN08:18:43.804 без native response. Это deadline hardware evidence,
не устранение clockface stall. Same-pair READY08:16:47.301/About50.355.
[361 evidence](live-20261005-sync/REQUEST-361-ACK-DEADLINE-EVIDENCE.md).

[Реестр](WATCH_FEATURE_REGISTRY.md):251 IDs/26 групп, R198/I42/H8/E3,
per-function acceptance и native/permission/implementation defaults.
Наличие записи не означает реализацию. Guide leaf audit/процент ещё не
завершены; новые применимые функции добавлять, цель не сужать.
Exact HealthDaemon23S303 извлечён: native10/11=Tinker Pairing/Opt In,
NanoSync envelope содержит UUID/identity/changeSet/status, а не standalone
BPM/rings. Следующая реализация Health требует настоящей схемы.
[Health361 evidence](live-20261005-sync/HEALTH-361-NATIVE-SCHEMA.md).

362 подтверждает native IDSData/LE16 header и PB mappings. Private encrypted
inbox/partial legacySecMP decryption реализованы,819Java tests PASS;
same-pairREADY08:41:21.678/About24.561100/charging=true. Legacy guessed
HealthSyncCodec удалён. ActualpeerInfo/Healthreceipt/sampleDB/anchors ещё
открыты; cryptoG14 — I, не hardwareHealth. [362 evidence](live-20261005-sync/HEALTH-362-INGRESS-EVIDENCE.md).

363 сохраняет Health ciphertext до ключей, повторяет разбор durable inbox
после verified peer identity/reconnect и хранит публичный Class-A ключ с
проверкой pair/local/Watch IDS. V1 записи совместимы; original epoch/IDs
не подменяются.825Java tests/lint/build PASS. Same-pairREADY08:56:06.939/
About08:56:10.478100/charging=true. JW native helpers подтверждают binary
plist, текущий credentials wire не менялся. Actual command12/Healthreceipt/
measurementDB остаются открытыми; статус cryptoI не повышен.
[363 evidence](live-20261005-sync/HEALTH-363-REPLAY-EVIDENCE.md).

358–360 реализуют native NTK Add/Update/Remove/Select/Reorder и безопасную
SY V2 delta-сессию без ResetStore/Setup. Выбор известного native UUID и
копирование Leghorn без внешних ресурсов доступны через защищённый IPC и
Companion. Update/remove/reorder пока только foundation и локальные тесты.
APPLIED требует новой полной коллекции после native END с нужным UUID,
порядком, выбором и конфигурацией; очередь/ACK/END ответа команды недостаточны.
Старые данные и незавершённая входящая сессия блокируют запись.
Исправлены receiverSyncVersion в START response и абсолютная CF дата
тайм-аута outgoing START по точной прошивке 23S303.
На358 полный read получил135 последовательных блоков0–134, но не END;
после scoped recovery/reconnect нового завершённого чтения пока нет.
На360 READY08:04:13.867/About08:04:16.588, actual Companion сохраняет
исходный observedAt1791259810919. Native mutation не отправлялась;
actual UID selection08:05:20.198 отказана REJECTED до HAL из-за stale
inventory. Применение выбора/копии на часах не принято. Цель всего плана
ACTIVE, остальные группы продолжаются.
[358–360 evidence](live-20261005-sync/CLOCKFACE-358-NATIVE-DELTA-EVIDENCE.md).

357 передаёт committed native inventory по HAL → APK → защищённый Binder →
Kotlin → Flutter. На реальном Companion UID проверено получение UUID,
выбранного циферблата, порядка, полноты и времени исходного наблюдения.
Запрос обновления из Companion получает полный новый ответ: 209 пакетов
0–208 и успешный END 07:10:10.977. Новая отметка 1791259810919 появляется
в Companion после END; локальный QUEUED её не изменяет. При реальном
отключении коллекция очищается.
При настоящем same-version restart READY 07:12:42.640 коллекция возвращается
с тем же observedAt, новым epoch и подтверждением Companion 07:13:52.872.
Галерея сохраняет макеты на телефоне и не утверждает установку на часы.
Это покрытие чтения и обновления коллекции;
выбор/редактирование/добавление/удаление, ресурсы и осложнения ещё требуют
native команд и readback. Весь план и цель ACTIVE.
[357 evidence](live-20261005-sync/CLOCKFACE-357-COMPANION-EVIDENCE.md).

356 получил current native full inventory:209 contiguous batches0..208,
успешный END06:54:02.598; один выбранный Leghorn face/real UUID/config/order.
Проблема unfinished session устранена native scoped cancellation только
синхронизации. Reload и настоящий same-version HAL stop/start сохраняют
complete snapshot; READY06:55:32.493/About34.757. Это приёмка чтения коллекции,
Companion/native mutations/resources/complications ещё открыты. Пара сохранена,
Watch erase/reboot/setup replay отсутствуют. Весь план/цель ACTIVE.
[356 evidence](live-20261005-sync/CLOCKFACE-356-CURRENT-INVENTORY-EVIDENCE.md).

355 закрывает rich descriptors/reset START/unsigned plist integer widths/
durable END gaps. Exact209-batch local replay complete=true/one Leghorn face;
не live receipt. Новый запрос принят06:47:39.143, START пока нет; native
producer/предыдущая unfinished session исследуются. Пара сохранена. Goal
ACTIVE, current collection/Companion/native edits и все остальные группы
продолжаются. [355 evidence](live-20261005-sync/CLOCKFACE-355-DESCRIPTOR-SESSION-EVIDENCE.md).

354 native full collection0x65 принят часами06:33:11.769. Приняты START и
batches0..66; rich descriptor в67 остановил contiguous receive. Нет END/
current snapshot. Исследованы99 live graphs и exact ClockKit coders.
Пара сохранена; reset/reboot/setup replay не отправлялись. Следующая работа:
bounded descriptors, новая live коллекция и Companion.
[354 evidence](live-20261005-sync/CLOCKFACE-354-LIVE-REQUEST-EVIDENCE.md).

Для каждой отдельной функции завести запись: идентификатор, native service,
зависимости, версия прошивки, направление передачи, реализация в HAL/APK,
элемент Companion, разрешения, тестовый сценарий и ссылка на evidence.
Разделять автономную работу часов и интеграцию с Android.

Статусы: исследование, реализация, проверено на часах, внешняя зависимость,
не применимо к модели. Не заменять внешнюю зависимость словом «готово».
Покрытие перечня и долю проверенных функций считать отдельно. Начальный
процент готовности неизвестен: инвентаризация и проверки ещё не завершены.

В каждом релизе проверять необходимые режимы: заблокированный телефон,
погашенный экран, фон, отсутствие связи, восстановление связи, повторные
сообщения, обновление приложения и перезапуск процесса. Функция готова,
когда пользовательское действие реально выполнено, а не только получен ACK.

## Обнаруженные пробелы исходной версии 0.2.332

- `RootBluetoothHalHost.handleIncomingProtobufEvent` выводит
  NOTIFICATION_DISMISS/NOTIFICATION_REPLY без идентификаторов и текста;
  HEALTH_UPDATE без разобранных данных. Это не завершённая интеграция.
- `AppleWatchNotificationListenerService` выбирает уведомление для ответа и
  удаления по package, а не по точному publisherBulletinId. При нескольких
  чатах одного приложения действие может попасть не в то уведомление.
- `BridgeIpcDispatcher` — singleton внутри процесса. У HAL app_process и APK
  разные экземпляры; для событий необходим настоящий межпроцессный канал.
  Начальные battery=92 и face=wayfinder не являются показаниями часов.
- Android Companion возвращает DELIVERED через 200 мс после broadcast.
  Убрать искусственные успехи, связать статус с реальными событиями операции.
- Наличие `HealthSyncCodec` и `TelephonyRelayCodec` не доказывает соответствие
  полному протоколу 23S303. Сначала сопоставить native схемы и реальные пакеты.
- В журнале текущей пары есть разрыв 00:49:43 reason=0x08 и неудачное
  восстановление до 00:50:23. Пользователь подтвердил активацию; это отдельный
  результат от устойчивости фоновой связи.

## Полный перечень работ

В таблице 26 групп; все входят в цель. Последовательность реализации — 9 этапов.

| Группа | Объём Android интеграции и проверки | Этап |
|---|---|---|
| Настройка и пара | PIN, настоящая активация, Setup до циферблата, передача Wi-Fi networks/passwords, сохранение пары, повторное подключение, несколько пар и выбор активной, перенос и восстановление | 1 |
| Транспорт | Bluetooth/HAL, NR, IDS классы защиты, Wi-Fi, выход часов в интернет через телефон, DNS, смена сети, очереди, большие ресурсы, восстановление передач | 1–2 |
| Состояние устройства | Настоящие батарея/зарядка/состояние аккумулятора, модель, версия, память, связь, ошибки и диагностика | 2 |
| Фоновая работа APK | Foreground service, уведомление состояния, восстановление после смерти процесса и запуска телефона, энергопотребление, ограничения Android | 2 |
| Уведомления | Все приложения, группировка, обновление и удаление, иконки/вложения, настройки по приложениям, звук/вибрация, Focus, действия и быстрые ответы | 3 |
| Общение | Контакты, SMS, история, ответы, входящие/исходящие звонки, принятие/отклонение/завершение, реальный аудиоканал; интеграции мессенджеров через доступные действия Android | 3–4 |
| Apple общение | iMessage, FaceTime Audio, Walkie-Talkie, Check In, NameDrop: отдельное исследование идентификации, аккаунта и сервисных зависимостей | 9 |
| Время и системные настройки | Время/часовой пояс, язык/регион, единицы, ориентация, яркость/AOD, текст, звук/haptics, жесты, Action button, раскладка приложений, авиарежим и режимы энергосбережения | 2, 5 |
| Циферблаты | Получение реальной коллекции, добавление/изменение/удаление/порядок, активный циферблат, фото, усложнения, обмен и Smart Stack | 5 |
| Повседневные данные | Календарь, напоминания, заметки, почта, погода, акции, приливы, будильники и их расписания; синхронизация изменений в обе стороны и конфликтов | 5 |
| Автономные инструменты | Проверка Alarm, Timer, Stopwatch, World Clock, Calculator, Flashlight, Tips, Memoji и ввода текста; добавить управление из APK там, где существует протокол | 5 |
| Активность и тренировки | Шаги, расстояние, энергия, кольца/цели, нагрузка, все применимые типы тренировок, планы, интервалы, зоны пульса, беговые/велосипедные/плавательные метрики, GPS-маршруты, датчики и GymKit | 6 |
| Здоровье | История и живые измерения пульса, ECG, oxygen с учётом варианта устройства, температура, сон/оценка/апноэ, Vitals, цикл, лекарства, Noise, Mindfulness, дневной свет, Handwashing и доступные health alerts | 6 |
| Хранилище здоровья | Native Health sync/anchors, UUID, источники, единицы, время, исправления/удаления, шифрованные данные, дедупликация; база APK, экспорт и Health Connect для поддерживаемых типов | 6 |
| Ultra и навигация | GPS, Compass, Waypoints, Backtrack, карты/маршруты/offline maps, Depth, температура воды, журнал погружений, настройки Action button и Siren | 5–6 |
| Медиа | Now Playing для Android, play/pause/seek/volume, музыка/подкасты/аудиокниги, перенос файлов и удаление, фотографии, Voice Memos, Music Recognition, Bluetooth аудиоустройства | 7 |
| Камера и удалённое управление | Camera Remote с Android camera API, предпросмотр/затвор; Remote, Shortcuts, доступные команды умного дома и управление другими устройствами | 7, 9 |
| Приложения | Каталог установленного, storage, AppConduit/installation proxy, подписанные приложения, установка/обновление/удаление, WatchConnectivity и companion данные, native App Store отдельно | 8–9 |
| Siri и интеллект | Автономные команды/диктовка/Translate, запросы через сеть, Android действия; native Workout Buddy, перевод сообщений и summaries отдельно по требованиям watchOS 26 | 7, 9 |
| Поиск | Ping в обе стороны, реальный звук/вибрация; Find My для людей/устройств/AirTag, Lost Mode и точный поиск исследовать отдельно | 2, 9 |
| Безопасность и доступность | Passcode/wrist detection, Medical ID, Fall/Crash Detection, SOS, AssistiveTouch, VoiceOver, Zoom, RTT, Live Listen, braille/клавиатура и Mirroring; автономные и телефонные пути проверять раздельно | 5, 9 |
| Apple Account и облако | Вход, 2FA, состояние account, iCloud/CloudKit, Home, Handoff, разблокировка Mac/iPhone, сервисы подписок и региональные приложения | 9 |
| Wallet и платежи | Passes, билеты/транспорт, ключи; Apple Pay, Apple Cash/Card, bank provisioning и Secure Element требуют отдельной подтверждённой цепочки | 9 |
| Cellular | eSIM, operator entitlement, тариф, совместный номер, Dual SIM, звонки/данные без телефона и состояние модема | 9 |
| Семейные режимы | Family Setup/Apple Watch For Your Kids, Schooltime, отчёты, ограничения и семейные Apple сервисы — отдельный режим, не смешивать с текущей обычной парой | 9 |
| Обслуживание | Backup/restore, OTA download/verify/install, штатное восстановление, экспорт диагностики; unpair/erase только явным действием владельца | 8 |

Контроль полноты: пройти каждую функциональную главу
[руководства watchOS 26](https://support.apple.com/en-gb/guide/watch/welcome/26/watchos/26)
и каждый применимый аппаратный пункт
[Ultra 2](https://support.apple.com/en-us/111832), добавить отдельные сценарии
в реестр. Возможности других моделей и отсутствующие аппаратные компоненты
не включать в знаменатель. Региональные и account ограничения сохранять.

## Последовательность релизов

6 октября начато выполнение всей программы как активной цели. На телефоне
0.2.334: scoped owner confirmation сохранено для существующей пары, ordinary
reconnect получил IDS CONTROL_READY01:42:43.649 и OPERATIONAL IDS READY.780.
Setup/Albert/Buddy replay запрещён отдельным operational invocation. Это
первая проверка обычного подключения, а не завершение этапа24h/20reconnect.

Изменения0.2.335 включены в336: bounded versioned incoming IPC
уведомлений/Health/телефонии сохраняет payload, type и response; APK принимает
данные в своём dispatcher без записи содержимого в журнал. Новых consumer
схем для Health/звонков это само по себе не реализует. Убраны mock battery/face
и ложное подтверждение изменения циферблата при одном создании команды.
335 вошла в установленную336. Operational foreground service336 владеет HAL
после закрытия Activity; требует той же подтверждённой активированной пары.
Проверены upgrade с сохранением app data, IDS reconnect02:04:03.828, закрытие
Activity, автоматическое восстановление service после SIGKILL APK с новым
IDS ready02:05:15.048 и короткий screen-off контроль. Root file lease отвергает
второго владельца HAL; закрытие stdin останавливает старый root. Local очередь32
и command policy защищают обычный режим от setup команд и stdin injection.
666 tests/lint/assemble PASS. First setup остаётся в lab Activity, полный
Companion IPC/request IDs/достоверные статусы и24h/20 reconnect не завершены.

337 installed: Poll/Final recovery и WAIT_F, SREJ(P1) cumulative prefix ACK,
RNR suppress timer copies; bounded IKE deferral до Final.672 tests PASS,
включая полные30 frames/wrap и deferred authenticated IKE между двумя
endpoints. Live CONTROL_READY02:17:19.108 и ordinary IDS ready.397, на
02:18:27–30 outstanding несколько раз0, ACK снова двигаются. Прежнее
наблюдавшееся зависание336 изменилось; долговременный результат не доказан.
Monitor/MaxTransmit/SAR и validity несогласованных supervisory ACK ещё
исследовать; текущий transportSettled getter не включает deferred IKE/WAIT_F.
Поздний контроль02:20:18–21 снова показывает outstanding19→22/ReqSeq14;
надёжное устранение stall не подтверждено, транспорт остаётся приоритетом.

| Этап | Текущее состояние | Следующий обязательный результат |
|---|---|---|
| 1 Настройка и reconnect | Частично реализовано и проверено на текущей паре | Закрепить успешный NPS путь автоматически, проверить20 reconnect |
| 2 Сервис и IPC | Foreground service, signature-protected Messenger, HCI credits, bounded HAL queue/tracker; request UUID/epoch/deadline и точный app ACK проверены на часах340; process recovery/короткий reconnect проверены | Correlated native responses и применение, батарея/Ping, boot/24h/20 reconnect |
| 3 Уведомления |344 зашифрованная очередь/IDs/tombstones привязаны к паре; actual APK SIGKILL восстановил4pending, четыре appACK сохранилиpending0. Второй SIGKILL восстановил offline removal. Native handshake и exact lights correlation работают, played=false | Видимость/реальный reply/dismiss и исчезновение карточки, revision action IDs/durable action dedup, native обновление текста, worker I/O, settings/filter/attachments |
| 4 Звонки и SMS | Исследование предстоит | Native schemas, Android Telecom и двусторонний аудиотракт |
| 5 Настройки и повседневные приложения |351: exact23S303 NPS для3 настроек/IPC/UI, ACK времени;352: native NTK/SY codecs и durable journal перед ACK, historical phone storage probe | Matching settings readback, живой clockface receiver/полная коллекция и её применение/изменения, multipart/session recovery, остальные настройки и данные |
| 6 Health и спорт | Native Data IPC/inbox/partial SecMP362; pre-key retention/replay/key persistence363 | Реальные peer keys/Health receipt, anchors/база/экспорт, полноценная выгрузка |
| 7 Медиа и управление | Предстоит | MediaSession, camera, файлы и управление |
| 8 Приложения и обслуживание | Предстоит | Native AppConduit, подпись, backup/OTA |
| 9 Apple и операторские сервисы | Отдельные исследования предстоят | Проверить account/entitlement/server requirements каждой функции |

352 восстанавливает точную цепочку nanotimekitd и13 операций
NTKDSyncMessage. Реальные архивы конфигурации/complications/цветов разобраны;
пакеты сохраняются в журнал пары до подтверждения. На OnePlus проверены
fsync/атомарная запись/повтор после нового процесса для historical capture.
Новых clockface пакетов после установки пока нет; живую приёмку, полную
коллекцию и применение операций ещё проверить. Журнал не означает APPLIED.
[352 evidence](live-20261005-sync/CLOCKFACE-352-NATIVE-JOURNAL-EVIDENCE.md).

351 реализует три native NPS настройки: положение руки, инверсию экрана и
24-часовой формат. Правильные домены/root binary plist/Apple2001 timestamp,
typed Binder→HAL, per-key ordering/deletion/session clearing и интерфейс
наблюдений. Реальная команда формата получила app ACK, но matching readback
не получен, поэтому применения не утверждаем. Старая NPS команда циферблата
отклоняется: нужен протокол коллекции. About теперь подтвердил изменение
charging=false→true. [351 evidence](live-20261005-sync/NATIVE-SETTINGS-351-EVIDENCE.md).

### Этап 1 Закрепить настройку и обычное подключение

Сохранить логи, рабочий APK, идентичность пары и последовательность успешной
активации в защищённом локальном архиве. Внести в автоматический путь именно
проверенный NPS порядок; отдельно проверить гипотезу об observer race из 333.
Разделить first setup и обычную работу: после завершения Setup reconnect не
должен повторно запускать Buddy, активацию или fresh pairing.

Приёмка: автоматический Setup до циферблата на отдельном разрешённом прогоне;
текущая пара сохраняется при обновлении APK, смерти процесса и 20 обычных
reconnect. Не сбрасывать работающие часы ради теста новой пары.

### Этап 2 Рабочий сервис и данные устройства

Выделить transport/session lifecycle из Activity. Ввести versioned IPC между
HAL, APK и Companion с request ID, ограниченной очередью, backpressure и
событиями accepted/sent/acknowledged/applied/failed/unknown. Очистить mock
показания и искусственные статусы успеха. Сначала подтвердить штатные schemas
NR и network relay; затем реализовать интернет, батарею и Ping.

Приёмка: 24 часа фоновой связи, включая screen off, Doze, смену сети и
дистанции, без потери пары; реальная батарея и Ping; сетевой запрос с часов.
Зафиксировать расход батареи и объём трафика.

### Этап 3 Уведомления и ответы

Сверить BulletinDistributor sender/receiver с 23S303. Хранить точную связь
Android notification key ↔ publisherBulletinId ↔ sectionId. Декодировать
payload действий; передать идентификаторы и RemoteInput через IPC обратно
в NotificationListener. Добавить настройки приложений, update/remove,
фильтрацию, вложения и восстановление очереди. Убрать двойную отправку.

Приёмка: несколько чатов одного приложения; ответ приходит нужному адресату,
удаляется нужное уведомление, новое обновляет существующее; offline/reconnect
не создаёт дубликатов. Проверить обычные Android уведомления и выбранные
мессенджеры на устройстве.

### Этап 4 Телефон и сообщения

Contacts/SMS sync, роль и разрешения Android, состояния звонков и действия
через Telecom. Аудиотракт исследовать отдельно: сообщение «входящий звонок»
само по себе не передаёт микрофон/динамик. Проверить native telephony schemas,
выбор аудиомаршрута, mute и завершение с обеих сторон.

Приёмка: входящий и исходящий звонок с двусторонним звуком на часах; корректные
состояния после отказа/завершения; SMS и контакты не расходятся после reconnect.

### Этап 5 Настройки и повседневные приложения

Текущий шаг358–360: исходящие native encoders всех пяти базовых face
операций, коррелированная delta-сессия, freshness/pair/epoch guards и
readback predicate готовы в коде. Companion подключает выбор и копию
resource-free Leghorn. Для hardware приёмки сначала восстановить полный
read/END, затем копия→реальный UUID/order/config readback→выбор исходного
UUID→новый readback. Original не удалять. Это ещё не приёмка редактирования,
ресурсов, фото или усложнений; локальную галерею не выдавать за installed.
Текущий входящий read остановился после135 блоков; staged данные не
используются для записи или нового времени наблюдения.

Сверить реальные NPS domains/keys/value types. Разделить подтверждённую
запись настройки и локальную optimistic UI. Поддержать конфликты двухсторонней
синхронизации. Реализовать коллекцию циферблатов, календарь/напоминания,
заметки, weather/maps adapters и применимые системные параметры. Автономные
возможности проверять без лишнего переписывания прошивки.

Приёмка: изменение на каждой стороне отражается на другой и сохраняется
после перезапуска; усложнение показывает реальные обновляемые данные;
offline режимы сохраняют доступные данные.

### Этап 6 Health и спорт

361–363 разобраны exact HealthDaemon/Foundation/MessageProtection/IDScredentials/JW. Native
IDSData header/IDs1/2/7 NanoSync mapping/envelope и legacycrypto подтверждены.
Encrypted inbox,pre-key retention/replay/durable public peer identity и partial RSA-contained SecMP decryption реализованы;
actual peer keys/receipt, version/authorization/UUID/nativechanges/status/
anchors/sampleDB ещё открыть. Старые TYPE10/11/defaults удалены362.
G14.crypto пока I; остальные G12/G13/G14 R. Health DB reset/commands не отправлены.

Разобрать реальную Health/CompanionSync цепочку, включая key protection,
anchors, change sets и durable receipts. Не считать текущие условные типы
10/11 полной схемой HealthKit. Сохранять оригинальные типы, UUID, timestamps,
units, sources и deletion records в базе; обновлять anchor атомарно с данными.
Добавить отображение, экспорт и Health Connect после проверки соответствия
типов. Разделить сбор на часах, onboarding функций и синхронизацию Android.

Приёмка: контрольная тренировка с маршрутом, ночь сна и измерения переносятся
без потерь/дубликатов; прерванная выгрузка продолжается; единицы и время
совпадают. Применимость медицинских функций проверить по SKU/региону и
штатным требованиям; не выдумывать измерения и не подменять onboarding.

### Этап 7 Медиа и управление

Android MediaSession ↔ Now Playing, file/resource transfers, аудио/фото,
Voice Memos, camera preview/shutter, применимые Siri/Shortcuts команды.
Раздельно исследовать локальные файлы и DRM/подписки. Для каждой функции
подтвердить соответствующий IDS service, schema и flow control.

Приёмка: медиауправление действует на выбранный проигрыватель, файлы
совпадают по hash, запись возвращается на телефон, камера реально снимает;
передача большого файла возобновляется после разрыва.

### Этап 8 Приложения и обслуживание

Изучить AppConduit, подпись/entitlements, установку и WatchConnectivity;
создать тестовое подписанное watchOS приложение для проверки, если есть
необходимая возможность подписи. Добавить полный цикл backup/restore и OTA
только после воспроизведения native manifests, verification и rollback rules.
Подписанное watchOS приложение и Android APK — разные форматы.

Приёмка: приложение устанавливается/запускается/обменивается данными/
обновляется/удаляется; backup восстанавливает проверенный набор; OTA проходит
проверку и сохраняет пару на отдельном разрешённом испытании.

### Этап 9 Apple сервисы и операторские функции

Вести отдельные исследования Account/iCloud, iMessage/FaceTime/Walkie-Talkie,
Find My, Wallet, eSIM, Home, continuity, Family Setup и Intelligence. Для
каждого получить цепочку аутентификации, server API, требования подписей и
entitlements, региональные ограничения и практический результат. Android
аналог описывать как отдельную функцию, а не как работающий Apple сервис.

Для watchOS 26 Apple прямо связывает Workout Buddy, Live Translation в
Messages и notification summaries с поддерживаемым iPhone с Apple
Intelligence. Это внешняя зависимость текущего native пути; собственный
Android обработчик потребует отдельной интеграции.
[Требования Apple](https://support.apple.com/en-lamr/121115).

Приёмка: реальные авторизованные операции на сервисе/у оператора. Если
доступного пути нет, сохранить точный blocker и частичную доступность;
процент native совместимости не завышать. Для SOS проверять настройки и
транспорт безопасными тестовыми сценариями без настоящего emergency call.

## Ближайший набор изменений

1. Зафиксировать достигнутую активацию и successful wire sequence; включить
   её в APK после проверки обычного подключения текущей пары.
2. Устранить повторный Setup при reconnect; восстановить штатный live канал.
3. Реализовать структурированный HAL ↔ APK ↔ Companion IPC и честные статусы.
4. Довести уведомления, точные dismiss/reply и Ping до проверки на часах.
5. Завести реестр отдельных функций из таблицы и очередь работ по каждому
   протоколу, с зависимостями и проверяемым результатом.

Каждый следующий релиз закрывает измеримые сценарии и обновляет реестр.
Сроки реализации Apple сервисов устанавливать после исследования доступности,
а не обещать заранее полную замену iPhone.
