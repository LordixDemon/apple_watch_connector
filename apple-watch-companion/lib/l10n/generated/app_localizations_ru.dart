// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Russian (`ru`).
class AppLocalizationsRu extends AppLocalizations {
  AppLocalizationsRu([String locale = 'ru']) : super(locale);

  @override
  String get bluetoothAdapter => 'Адаптер Bluetooth';

  @override
  String get bluetoothController => 'Контроллер Bluetooth';

  @override
  String get refreshBluetoothAdapters => 'Обновить адаптеры';

  @override
  String get bluetoothAdaptersLoading => 'Поиск адаптеров Bluetooth…';

  @override
  String get bluetoothAdaptersEmpty =>
      'Адаптеры Bluetooth не найдены. Подключите адаптер и обновите список.';

  @override
  String get bluetoothAdapterChoiceHint =>
      'Выберите адаптер для часов. Выбор сохраняется для будущих подключений.';

  @override
  String get bluetoothAdapterDisconnectHint =>
      'Отключите часы перед сменой адаптера Bluetooth.';

  @override
  String get bluetoothAdapterMissing => 'Сохранённый адаптер недоступен';

  @override
  String get bluetoothAdapterMissingHint =>
      'Подключите сохранённый адаптер снова или выберите другой. Сопряжение с часами сохранено.';

  @override
  String get bluetoothAdapterDiscoveryFailed =>
      'Не удалось прочитать список адаптеров Bluetooth. Убедитесь, что BlueZ запущен, и обновите список.';

  @override
  String get bluetoothAdapterSaveFailed =>
      'Не удалось прочитать или сохранить настройки адаптера. Проверьте доступ к каталогу состояния приложения и повторите выбор.';

  @override
  String get bluetoothAdapterSaved => 'Адаптер Bluetooth сохранён.';

  @override
  String get opticalScanTitle => 'Создать пару с Apple Watch';

  @override
  String get opticalPairWithCamera => 'Навести камеру на часы';

  @override
  String get opticalScanHint =>
      'Держите анимацию сопряжения внутри рамки, затем нажмите «Сканировать». Не двигайте телефон во время считывания кода.';

  @override
  String get opticalScan => 'Сканировать';

  @override
  String get opticalNoMatch =>
      'Анимация сопряжения не распознана. Наведите камеру на экран часов и попробуйте снова.';

  @override
  String get opticalRecognizedTitle => 'Apple Watch найдены';

  @override
  String opticalRecognizedDetail(String name) {
    return 'Распознан код сопряжения для $name. Следующий шаг — аутентификация по Bluetooth.';
  }

  @override
  String opticalReplaceDetail(String name) {
    return 'Распознан код для $name. Часы должны ожидать новой пары. Предыдущее зашифрованное сопряжение будет сохранено в резервной копии перед заменой.';
  }

  @override
  String get opticalReplacePair => 'Заменить сохранённую пару';

  @override
  String get opticalStartPairing => 'Создать пару';

  @override
  String opticalAnalyzedFrames(int count) {
    return 'Проанализировано кадров: $count';
  }

  @override
  String get opticalPairingLab => 'Лаборатория оптического сопряжения';

  @override
  String get opticalCaptureHint =>
      'Удерживайте анимацию сопряжения в рамке. Захватите короткий образец для проверки декодера. После выхода с экрана образец удаляется.';

  @override
  String get opticalCameraError =>
      'Не удалось запустить камеру. Проверьте разрешение и попробуйте снова.';

  @override
  String opticalFrames(int count) {
    return 'Захвачено кадров: $count';
  }

  @override
  String get opticalCapture => 'Захватить образец';

  @override
  String get opticalRetry => 'Повторить';

  @override
  String get opticalStopCapture => 'Остановить захват';

  @override
  String get backendFeatures => 'Доступные функции';

  @override
  String get backendFeaturesHint =>
      'Доступность отражает сервисы, реализованные текущим бэкендом. Подключение не гарантирует поддержку всех функций, моделей и версий watchOS.';

  @override
  String get backendFeaturesUnknown =>
      'Сведения о доступных функциях ещё не получены от службы подключения.';

  @override
  String get featureNotSupported => 'Пока не поддерживается';

  @override
  String get phoneFindFeature => 'Поиск телефона';

  @override
  String get ok => 'ОК';

  @override
  String get rightWrist => 'Правое запястье';

  @override
  String get rotateScreen180 => 'Повернуть экран на 180°';

  @override
  String get message24Hourtime => '24-часовой формат';

  @override
  String get readyToConnect => 'Готово к подключению';

  @override
  String get workout => 'Тренировка';

  @override
  String get activity => 'Активность';

  @override
  String get heartRate => 'Пульс';

  @override
  String get music => 'Музыка';

  @override
  String get messages => 'Сообщения';

  @override
  String get phone => 'Телефон';

  @override
  String get weather => 'Погода';

  @override
  String get maps => 'Карты';

  @override
  String get battery => 'Аккумулятор';

  @override
  String get message72Bpm => '72 уд/мин';

  @override
  String get message21Sunny => '+21° Солнечно';

  @override
  String get compass => 'Компас';

  @override
  String get message124Se182M => '124° ЮВ • 182 м';

  @override
  String get outdoorRun => 'Бег на улице';

  @override
  String get nowPlaying => 'Сейчас играет';

  @override
  String get calendar => 'Календарь';

  @override
  String get meetingAt1930 => 'Встреча в 19:30';

  @override
  String get custom => 'Пользовательский';

  @override
  String get imported => 'Импортированные';

  @override
  String gb(String value0) {
    return '$value0 ГБ';
  }

  @override
  String mb(String value0) {
    return '$value0 МБ';
  }

  @override
  String kb(String value0) {
    return '$value0 КБ';
  }

  @override
  String b(String value0) {
    return '$value0 Б';
  }

  @override
  String get discover => 'Обзор';

  @override
  String get newInWatchos11 => 'WATCH COMPANION';

  @override
  String get appleWatchUltra2OnAndroid => 'Apple Watch Companion';

  @override
  String get syncNotificationsCallsWorkoutsAndWatchFacesWithYour =>
      'Подключите Apple Watch со смартфона или компьютера. Список сервисов вашего бэкенда можно посмотреть в «Доступных функциях».';

  @override
  String get guidesTips => 'СОВЕТЫ И РАЗДЕЛЫ';

  @override
  String get gesturesControls => 'Жесты и управление';

  @override
  String get doubleTapDigitalCrownAndActionButton =>
      'Двойное сжатие, Digital Crown и кнопка действия';

  @override
  String get notificationMirroring => 'Дублирование уведомлений';

  @override
  String get setUpSmsCallsAndMessagingApps =>
      'Настройка СМС, звонков и мессенджеров';

  @override
  String get resources => 'РЕСУРСЫ';

  @override
  String get halSupportDiagnostics => 'Диагностика и журнал Bridge';

  @override
  String get appleWatchUltra2Controls => 'Управление Apple Watch';

  @override
  String get useDoubleTapTheDigitalCrownAndTheOrange =>
      'Используйте двойное сжатие, колёсико Digital Crown и кнопку действия (на моделях Ultra).';

  @override
  String get essentialGestures => 'ОСНОВНЫЕ ЖЕСТЫ';

  @override
  String get doubleTap => 'Двойное сжатие';

  @override
  String get tapYourIndexFingerAndThumbTogetherTwiceTo =>
      'Дважды соедините большой и указательный пальцы, чтобы ответить на вызов, остановить таймер или открыть Смарт-стопку.';

  @override
  String get turnToScrollThroughListsAndZoomOrPress =>
      'Прокручивайте списки и масштабируйте или нажмите для возврата к циферблату.';

  @override
  String get orangeActionButton => 'Кнопка действия';

  @override
  String get startAWorkoutMarkAWaypointOrHoldTo =>
      'Запустите тренировку, поставьте путевую точку или удерживайте для включения сирены.';

  @override
  String get waterLock => 'Блокировка воды';

  @override
  String get preventsAccidentalTouchesInWaterUseTheDigitalCrown =>
      'Предотвращает случайные нажатия в воде. Удерживайте Digital Crown для разблокировки и удаления влаги.';

  @override
  String get faceGallery => 'Циферблаты';

  @override
  String get save => 'СОХРАНИТЬ';

  @override
  String get reset => 'Сбросить';

  @override
  String get nightMode => 'Ночной режим';

  @override
  String get bezelStyle => 'СТИЛЬ БЕЗЕЛЯ';

  @override
  String get seconds => 'Секунды';

  @override
  String get exportWatchfacePackage => 'Экспорт .watchface';

  @override
  String get cancel => 'Отмена';

  @override
  String get topSubdial => 'Верхний циферблат';

  @override
  String get topLeft => 'Верхний левый';

  @override
  String get topRight => 'Верхний правый';

  @override
  String get center => 'Центр';

  @override
  String get bottomLeft => 'Нижний левый';

  @override
  String get bottomRight => 'Нижний правый';

  @override
  String get bottomSubdial => 'Нижний циферблат';

  @override
  String get bezel => 'Безель';

  @override
  String get import => 'Импорт';

  @override
  String get myWatch => 'Мои часы';

  @override
  String get about => 'Об устройстве';

  @override
  String get general => 'Основные';

  @override
  String get name => 'Имя';

  @override
  String get model => 'Модель';

  @override
  String get modelNumber => 'Номер модели';

  @override
  String get watchCase => 'Корпус';

  @override
  String get watchosVersion => 'Версия watchOS';

  @override
  String get serialNumber => 'Серийный номер';

  @override
  String get networkAddresses => 'Сетевые адреса';

  @override
  String get wiFiAddress => 'Адрес Wi-Fi';

  @override
  String get storage => 'Хранилище';

  @override
  String get available => 'Доступно';

  @override
  String get songs => 'Песни';

  @override
  String get photos => 'Фотографии';

  @override
  String get userApps => 'Приложения';

  @override
  String get action => 'ДЕЙСТВИЕ';

  @override
  String get stopwatch => 'Секундомер';

  @override
  String get backtrack => 'Возврат по маршруту';

  @override
  String get flashlight => 'Фонарик';

  @override
  String get holdForSiren => 'Удержание для сирены';

  @override
  String version(String value0, String value1) {
    return '$value0 • Версия $value1';
  }

  @override
  String get brightness => 'ЯРКОСТЬ';

  @override
  String get textSize => 'РАЗМЕР ТЕКСТА';

  @override
  String get boldText => 'Жирный шрифт';

  @override
  String get wakeOnWristRaise => 'Активация при поднятии запястья';

  @override
  String get wakeDuration => 'Время активности экрана';

  @override
  String get fallDetection => 'ОБНАРУЖЕНИЕ ПАДЕНИЯ';

  @override
  String get crashDetection => 'ОБНАРУЖЕНИЕ ДТП';

  @override
  String get softwareUpdate => 'Обновление ПО';

  @override
  String get orientationTime => 'Ориентация и время';

  @override
  String get leftWrist => 'Левое запястье';

  @override
  String get notReceived => 'Не получено';

  @override
  String get backgroundAppRefresh => 'Обновление контента';

  @override
  String get on => 'Вкл.';

  @override
  String get off => 'Выкл.';

  @override
  String get airplaneMode => 'Авиарежим';

  @override
  String get activityHealth => 'Активность и здоровье';

  @override
  String get noHealthDataReceivedFromTheWatchYet =>
      'Данные о здоровье с часов ещё не получены.';

  @override
  String get watchMeasurements => 'ПОКАЗАТЕЛИ ЧАСОВ';

  @override
  String get bpm => 'уд/мин';

  @override
  String get steps => 'Шаги';

  @override
  String km(String value0) {
    return '$value0 км';
  }

  @override
  String get move => 'Подвижность';

  @override
  String kcal(String value0, String value1) {
    return '$value0 / $value1 ккал';
  }

  @override
  String get exercise => 'Упражнения';

  @override
  String min(String value0, String value1) {
    return '$value0 / $value1 мин';
  }

  @override
  String get stand => 'С разминкой';

  @override
  String hr(String value0, String value1) {
    return '$value0 / $value1 ч';
  }

  @override
  String get activityRings => 'КОЛЬЦА АКТИВНОСТИ';

  @override
  String get standReminders => 'Напоминания о разминке';

  @override
  String get remindsYouToStandAndMove10MinutesBefore =>
      'Напоминает встать и размяться за 10 минут до конца каждого часа.';

  @override
  String get dailyCoaching => 'Подсказки по цели';

  @override
  String get goalCompletions => 'Достижение целей';

  @override
  String get heartBreathing => 'ПУЛЬС И ДЫХАНИЕ';

  @override
  String get highHeartRateNotifications => 'Высокий пульс';

  @override
  String bpm2(String value0) {
    return '> $value0 уд/мин';
  }

  @override
  String get lowHeartRateNotifications => 'Низкий пульс';

  @override
  String bpm3(String value0) {
    return '< $value0 уд/мин';
  }

  @override
  String get bloodOxygen => 'Кислород в крови';

  @override
  String get sleepTracking => 'Отслеживание сна';

  @override
  String get allWatches => 'Все часы';

  @override
  String get connected => 'Подключены';

  @override
  String get disconnected => 'Отключены';

  @override
  String get notifications => 'Уведомления';

  @override
  String get installedOnAppleWatch => 'УСТАНОВЛЕНО НА APPLE WATCH';

  @override
  String get installed => 'Установлено';

  @override
  String get hidden => 'Скрыто';

  @override
  String get notificationIndicator => 'Индикатор уведомлений';

  @override
  String get notificationPrivacy => 'Конфиденциальность уведомлений';

  @override
  String get mirrorPhoneAlerts => 'ДУБЛИРОВАНИЕ УВЕДОМЛЕНИЙ';

  @override
  String get mail => 'Почта';

  @override
  String get sendTestNotification => 'Отправить тестовое уведомление';

  @override
  String get testMessage => 'Тестовое сообщение';

  @override
  String get unlockWithPhone => 'Разблокировка с телефоном';

  @override
  String get wristDetection => 'РАСПОЗНАВАНИЕ ЗАПЯСТЬЯ';

  @override
  String get checkingForUpdates => 'Проверка обновлений…';

  @override
  String get yourAppleWatchSoftwareIsUpToDate =>
      'Установлена актуальная версия ПО Apple Watch.';

  @override
  String get checkAgain => 'Проверить снова';

  @override
  String get alertVolume => 'ГРОМКОСТЬ СИГНАЛОВ';

  @override
  String get silentMode => 'Бесшумный режим';

  @override
  String get hapticAlerts => 'Тактильные сигналы';

  @override
  String get prominent => 'Ощутимый';

  @override
  String get crownHaptics => 'Тактильный отклик Crown';

  @override
  String get systemHaptics => 'Системные тактильные сигналы';

  @override
  String get coverToMute => 'Выключать звук прикрытием';

  @override
  String get watchStorage => 'ПАМЯТЬ ЧАСОВ';

  @override
  String get reclaimable => 'Освобождаемо';

  @override
  String get purgeableData => 'Очищаемые данные';

  @override
  String get watchContent => 'СОДЕРЖИМОЕ ЧАСОВ';

  @override
  String get pairAppleWatch => 'Создать пару с Apple Watch';

  @override
  String get alwaysOnDisplay => 'Всегда включённый дисплей';

  @override
  String get exclusiveAppleWatchUltraFacesWithACustomizableBezel =>
      'Фирменные циферблаты Apple Watch Ultra с настраиваемым безелем, ночным режимом и 8 расширениями.';

  @override
  String get modular => 'Модульный';

  @override
  String get largeInformationBlocksAndGraphsForDataAtA =>
      'Крупные инфоблоки и графики для быстрого просмотра данных.';

  @override
  String get classic => 'Классический';

  @override
  String get traditionalAnalogFacesWithRomanAndArabicNumerals =>
      'Традиционные аналоговые циферблаты с римскими и арабскими цифрами.';

  @override
  String get infographAstronomy => 'Инфограф и Астрономия';

  @override
  String get detailedAstronomicalAndWeatherDisplays =>
      'Подробные астрономические и погодные экраны.';

  @override
  String get activityNike => 'Активность и Nike';

  @override
  String get activityRingsWorkoutsAndSportsMetrics =>
      'Кольца активности, тренировки и спортивные показатели.';

  @override
  String get wayfinderAlpine => 'Путеводитель Альпы';

  @override
  String get wayfinderNightMode => 'Путеводитель Ночной';

  @override
  String get modularDuo => 'Модульный дуэт';

  @override
  String get modularMulticolor => 'Модульный разноцветный';

  @override
  String get california => 'Калифорния';

  @override
  String get californiaOcean => 'Калифорния Океан';

  @override
  String get chronographPro => 'Хронограф Про';

  @override
  String get infograph => 'Инфограф';

  @override
  String get solarDial => 'Солнечный циферблат';

  @override
  String get activityAnalog => 'Активность (стрелки)';

  @override
  String get modularUltra => 'Модульный Ultra';

  @override
  String get activityDigital => 'Активность (цифры)';

  @override
  String get collectionReceivedFromTheWatch => 'Коллекция получена от часов.';

  @override
  String get connectionChangedRefreshAgain =>
      'Состояние подключения изменилось. Обновите ещё раз.';

  @override
  String get waitingForTheWatchFaceCollection =>
      'Ожидание коллекции циферблатов с часов…';

  @override
  String get requestNotSentCheckTheConnection =>
      'Запрос не отправлен. Проверьте подключение.';

  @override
  String get newCollectionNotReceivedYetRefreshAgain =>
      'Обновлённая коллекция ещё не получена. Обновите ещё раз.';

  @override
  String get myFaces => 'Мои циферблаты';

  @override
  String myFacesCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Мои циферблаты · $count',
      many: 'Мои циферблаты · $count',
      few: 'Мои циферблаты · $count',
      one: 'Мои циферблаты · 1',
      zero: 'Мои циферблаты · 0',
    );
    return '$_temp0';
  }

  @override
  String get collectionNotReceivedYet => 'Коллекция ещё не получена';

  @override
  String get partialCollectionReceived => 'Получена неполная коллекция';

  @override
  String get collectionIsEmpty => 'Коллекция пуста';

  @override
  String face(String value0) {
    return 'Циферблат $value0';
  }

  @override
  String get selectedOnWatch => 'Активен на часах';

  @override
  String get createCopy => 'Создать копию';

  @override
  String get refreshFromWatch => 'Обновить с часов';

  @override
  String get enable => 'Включить';

  @override
  String get disable => 'Выключить';

  @override
  String get commandSentWaitingForTheWatchResult =>
      'Команда отправлена. Ожидание ответа от часов…';

  @override
  String get commandNotSentCheckTheConnection =>
      'Команда не отправлена. Проверьте подключение.';

  @override
  String get watchSettings => 'НАСТРОЙКИ ЧАСОВ';

  @override
  String get valuesComeFromWatchMessagesNotReceivedMeansThe =>
      'Значения поступают из сообщений часов. «Не получено» означает, что часы ещё не передали настройку.';

  @override
  String get connectYourWatchToChangeSettings =>
      'Подключите часы для изменения настроек.';

  @override
  String get stopNotConfirmed => 'Остановка не подтверждена';

  @override
  String get couldNotOpenFlashlightPermission =>
      'Не удалось открыть запрос разрешения на фонарик';

  @override
  String get phoneServiceUnavailable => 'Служба поиска телефона недоступна';

  @override
  String get alertStatusIsStale => 'Статус сигнала устарел';

  @override
  String get flashlightIsOn => 'Фонарик включён';

  @override
  String get soundAndFlashlightAreOn => 'Звук и фонарик включены';

  @override
  String get soundAlertStarted => 'Звуковой сигнал включён';

  @override
  String get lastAlertDidNotStart => 'Последний сигнал не запустился';

  @override
  String get requestHadNoSoundOrFlashlight => 'Запрос был без звука и фонарика';

  @override
  String get alertStopped => 'Сигнал остановлен';

  @override
  String get noAlertIsRunning => 'Активный сигнал отсутствует';

  @override
  String get findPhoneFromWatch => 'Поиск телефона с часов';

  @override
  String get localApkTest => 'Локальный тест APK';

  @override
  String get stopping => 'Остановка…';

  @override
  String get stopAlert => 'Остановить сигнал';

  @override
  String get cameraPermissionIsRequiredToFindYourPhoneWith =>
      'Для мигания фонариком при поиске телефона требуется разрешение на камеру.';

  @override
  String get allowFlashlight => 'Разрешить фонарик';

  @override
  String get incline42 => 'УКЛОН 42°';

  @override
  String get north124 => 'СЕВЕР 124°';

  @override
  String get message182M => '182 М';

  @override
  String get activity2 => 'АКТИВНОСТЬ';

  @override
  String get message620800Kcal => '620 / 800 ККАЛ';

  @override
  String get today => 'СЕГОДНЯ';

  @override
  String get message1930Meeting => '19:30 ВСТРЕЧА';

  @override
  String get watchSync => 'Watch Sync';

  @override
  String get message72Bpm5Minago => '72 УД/МИН • 5 мин назад';

  @override
  String get theWatchHasNotRepliedYetRefreshAgain =>
      'Часы ещё не ответили. Попробуйте обновить снова.';

  @override
  String get dataUpdated => 'Данные обновлены';

  @override
  String get couldNotSendRequest => 'Не удалось отправить запрос';

  @override
  String get noWatchDataYet => 'Данных с часов пока нет';

  @override
  String telemetryStatusTime(
    String value0,
    String value1,
    String value2,
    String value3,
  ) {
    return '$value0 · $value1:$value2:$value3';
  }

  @override
  String get chargingUnknown => 'статус зарядки неизвестен';

  @override
  String get charging => 'заряжаются';

  @override
  String get notCharging => 'не заряжаются';

  @override
  String get battery2 => 'Заряд: —';

  @override
  String batteryReading(String value0, String value1, String value2) {
    return '$value0: $value1% · $value2';
  }

  @override
  String get noConnectionToWatch => 'Нет соединения с часами';

  @override
  String get waitingForWatchReply => 'Ожидание ответа часов…';

  @override
  String get refreshData => 'Обновить данные';

  @override
  String get updated => 'Обновлено';

  @override
  String get dataIsStale => 'Данные устарели';

  @override
  String get battery3 => 'Заряд';

  @override
  String get lastBattery => 'Последний заряд';

  @override
  String get live => 'ОНЛАЙН';

  @override
  String get bluetooth => 'Bluetooth';

  @override
  String get wiFi => 'Wi-Fi';

  @override
  String get digitalCrown => 'Digital Crown';

  @override
  String get appleWatch => 'Apple Watch';

  @override
  String get watch => 'Watch';

  @override
  String get ultra => 'Ultra';

  @override
  String get wayfinder => 'Путеводитель';

  @override
  String get ultraModular => 'Модульный Ultra';

  @override
  String get ultraModularTrail => 'Модульный Ultra Trail';

  @override
  String get nikeBounceVolt => 'Nike Отскок Volt';

  @override
  String get appleMusic => 'Apple Music';

  @override
  String get appleAccount => 'Аккаунт Apple';

  @override
  String get watchConnection => 'Подключение часов';

  @override
  String get savedWatch => 'Сохранённые часы';

  @override
  String get noPairedWatch => 'Нет сопряжённых часов';

  @override
  String get noPairedWatchHint =>
      'Держите часы рядом и переведите их в режим сопряжения.';

  @override
  String get bridgeUnavailable => 'Bridge недоступен';

  @override
  String get bridgeUnavailableHint =>
      'Установите совместимый бэкенд Bridge для подключения часов.';

  @override
  String get identityLoading => 'Чтение сохранённой пары…';

  @override
  String get identityReadError =>
      'Сохранённая пара недоступна. Проверьте диагностику перед новым сопряжением.';

  @override
  String get connectWatch => 'Подключить';

  @override
  String get disconnectWatch => 'Отключить';

  @override
  String get connectionConnecting => 'Подключение…';

  @override
  String get connectionStopping => 'Отключение…';

  @override
  String get connectionPermissions => 'Разрешить доступ к Bluetooth';

  @override
  String get resumeWatchSetup => 'Продолжить настройку';

  @override
  String get finishWatchSetup => 'Завершить настройку';

  @override
  String get watchStillSettingUp => 'На часах всё ещё идёт настройка';

  @override
  String get resumeWatchSync => 'Возобновить синхронизацию';

  @override
  String get resumeWatchSyncHint =>
      'Используйте это только если часы ещё показывают экран настройки. Если виден циферблат — завершите настройку.';

  @override
  String get confirmWatchFace => 'Циферблат отображается';

  @override
  String get confirmWatchFaceHint =>
      'Подтвердите только после появления циферблата на часах. Текущая сессия настройки будет безопасно завершена.';

  @override
  String get confirmWatchFaceQuestion => 'На часах уже виден циферблат?';

  @override
  String get confirmWatchFaceDetail =>
      'Сохраняет обычный режим подключения для текущей активированной пары.';

  @override
  String get connectionRequestAccepted => 'Запрос принят. Ожидание часов…';

  @override
  String get connectionRequestRejected =>
      'Запрос отклонён. Проверьте подключение и диагностику.';

  @override
  String get connectionPermissionRequired =>
      'Предоставьте доступ к Bluetooth и повторите попытку.';

  @override
  String get connectionBusy =>
      'Операция подключения или настройки уже выполняется.';

  @override
  String get syncCurrentWifi => 'Передать текущую сеть Wi-Fi';

  @override
  String get wifiReceiptHint =>
      'Сеть поставлена в очередь на передачу. Проверьте «Настройки → Wi-Fi» на часах.';

  @override
  String get watchDiagnostics => 'Диагностика подключения';

  @override
  String get bridgeJournal => 'Журнал Bridge';

  @override
  String get copyBridgeJournal => 'Копировать';

  @override
  String get journalCopied => 'Журнал скопирован';

  @override
  String get advancedConnectionTools => 'Расширенные инструменты';

  @override
  String get stockBondAudit => 'Аудит системной связки Bluetooth';

  @override
  String get stockBondImport => 'Импорт системной связки Bluetooth';

  @override
  String get stockIdentityAlignment => 'Выравнивание системного идентификатора';

  @override
  String get stockReconnectProbe => 'Проверка системного переподключения';

  @override
  String get advancedToolHint =>
      'Для этих инструментов подключение должно быть остановлено, а системный Bluetooth — выключен.';

  @override
  String get advancedToolQuestion => 'Запустить инструмент?';

  @override
  String get advancedToolDetail =>
      'Перед выполнением бэкенд проверяет сохранённую пару и состояние Bluetooth.';

  @override
  String get pairingDiscovering => 'Поиск часов…';

  @override
  String get pairingConnecting => 'Подключение к найденным часам…';

  @override
  String get pairingSecurity => 'Установка защищённой пары…';

  @override
  String get pairingActivation => 'Активация в Apple…';

  @override
  String get pairingIds => 'Подключение сервисов часов…';

  @override
  String get pairingRegistry => 'Чтение конфигурации часов…';

  @override
  String get pairingConfiguring => 'Применение настроек часов…';

  @override
  String get pairingActivated => 'Часы активированы';

  @override
  String get pairingCheckWatch =>
      'Начальная синхронизация отправлена. Проверьте часы.';

  @override
  String get pairingVerifyReconnect =>
      'Настройка часов обнаружена. Проверка подключения…';

  @override
  String get pairingPaused => 'Сессия настройки остановлена';

  @override
  String get watchActivatedHint =>
      'Активация завершена. Нажмите ниже, когда на часах появится циферблат.';

  @override
  String get confirmWatchFaceActiveDetail =>
      'Безопасно завершит сессию настройки и сохранит обычный режим подключения.';

  @override
  String get pairingSync => 'Синхронизация настройки часов…';

  @override
  String get pairingVerified => 'Настройка часов завершена';

  @override
  String get pairingFailed =>
      'Сессия настройки завершилась с ошибкой. Проверьте журнал.';

  @override
  String get pairingIdle => 'Активной сессии настройки нет.';

  @override
  String get pairingStarting => 'Запуск сессии с часами…';

  @override
  String get pairingPinHint => 'Введите 6-значный код с экрана часов.';

  @override
  String get pairingCheckingCode => 'Проверка кода часов…';

  @override
  String get pairingCodeVerifiedBondPending =>
      'Код проверен. Создание системной Bluetooth-связки пока недоступно в этой сборке.';

  @override
  String get macosPairingBootstrapRestricted =>
      'macOS ограничивает Bluetooth-канал, необходимый для сопряжения часов. Подробности в диагностике.';

  @override
  String get windowsPairingBootstrapRestricted =>
      'Поиск и подключение Bluetooth доступны, но Windows не предоставляет канал для первичного сопряжения в этой сборке.';

  @override
  String get windowsRawHciRequired =>
      'Для полного сопряжения нужен встроенный протокольный рантайм и подписанный UsbDk 1.0.21 (1.0.22 заблокирован из-за BSOD). Выберите USB-адаптер Bluetooth.';

  @override
  String get sendWatchPin => 'Отправить код';

  @override
  String get pairingCodeOnlyHint =>
      'На этом компьютере сопряжение выполняется по 6-значному коду на экране часов.';

  @override
  String get findNearbyWatch => 'Найти часы';

  @override
  String get bluetoothLinkConnected => 'Соединение Bluetooth установлено';

  @override
  String get watchPairingUnavailable =>
      'Сервисы сопряжения на этой платформе пока недоступны.';

  @override
  String get activationOwnerInput => 'Apple требует подтверждения владельца';

  @override
  String get activationCredentialsHint =>
      'Учётные данные отправляются напрямую в сервис активации Apple и не сохраняются приложением.';

  @override
  String get submitActivation => 'Продолжить активацию';

  @override
  String get retryActivation => 'Повторить активацию';

  @override
  String get stopSetup => 'Остановить настройку';

  @override
  String get pairedIdentity => 'ID пары';

  @override
  String get hardwareIdentity => 'Модель (идентификатор)';

  @override
  String get systemBuild => 'Сборка системы';

  @override
  String get pendingWatchSetup =>
      'Для этой сохранённой пары настройка не завершена.';

  @override
  String get oneWatchBackend =>
      'Сейчас бэкенд хранит одну пару. Сохранённая пара остаётся при отключении.';

  @override
  String get chooseDiscoveredWatch =>
      'Выберите обнаруженные рядом часы. Показываются только поддерживаемые объявления.';

  @override
  String get desktopCoreUnavailable =>
      'Не удалось запустить службу подключения. Подробности в диагностике.';

  @override
  String get manageWatchFaces => 'Управление циферблатами';

  @override
  String get nativeFaceChangesConfirmed => 'Изменения подтверждены часами.';

  @override
  String get nativeFaceWaiting => 'Ожидание подтверждения изменений от часов…';

  @override
  String get nativeFaceSaving => 'Сохранение изменений…';

  @override
  String get nativeFaceRetry => 'Повторить';

  @override
  String get nativeFaceEditPending =>
      'Изменения сохранены на устройстве. Ожидание часов…';

  @override
  String get nativeFaceSaveFailed =>
      'Не удалось сохранить изменения на устройстве. Не закрывайте редактор и повторите попытку.';

  @override
  String get nativeFaceEditUncertain =>
      'Изменения сохранены, но часы не подтвердили получение. Отправьте снова.';

  @override
  String get nativeFaceRejected =>
      'Изменения не отправлены. Обновите коллекцию и попробуйте снова.';

  @override
  String get nativeFaceUnknown =>
      'Результат не подтверждён. Обновите перед следующим изменением.';

  @override
  String get nativeFaceCustomization => 'Настройка';

  @override
  String get nativeFaceComplications => 'Расширения';

  @override
  String get nativeFaceSetActive => 'Сделать текущим циферблатом';

  @override
  String get nativeFaceDelete => 'Удалить циферблат';

  @override
  String get nativeFaceDeleteQuestion => 'Удалить этот циферблат?';

  @override
  String get nativeFaceDeleteDetail =>
      'Остальные циферблаты останутся на часах. Последний циферблат удалить нельзя.';

  @override
  String get nativeFaceMoveEarlier => 'Сдвинуть раньше';

  @override
  String get nativeFaceMoveLater => 'Сдвинуть позже';

  @override
  String get nativeFaceConfiguration => 'Конфигурация часов';

  @override
  String get nativeFaceApply => 'Применить к часам';

  @override
  String get nativeFaceValueHint =>
      'Введите значение JSON. Строки в кавычках; числа и boolean в формате JSON.';

  @override
  String get nativeFaceInvalidValue => 'Некорректное значение конфигурации';

  @override
  String get nativeFaceConfigurationUnavailable =>
      'Обновите Bridge и коллекцию, чтобы загрузить параметры этого циферблата.';

  @override
  String get nativeFaceFileSaved => 'Файл циферблата сохранён';

  @override
  String get nativeFaceFileError =>
      'Не удалось открыть или сохранить файл циферблата';

  @override
  String get nativeFaceResourceExportUnavailable =>
      'Циферблат может требовать фото или ресурсы. Экспорт недоступен, пока не считан полный пакет.';

  @override
  String get nativeFaceComplicationHint =>
      'Выберите совместимое расширение из каталога часов или готового циферблата. Настройки и интенты сохраняются.';

  @override
  String get nativeFaceComplicationCatalogPartial =>
      'Часть дескрипторов недоступна. Обновите коллекцию для получения вариантов.';

  @override
  String get nativeFaceComplicationVariantsMissing =>
      'Некоторые расширения не передали список вариантов. Текущие настройки можно продолжать использовать.';

  @override
  String get nativeFaceNoComplication => 'Нет';

  @override
  String get nativeFaceUnknownComplication => 'Неизвестное расширение';

  @override
  String get nativeFaceConflict =>
      'Циферблат изменился или часы переподключились. Откройте его заново перед правками.';

  @override
  String get nativeFaceTimeStyle => 'Стиль времени';

  @override
  String get nativeFaceDialStyle => 'Стиль циферблата';

  @override
  String get nativeFaceWaypoint => 'Путевые точки';

  @override
  String get nativeFacePink => 'Розовый';

  @override
  String get nativeFaceNeonGreen => 'Неоново-зелёный';

  @override
  String get nativeFaceOrange => 'Оранжевый';

  @override
  String get nativeFacePinkSand => 'Розовый / Песок';

  @override
  String get nativeFaceNeonGreenCloud => 'Неоново-зелёный / Облако';

  @override
  String get nativeFaceOrangeLightSage => 'Оранжевый / Светлый шалфей';

  @override
  String get nativeFaceLightBlue => 'Голубой';

  @override
  String get nativeFaceBrightBlue => 'Ярко-синий';

  @override
  String get nativeFaceTerraCotta => 'Терракотовый';

  @override
  String get nativeFaceTopLeft => 'Сверху слева';

  @override
  String get nativeFaceTop => 'Сверху';

  @override
  String get nativeFaceBottom => 'Снизу';

  @override
  String get nativeFaceTopRight => 'Сверху справа';

  @override
  String get nativeFaceBottomLeft => 'Снизу слева';

  @override
  String get nativeFaceBottomRight => 'Снизу справа';

  @override
  String get nativeFaceCenter => 'По центру';

  @override
  String get nativeFaceNightMode => 'Ночной режим';

  @override
  String get nativeFaceColor => 'Цвет';

  @override
  String get nativeFaceAutomatic => 'Автоматически';

  @override
  String get nativeFaceLeft => 'Слева';

  @override
  String get nativeFaceRight => 'Справа';

  @override
  String nativeFacePalette(String number) {
    return 'Палитра $number';
  }

  @override
  String nativeFaceSeasonalPalette(String number) {
    return 'Сезонная $number';
  }

  @override
  String get nativeFaceAdvanced => 'Дополнительно';

  @override
  String get nativeFaceEditHint => 'Выберите параметры и примените их к часам.';

  @override
  String get nativeFaceCopyHint =>
      'Создайте новый циферблат на основе имеющегося или импортируйте файл .watchface.';

  @override
  String get nativeFaceLocalLibrary => 'Локальная библиотека';

  @override
  String get nativeFaceUnsaved => 'Отменить несохранённые изменения?';

  @override
  String get nativeFaceDiscard => 'Отменить';

  @override
  String get nativeFaceImport => 'Импорт циферблата';

  @override
  String get nativeFaceSharedPreview => 'Встроенное превью';

  @override
  String get nativeFaceImportDone => 'Готово';

  @override
  String get nativeFaceSharedPreviewUnavailable =>
      'В этом файле нет подходящего превью.';

  @override
  String get nativeFaceSharedPreviewHint =>
      'Превью из файла. Совместимость и установку подтверждают сами часы.';

  @override
  String get nativeFaceDraft => 'Несохранённые изменения';

  @override
  String get nativeFaceDigital => 'Цифровой';

  @override
  String get nativeFaceAnalog => 'Аналоговый';

  @override
  String get nativeFaceAdd => 'Добавить циферблат';

  @override
  String get nativeFaceAvailable => 'Доступные циферблаты';

  @override
  String get nativeFaceAddToWatch => 'Добавить на часы';

  @override
  String get nativeFaceDefaultPreview => 'Превью по умолчанию';

  @override
  String get nativeFacePreviewUnavailable =>
      'Превью для этих настроек недоступно';

  @override
  String get nativeFaceStylePreview => 'Предпросмотр стиля';

  @override
  String get nativeFaceStylePreviewHint =>
      'Примерное время. Живые данные и расширения отображаются на часах.';

  @override
  String get nativeFaceSearch => 'Поиск';

  @override
  String get nativePhotoChoose => 'Выбрать фото';

  @override
  String get nativePhotoCropHint =>
      'Перетащите для кадрирования. Масштабируйте жестом или ползунком.';

  @override
  String nativeIntentSettings(String slot) {
    return 'Параметры для $slot';
  }

  @override
  String nativeIntentParameterSeconds(String name) {
    return '$name (секунды)';
  }

  @override
  String nativeIntentDurationValue(String name, String seconds) {
    return '$name: $seconds с';
  }

  @override
  String get nativeIntentParameterInvalid =>
      'Введите корректную длительность в допустимых пределах.';

  @override
  String get nativeIntentLocalDraft =>
      'Параметры сохранены в черновике. Примените циферблат для отправки на часы.';

  @override
  String get nativePhotoZoom => 'Масштаб';

  @override
  String get nativePhotoAspect => 'Форма рамки';

  @override
  String get nativePhotoKeepCrop => 'Сохранять кадр';

  @override
  String nativePhotoSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Выбрано $count фото',
      many: 'Выбрано $count фото',
      few: 'Выбрано $count фото',
      one: 'Выбрано 1 фото',
    );
    return '$_temp0';
  }

  @override
  String get nativePhotoTimeAlignment => 'Расположение времени';

  @override
  String get nativePhotoLeading => 'Слева';

  @override
  String get nativePhotoTrailing => 'Справа';

  @override
  String get nativePhotoTimeSize => 'Размер цифр';

  @override
  String get nativePhotoSmall => 'Мелкий';

  @override
  String get nativePhotoMedium => 'Средний';

  @override
  String get nativePhotoLarge => 'Крупный';

  @override
  String get nativePhotoXLarge => 'Очень крупный';

  @override
  String get nativePhotoRemove => 'Удалить фото';

  @override
  String get nativeFaceShade => 'Оттенок';

  @override
  String get nativeFaceAddColors => 'Добавить цвета';

  @override
  String get nativeFaceColorsDone => 'Готово';

  @override
  String get nativeFaceColorsSaveError =>
      'Не удалось сохранить цвета. Попробуйте снова.';

  @override
  String get nativeFaceColorsPending =>
      'Ожидание настроек цветов с Apple Watch.';

  @override
  String get nativeFaceColorsAwaiting => 'Отправка цветов на Apple Watch.';

  @override
  String get nativeFaceColorsUncertain =>
      'Apple Watch сообщили другой набор цветов.';

  @override
  String get nativeFaceColorsSent => 'Цвета отправлены на Apple Watch.';

  @override
  String get nativeFaceColorsDeliveryUncertain =>
      'Не удалось подтвердить доставку цветов.';

  @override
  String get nativeFaceColorsRetry => 'Повторить отправку цветов';

  @override
  String get nativeFaceMonogram => 'Монограмма';

  @override
  String get nativeMonogramDone => 'Готово';

  @override
  String get nativeMonogramInvalid =>
      'Этот текст нельзя использовать как монограмму.';

  @override
  String get nativeMonogramUnavailable =>
      'Подключите Apple Watch для изменения монограммы.';

  @override
  String get nativeMonogramSaveError =>
      'Не удалось сохранить монограмму. Попробуйте снова.';

  @override
  String get nativeMonogramAwaiting => 'Отправка монограммы на Apple Watch.';

  @override
  String get nativeMonogramDelivered =>
      'Монограмма доставлена. Ожидание подтверждения от часов.';

  @override
  String get nativeMonogramUncertain =>
      'Синхронизация монограммы не подтверждена.';

  @override
  String get nativeMonogramConfirmed => 'Монограмма подтверждена Apple Watch.';

  @override
  String get nativeFaceSeeAllWatchFaces => 'Все циферблаты';

  @override
  String get nativeFaceAllWatchFaces => 'Все циферблаты';

  @override
  String get nativeFaceLibraryEdit => 'Изменить';

  @override
  String get nativeFaceLibraryDone => 'Готово';

  @override
  String nativeFaceLibraryReorder(String face) {
    return 'Переместить $face';
  }

  @override
  String get notificationForwarding => 'Пересылать уведомления на часы';

  @override
  String get notificationForwardingDescription =>
      'Пересылка уведомлений приложений Android на Apple Watch. Актуальные уведомления тихо восстанавливаются при переподключении.';

  @override
  String get notificationBridgeUnavailable =>
      'Подключите обновлённый Android Bridge для настройки пересылки.';

  @override
  String get notificationAccessRequired =>
      'Разрешите доступ к уведомлениям на телефоне.';

  @override
  String get notificationForwardingPaused =>
      'Пересылка уведомлений приостановлена.';

  @override
  String get notificationListenerWaiting =>
      'Ожидание подключения системного сервиса уведомлений Android.';

  @override
  String get notificationWatchWaiting => 'Ожидание подключения Apple Watch.';

  @override
  String get notificationForwardingReady =>
      'Готово к пересылке уведомлений на часы.';

  @override
  String get notificationAllowAccess => 'Разрешить доступ к уведомлениям';

  @override
  String get notificationManageAccess => 'Настроить доступ к уведомлениям';

  @override
  String get notificationAccessDescription =>
      'Включите Apple Watch Notification Mirror в настройках Android.';

  @override
  String get notificationAccessFailed =>
      'Не удалось открыть настройки доступа к уведомлениям.';

  @override
  String get notificationSettingsFailed =>
      'Не удалось сохранить настройки уведомлений. Попробуйте снова.';

  @override
  String get notificationAppDiscovery =>
      'Приложения появляются здесь после первого уведомления. Пересылка включена по умолчанию; отключите лишние приложения.';

  @override
  String get notificationNoApps => 'Приложения ещё не зафиксированы';

  @override
  String notificationPending(int count) {
    return 'В очереди на доставку: $count';
  }

  @override
  String get notificationTestDescription =>
      'Отправить тестовое уведомление, когда пересылка готова.';

  @override
  String get notificationTestBody =>
      'Это тестовое уведомление из Watch Companion.';

  @override
  String get notificationQueued => 'Уведомление поставлено в очередь';

  @override
  String get notificationQueuedDetails =>
      'Тестовое уведомление поставлено в очередь на Apple Watch. Проверьте экран часов.';

  @override
  String get notificationTestFailed => 'Не удалось поставить в очередь';

  @override
  String get notificationTestFailedDetails =>
      'Не удалось отправить тестовое уведомление. Проверьте подключение часов.';

  @override
  String get notificationOnlyWhenLocked =>
      'Только при заблокированном телефоне';

  @override
  String get notificationOnlyWhenLockedDescription =>
      'Не пересылать уведомления на часы, пока экран телефона разблокирован.';

  @override
  String get notificationSearchApps => 'Поиск приложений';

  @override
  String get notificationFilterAll => 'Все';

  @override
  String get notificationFilterActive => 'Активные';

  @override
  String get notificationRetryPending => 'Повторить отправку очереди';

  @override
  String get notificationTestAcknowledged =>
      'Apple Watch подтвердили получение по IDS. Проверьте экран часов.';

  @override
  String get notificationTestDeliveryFailed =>
      'Тестовое уведомление не дошло до Apple Watch вовремя. Проверьте соединение.';

  @override
  String get updateUnavailable =>
      'Обновления ПО недоступны при текущем подключении.';

  @override
  String get updateConnect => 'Подключите Apple Watch для проверки обновлений.';

  @override
  String get updateUnknown => 'Ожидание статуса обновления от часов.';

  @override
  String get updateIdle =>
      'Обновление не выбрано. Нажмите проверку для запроса каталога Apple.';

  @override
  String get updateAvailable => 'Доступно обновление';

  @override
  String get updatePreparing => 'Загрузка и подготовка на Apple Watch';

  @override
  String get updateWaitingPreparation => 'Ожидание условий для подготовки';

  @override
  String get updatePrepared => 'Готово к установке';

  @override
  String get updateWaitingInstallation => 'Ожидание условий для установки';

  @override
  String get updateInstalling => 'Установка на Apple Watch';

  @override
  String get updateRestart =>
      'Ожидание перезагрузки часов и подтверждения новой сборки';

  @override
  String get updateComplete => 'Часы подтвердили установленную сборку.';

  @override
  String get updateFailed =>
      'Часы сообщили об ошибке обновления. Обновите статус перед продолжением.';

  @override
  String get updateStalled => 'Часы сообщают, что прогресс приостановлен.';

  @override
  String updateStageProgress(String percent) {
    return '$percent% текущего этапа';
  }

  @override
  String get updateRequirements =>
      'Часы должны стоять на зарядке (минимум 50% заряда) и быть подключены к Wi-Fi. Держите телефон рядом.';

  @override
  String get updateTerms =>
      'Примите условия обновления на часах: «Настройки → Основные → Обновление ПО», затем обновите статус здесь.';

  @override
  String get updateDownload => 'Загрузить и подготовить';

  @override
  String get updateInstall => 'Установить обновление';

  @override
  String get updateRefresh => 'Обновить статус';

  @override
  String updateConfirm(String version, String build) {
    return 'Установить watchOS $version ($build)? Часы перезагрузятся. Не снимайте их с зарядки до завершения.';
  }

  @override
  String get updateQueued =>
      'Запрос в очереди. Ожидание подтверждения от часов.';

  @override
  String get updateNotQueued =>
      'Не удалось поставить запрос в очередь. Обновите статус и повторите.';

  @override
  String get updateUnconfirmed =>
      'Доставка не подтверждена. Обновите статус перед следующей командой.';

  @override
  String get updateInstallUnconfirmed =>
      'Запрос на установку уже отправлен. Обновите статус или завершите установку на часах.';

  @override
  String updateDownloadSize(String size) {
    return 'Размер загрузки: $size';
  }

  @override
  String updateRequiredSpace(String size) {
    return 'Требуется свободно: $size';
  }

  @override
  String get opticalCameraPermissionDenied =>
      'Для сканирования Apple Watch требуется разрешение на использование камеры.';

  @override
  String get opticalNoCamera => 'На этом устройстве не найдена камера.';

  @override
  String get filterJournal => 'Фильтр журнала';

  @override
  String get jumpToBottom => 'Вниз';

  @override
  String get copiedToClipboard => 'Скопировано';

  @override
  String get noMatchingJournalLines => 'Совпадающих строк не найдено';

  @override
  String get watchConnectionSubtitle =>
      'Сопряжение, переподключение и статус Bridge';
}
