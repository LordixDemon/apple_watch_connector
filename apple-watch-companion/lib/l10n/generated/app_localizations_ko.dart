// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Korean (`ko`).
class AppLocalizationsKo extends AppLocalizations {
  AppLocalizationsKo([String locale = 'ko']) : super(locale);

  @override
  String get bluetoothAdapter => 'Bluetooth 어댑터';

  @override
  String get bluetoothController => 'Bluetooth 컨트롤러';

  @override
  String get refreshBluetoothAdapters => '어댑터 새로 고침';

  @override
  String get bluetoothAdaptersLoading => 'Bluetooth 어댑터를 찾는 중…';

  @override
  String get bluetoothAdaptersEmpty =>
      'Bluetooth 어댑터를 찾을 수 없습니다. 어댑터를 연결하고 목록을 새로 고치세요.';

  @override
  String get bluetoothAdapterChoiceHint =>
      '워치에 사용할 어댑터를 선택하세요. 선택한 어댑터는 다음 연결에도 사용됩니다.';

  @override
  String get bluetoothAdapterDisconnectHint =>
      'Bluetooth 어댑터를 변경하기 전에 워치 연결을 해제하세요.';

  @override
  String get bluetoothAdapterMissing => '저장된 어댑터를 사용할 수 없음';

  @override
  String get bluetoothAdapterMissingHint =>
      '저장된 어댑터를 다시 연결하거나 다른 어댑터를 선택하세요. 워치 페어링은 유지됩니다.';

  @override
  String get bluetoothAdapterDiscoveryFailed =>
      'Bluetooth 어댑터 정보를 읽을 수 없습니다. BlueZ가 실행 중인지 확인한 뒤 목록을 새로 고치세요.';

  @override
  String get bluetoothAdapterSaveFailed =>
      '어댑터 설정을 읽거나 저장할 수 없습니다. 앱 상태 폴더의 접근 권한을 확인한 뒤 어댑터를 다시 선택하세요.';

  @override
  String get bluetoothAdapterSaved => 'Bluetooth 어댑터를 저장했습니다.';

  @override
  String get opticalScanTitle => 'Apple Watch 페어링';

  @override
  String get opticalPairWithCamera => '카메라로 페어링';

  @override
  String get opticalScanHint =>
      '페어링 애니메이션을 프레임 안에 맞추고 스캔을 탭하세요. 코드를 읽는 동안 휴대폰을 움직이지 마세요.';

  @override
  String get opticalScan => '스캔';

  @override
  String get opticalNoMatch => '페어링 애니메이션을 찾을 수 없습니다. 카메라를 워치에 맞추고 다시 시도하세요.';

  @override
  String get opticalRecognizedTitle => 'Apple Watch 발견';

  @override
  String opticalRecognizedDetail(String name) {
    return '$name의 페어링 코드를 인식했습니다. 다음으로 Bluetooth 인증을 진행합니다.';
  }

  @override
  String opticalReplaceDetail(String name) {
    return '$name의 코드를 인식했습니다. 워치가 새 페어링을 기다리는 상태여야 합니다. 기존 암호화 페어링은 교체 전에 백업됩니다.';
  }

  @override
  String get opticalReplacePair => '저장된 페어링 교체';

  @override
  String get opticalStartPairing => '워치 페어링';

  @override
  String opticalAnalyzedFrames(int count) {
    return '프레임 $count개 분석됨';
  }

  @override
  String get opticalPairingLab => '광학 페어링 실험실';

  @override
  String get opticalCaptureHint =>
      '페어링 애니메이션을 프레임 안에 맞추세요. 짧은 샘플을 촬영해 광학 디코더를 테스트합니다. 인식 기능은 아직 활성화되지 않았습니다. 이 화면을 나가면 샘플이 삭제됩니다.';

  @override
  String get opticalCameraError => '카메라 촬영에 실패했습니다. 카메라 권한을 확인하고 다시 시도하세요.';

  @override
  String opticalFrames(int count) {
    return '프레임 $count개 촬영됨';
  }

  @override
  String get opticalCapture => '샘플 촬영';

  @override
  String get opticalRetry => '다시 시도';

  @override
  String get opticalStopCapture => '촬영 중지';

  @override
  String get backendFeatures => '사용 가능한 기능';

  @override
  String get backendFeaturesHint =>
      '이 목록은 현재 연결 백엔드에 구현된 서비스를 나타냅니다. 연결되었다고 해서 모든 watchOS 기능, 기기 또는 펌웨어 버전을 지원하는 것은 아닙니다.';

  @override
  String get backendFeaturesUnknown => '연결 서비스에서 지원 기능 정보를 아직 받지 못했습니다.';

  @override
  String get featureNotSupported => '아직 지원하지 않음';

  @override
  String get phoneFindFeature => '휴대폰 찾기';

  @override
  String get ok => '확인';

  @override
  String get rightWrist => '오른쪽 손목';

  @override
  String get rotateScreen180 => '화면 180° 회전';

  @override
  String get message24Hourtime => '24시간제';

  @override
  String get readyToConnect => '연결 준비됨';

  @override
  String get workout => '운동';

  @override
  String get activity => '활동';

  @override
  String get heartRate => '심박수';

  @override
  String get music => '음악';

  @override
  String get messages => '메시지';

  @override
  String get phone => '전화';

  @override
  String get weather => '날씨';

  @override
  String get maps => '지도';

  @override
  String get battery => '배터리';

  @override
  String get message72Bpm => '72 bpm';

  @override
  String get message21Sunny => '+21° 맑음';

  @override
  String get compass => '나침반';

  @override
  String get message124Se182M => '124° 남동 • 182 m';

  @override
  String get outdoorRun => '실외 달리기';

  @override
  String get nowPlaying => '지금 재생 중';

  @override
  String get calendar => '캘린더';

  @override
  String get meetingAt1930 => '19:30 회의';

  @override
  String get custom => '사용자 설정';

  @override
  String get imported => '가져온 항목';

  @override
  String gb(String value0) {
    return '$value0 GB';
  }

  @override
  String mb(String value0) {
    return '$value0 MB';
  }

  @override
  String kb(String value0) {
    return '$value0 KB';
  }

  @override
  String b(String value0) {
    return '$value0 B';
  }

  @override
  String get discover => '둘러보기';

  @override
  String get newInWatchos11 => 'WATCH COMPANION';

  @override
  String get appleWatchUltra2OnAndroid => 'Apple Watch Companion';

  @override
  String get syncNotificationsCallsWorkoutsAndWatchFacesWithYour =>
      '휴대폰이나 데스크톱으로 Apple Watch를 연결하세요. 나의 시계의 사용 가능한 기능에서 연결 백엔드에 구현된 서비스를 확인할 수 있습니다.';

  @override
  String get guidesTips => '사용 설명 및 팁';

  @override
  String get gesturesControls => '제스처 및 조작';

  @override
  String get doubleTapDigitalCrownAndActionButton =>
      '더블 탭, Digital Crown 및 동작 버튼';

  @override
  String get notificationMirroring => '알림 미러링';

  @override
  String get setUpSmsCallsAndMessagingApps => 'SMS, 전화 및 메시지 앱 설정';

  @override
  String get resources => '참고 자료';

  @override
  String get halSupportDiagnostics => 'HAL 지원 및 진단';

  @override
  String get appleWatchUltra2Controls => 'Apple Watch Ultra 2 조작법';

  @override
  String get useDoubleTapTheDigitalCrownAndTheOrange =>
      '더블 탭, Digital Crown 및 오렌지색 동작 버튼을 사용하세요.';

  @override
  String get essentialGestures => '기본 제스처';

  @override
  String get doubleTap => '더블 탭';

  @override
  String get tapYourIndexFingerAndThumbTogetherTwiceTo =>
      '검지와 엄지를 두 번 맞대어 전화를 받거나 타이머를 중지하거나 스마트 스택을 스크롤할 수 있습니다.';

  @override
  String get turnToScrollThroughListsAndZoomOrPress =>
      '돌려서 목록을 스크롤하거나 확대하고, 눌러서 시계 페이스로 돌아갑니다.';

  @override
  String get orangeActionButton => '오렌지색 동작 버튼';

  @override
  String get startAWorkoutMarkAWaypointOrHoldTo =>
      '운동을 시작하거나 경유지를 표시하거나 길게 눌러 86 dB 사이렌을 작동할 수 있습니다.';

  @override
  String get waterLock => '수중 잠금';

  @override
  String get preventsAccidentalTouchesInWaterUseTheDigitalCrown =>
      '물속에서 실수로 화면이 터치되는 것을 방지합니다. Digital Crown으로 화면 잠금을 해제하고 물을 배출하세요.';

  @override
  String get faceGallery => '페이스 갤러리';

  @override
  String get save => '저장';

  @override
  String get reset => '재설정';

  @override
  String get nightMode => '야간 모드';

  @override
  String get bezelStyle => '베젤 스타일';

  @override
  String get seconds => '초';

  @override
  String get exportWatchfacePackage => '.watchface 패키지 내보내기';

  @override
  String get cancel => '취소';

  @override
  String get topSubdial => '상단 서브 다이얼';

  @override
  String get topLeft => '왼쪽 상단';

  @override
  String get topRight => '오른쪽 상단';

  @override
  String get center => '중앙';

  @override
  String get bottomLeft => '왼쪽 하단';

  @override
  String get bottomRight => '오른쪽 하단';

  @override
  String get bottomSubdial => '하단 서브 다이얼';

  @override
  String get bezel => '베젤';

  @override
  String get import => '가져오기';

  @override
  String get myWatch => '나의 시계';

  @override
  String get about => '정보';

  @override
  String get general => '일반';

  @override
  String get name => '이름';

  @override
  String get model => '모델';

  @override
  String get modelNumber => '모델 번호';

  @override
  String get watchCase => '케이스';

  @override
  String get watchosVersion => 'watchOS 버전';

  @override
  String get serialNumber => '일련 번호';

  @override
  String get networkAddresses => '네트워크 주소';

  @override
  String get wiFiAddress => 'Wi-Fi 주소';

  @override
  String get storage => '저장 공간';

  @override
  String get available => '사용 가능';

  @override
  String get songs => '노래';

  @override
  String get photos => '사진';

  @override
  String get userApps => '사용자 앱';

  @override
  String get action => '동작';

  @override
  String get stopwatch => '스톱워치';

  @override
  String get backtrack => '경로 되짚기';

  @override
  String get flashlight => '손전등';

  @override
  String get holdForSiren => '길게 눌러 사이렌 실행';

  @override
  String version(String value0, String value1) {
    return '$value0 • 버전 $value1';
  }

  @override
  String get brightness => '밝기';

  @override
  String get textSize => '텍스트 크기';

  @override
  String get boldText => '볼드체 텍스트';

  @override
  String get wakeOnWristRaise => '손목을 올려 깨우기';

  @override
  String get wakeDuration => '깨우기 시간';

  @override
  String get fallDetection => '넘어짐 감지';

  @override
  String get crashDetection => '충돌 감지';

  @override
  String get softwareUpdate => '소프트웨어 업데이트';

  @override
  String get orientationTime => '방향 및 시간';

  @override
  String get leftWrist => '왼쪽 손목';

  @override
  String get notReceived => '수신되지 않음';

  @override
  String get backgroundAppRefresh => '백그라운드 앱 새로 고침';

  @override
  String get on => '켬';

  @override
  String get off => '끔';

  @override
  String get airplaneMode => '에어플레인 모드';

  @override
  String get activityHealth => '활동 및 건강';

  @override
  String get noHealthDataReceivedFromTheWatchYet => '워치에서 받은 건강 데이터가 아직 없습니다.';

  @override
  String get watchMeasurements => '워치 측정값';

  @override
  String get bpm => 'bpm';

  @override
  String get steps => '걸음 수';

  @override
  String km(String value0) {
    return '$value0 km';
  }

  @override
  String get move => '움직이기';

  @override
  String kcal(String value0, String value1) {
    return '$value0 / $value1 kcal';
  }

  @override
  String get exercise => '운동하기';

  @override
  String min(String value0, String value1) {
    return '$value0 / $value1분';
  }

  @override
  String get stand => '일어서기';

  @override
  String hr(String value0, String value1) {
    return '$value0 / $value1시간';
  }

  @override
  String get activityRings => '활동 링';

  @override
  String get standReminders => '일어서기 알림';

  @override
  String get remindsYouToStandAndMove10MinutesBefore =>
      '매시간이 끝나기 10분 전에 일어나 움직이도록 알려줍니다.';

  @override
  String get dailyCoaching => '일일 코칭';

  @override
  String get goalCompletions => '목표 달성';

  @override
  String get heartBreathing => '심장 및 호흡';

  @override
  String get highHeartRateNotifications => '고심박수 알림';

  @override
  String bpm2(String value0) {
    return '> $value0 bpm';
  }

  @override
  String get lowHeartRateNotifications => '저심박수 알림';

  @override
  String bpm3(String value0) {
    return '< $value0 bpm';
  }

  @override
  String get bloodOxygen => '혈중 산소';

  @override
  String get sleepTracking => '수면 추적';

  @override
  String get allWatches => '모든 시계';

  @override
  String get connected => '연결됨';

  @override
  String get disconnected => '연결 해제됨';

  @override
  String get notifications => '알림';

  @override
  String get installedOnAppleWatch => 'APPLE WATCH에 설치됨';

  @override
  String get installed => '설치됨';

  @override
  String get hidden => '숨겨짐';

  @override
  String get notificationIndicator => '알림 표시';

  @override
  String get notificationPrivacy => '알림 개인 정보 보호';

  @override
  String get mirrorPhoneAlerts => '휴대폰 알림 미러링';

  @override
  String get mail => '메일';

  @override
  String get sendTestNotification => '테스트 알림 보내기';

  @override
  String get testMessage => '테스트 메시지';

  @override
  String get unlockWithPhone => '휴대폰으로 잠금 해제';

  @override
  String get wristDetection => '손목 감지';

  @override
  String get checkingForUpdates => '업데이트 확인 중…';

  @override
  String get yourAppleWatchSoftwareIsUpToDate => 'Apple Watch 소프트웨어가 최신 버전입니다.';

  @override
  String get checkAgain => '다시 확인';

  @override
  String get alertVolume => '알림 음량';

  @override
  String get silentMode => '무음 모드';

  @override
  String get hapticAlerts => '햅틱 알림';

  @override
  String get prominent => '뚜렷하게';

  @override
  String get crownHaptics => '크라운 햅틱';

  @override
  String get systemHaptics => '시스템 햅틱';

  @override
  String get coverToMute => '가려서 소리 끄기';

  @override
  String get watchStorage => '워치 저장 공간';

  @override
  String get reclaimable => '확보 가능';

  @override
  String get purgeableData => '정리 가능한 데이터';

  @override
  String get watchContent => '워치 콘텐츠';

  @override
  String get pairAppleWatch => 'Apple Watch 페어링';

  @override
  String get alwaysOnDisplay => '상시표시형 디스플레이';

  @override
  String get exclusiveAppleWatchUltraFacesWithACustomizableBezel =>
      '맞춤형 베젤, 야간 모드 및 컴플리케이션 8개를 제공하는 Apple Watch Ultra 전용 페이스입니다.';

  @override
  String get modular => '모듈';

  @override
  String get largeInformationBlocksAndGraphsForDataAtA =>
      '큰 정보 블록과 그래프로 데이터를 한눈에 확인합니다.';

  @override
  String get classic => '클래식';

  @override
  String get traditionalAnalogFacesWithRomanAndArabicNumerals =>
      '로마 숫자와 아라비아 숫자를 사용하는 전통적인 아날로그 페이스입니다.';

  @override
  String get infographAstronomy => '인포그래프 및 천체';

  @override
  String get detailedAstronomicalAndWeatherDisplays => '천체 및 날씨 정보를 자세히 표시합니다.';

  @override
  String get activityNike => '활동 및 Nike';

  @override
  String get activityRingsWorkoutsAndSportsMetrics => '활동 링, 운동 및 스포츠 측정값입니다.';

  @override
  String get wayfinderAlpine => '길잡이 알파인';

  @override
  String get wayfinderNightMode => '길잡이 야간 모드';

  @override
  String get modularDuo => '모듈 듀오';

  @override
  String get modularMulticolor => '모듈 멀티컬러';

  @override
  String get california => '캘리포니아';

  @override
  String get californiaOcean => '캘리포니아 오션';

  @override
  String get chronographPro => '크로노그래프 프로';

  @override
  String get infograph => '인포그래프';

  @override
  String get solarDial => '태양 다이얼';

  @override
  String get activityAnalog => '활동 아날로그';

  @override
  String get modularUltra => '모듈 울트라';

  @override
  String get activityDigital => '활동 디지털';

  @override
  String get collectionReceivedFromTheWatch => '워치에서 페이스 모음을 받았습니다.';

  @override
  String get connectionChangedRefreshAgain => '연결이 변경되었습니다. 다시 새로 고치세요.';

  @override
  String get waitingForTheWatchFaceCollection => '워치의 페이스 모음을 기다리는 중…';

  @override
  String get requestNotSentCheckTheConnection => '요청을 보내지 못했습니다. 연결을 확인하세요.';

  @override
  String get newCollectionNotReceivedYetRefreshAgain =>
      '새 페이스 모음을 아직 받지 못했습니다. 다시 새로 고치세요.';

  @override
  String get myFaces => '나의 페이스';

  @override
  String myFacesCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '나의 페이스 · $count',
      one: '나의 페이스 · 1',
      zero: '나의 페이스 · 0',
    );
    return '$_temp0';
  }

  @override
  String get collectionNotReceivedYet => '페이스 모음을 아직 받지 못함';

  @override
  String get partialCollectionReceived => '페이스 모음의 일부만 받음';

  @override
  String get collectionIsEmpty => '페이스 모음이 비어 있음';

  @override
  String face(String value0) {
    return '페이스 $value0';
  }

  @override
  String get selectedOnWatch => '워치에서 선택됨';

  @override
  String get createCopy => '복사본 생성';

  @override
  String get refreshFromWatch => '워치에서 새로 고침';

  @override
  String get enable => '활성화';

  @override
  String get disable => '비활성화';

  @override
  String get commandSentWaitingForTheWatchResult =>
      '명령을 보냈습니다. 워치의 결과를 기다리는 중입니다.';

  @override
  String get commandNotSentCheckTheConnection => '명령을 보내지 못했습니다. 연결을 확인하세요.';

  @override
  String get watchSettings => '워치 설정';

  @override
  String get valuesComeFromWatchMessagesNotReceivedMeansThe =>
      '워치에서 보낸 값을 표시합니다. “수신되지 않음”은 워치가 아직 해당 설정을 보고하지 않았다는 뜻입니다.';

  @override
  String get connectYourWatchToChangeSettings => '설정을 변경하려면 워치를 연결하세요.';

  @override
  String get stopNotConfirmed => '중지가 확인되지 않음';

  @override
  String get couldNotOpenFlashlightPermission => '손전등 권한 화면을 열 수 없음';

  @override
  String get phoneServiceUnavailable => '휴대폰 서비스를 사용할 수 없음';

  @override
  String get alertStatusIsStale => '알림 상태가 오래됨';

  @override
  String get flashlightIsOn => '손전등이 켜져 있음';

  @override
  String get soundAndFlashlightAreOn => '소리 및 손전등이 켜져 있음';

  @override
  String get soundAlertStarted => '소리 알림 시작됨';

  @override
  String get lastAlertDidNotStart => '마지막 알림이 시작되지 않음';

  @override
  String get requestHadNoSoundOrFlashlight => '요청에 소리 또는 손전등이 포함되지 않음';

  @override
  String get alertStopped => '알림 중지됨';

  @override
  String get noAlertIsRunning => '실행 중인 알림 없음';

  @override
  String get findPhoneFromWatch => '워치에서 휴대폰 찾기';

  @override
  String get localApkTest => '로컬 APK 테스트';

  @override
  String get stopping => '중지 중…';

  @override
  String get stopAlert => '알림 중지';

  @override
  String get cameraPermissionIsRequiredToFindYourPhoneWith =>
      '손전등으로 휴대폰을 찾으려면 카메라 권한이 필요합니다.';

  @override
  String get allowFlashlight => '손전등 허용';

  @override
  String get incline42 => '경사 42°';

  @override
  String get north124 => '북쪽 124°';

  @override
  String get message182M => '182 M';

  @override
  String get activity2 => '활동';

  @override
  String get message620800Kcal => '620 / 800 KCAL';

  @override
  String get today => '오늘';

  @override
  String get message1930Meeting => '19:30 회의';

  @override
  String get watchSync => '워치 동기화';

  @override
  String get message72Bpm5Minago => '72 BPM • 5분 전';

  @override
  String get theWatchHasNotRepliedYetRefreshAgain =>
      '워치가 아직 응답하지 않았습니다. 다시 새로 고치세요.';

  @override
  String get dataUpdated => '데이터를 업데이트했습니다';

  @override
  String get couldNotSendRequest => '요청을 보내지 못했습니다';

  @override
  String get noWatchDataYet => '워치 데이터가 아직 없음';

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
  String get chargingUnknown => '충전 상태 알 수 없음';

  @override
  String get charging => '충전 중';

  @override
  String get notCharging => '충전 중 아님';

  @override
  String get battery2 => '배터리: —';

  @override
  String batteryReading(String value0, String value1, String value2) {
    return '$value0: $value1% · $value2';
  }

  @override
  String get noConnectionToWatch => '워치에 연결되지 않음';

  @override
  String get waitingForWatchReply => '워치 응답 대기 중…';

  @override
  String get refreshData => '데이터 새로 고침';

  @override
  String get updated => '업데이트됨';

  @override
  String get dataIsStale => '데이터가 오래됨';

  @override
  String get battery3 => '배터리';

  @override
  String get lastBattery => '마지막 배터리 정보';

  @override
  String get live => '실시간';

  @override
  String get bluetooth => 'Bluetooth';

  @override
  String get wiFi => 'Wi-Fi';

  @override
  String get digitalCrown => 'Digital Crown';

  @override
  String get appleWatch => 'Apple Watch';

  @override
  String get watch => '시계';

  @override
  String get ultra => 'Ultra';

  @override
  String get wayfinder => '길잡이';

  @override
  String get ultraModular => '울트라 모듈';

  @override
  String get ultraModularTrail => '울트라 모듈 트레일';

  @override
  String get nikeBounceVolt => 'Nike 바운스 볼트';

  @override
  String get appleMusic => 'Apple Music';

  @override
  String get appleAccount => 'Apple 계정';

  @override
  String get watchConnection => '워치 연결';

  @override
  String get savedWatch => '저장된 워치';

  @override
  String get noPairedWatch => '페어링된 워치 없음';

  @override
  String get noPairedWatchHint => '워치를 가까이 두고 페어링 모드로 전환하여 시작하세요.';

  @override
  String get bridgeUnavailable => 'Bridge를 사용할 수 없음';

  @override
  String get bridgeUnavailableHint => '워치를 연결하려면 호환되는 Bridge 백엔드를 설치하세요.';

  @override
  String get identityLoading => '저장된 페어링을 읽는 중…';

  @override
  String get identityReadError =>
      '저장된 페어링을 사용할 수 없습니다. 새로 페어링하기 전에 진단 정보를 확인하세요.';

  @override
  String get connectWatch => '연결';

  @override
  String get disconnectWatch => '연결 해제';

  @override
  String get connectionConnecting => '연결 중…';

  @override
  String get connectionStopping => '연결 해제 중…';

  @override
  String get connectionPermissions => 'Bluetooth 접근 허용';

  @override
  String get resumeWatchSetup => '설정 계속하기';

  @override
  String get finishWatchSetup => '설정 완료';

  @override
  String get watchStillSettingUp => '워치에 아직 설정 화면이 표시됨';

  @override
  String get resumeWatchSync => '동기화 재개';

  @override
  String get resumeWatchSyncHint =>
      '워치에 아직 설정 화면이 표시될 때만 사용하세요. 시계 페이스가 보이면 대신 설정을 완료하세요.';

  @override
  String get confirmWatchFace => '시계 페이스가 표시됨';

  @override
  String get confirmWatchFaceHint =>
      '워치에 시계 페이스가 표시된 후에만 확인하세요. 현재 설정 연결을 안전하게 중지합니다.';

  @override
  String get confirmWatchFaceQuestion => '워치에 시계 페이스가 표시되나요?';

  @override
  String get confirmWatchFaceDetail => '현재 활성화된 페어링의 연결 방식을 일반 연결 모드로 저장합니다.';

  @override
  String get connectionRequestAccepted => '요청을 수락했습니다. 워치를 기다리는 중…';

  @override
  String get connectionRequestRejected =>
      '요청이 수락되지 않았습니다. 현재 연결과 진단 정보를 확인하세요.';

  @override
  String get connectionPermissionRequired => 'Bluetooth 접근을 허용한 뒤 다시 시도하세요.';

  @override
  String get connectionBusy => '연결 또는 설정 작업이 이미 진행 중입니다.';

  @override
  String get syncCurrentWifi => '휴대폰 Wi-Fi 네트워크 보내기';

  @override
  String get wifiReceiptHint =>
      '네트워크가 전송 대기열에 추가되었습니다. 워치의 설정 → Wi-Fi에서 확인하세요.';

  @override
  String get watchDiagnostics => '연결 진단';

  @override
  String get bridgeJournal => 'Bridge 로그';

  @override
  String get copyBridgeJournal => '로그 복사';

  @override
  String get journalCopied => '로그를 복사했습니다';

  @override
  String get advancedConnectionTools => '고급 연결 도구';

  @override
  String get stockBondAudit => '시스템 Bluetooth 본딩 검사';

  @override
  String get stockBondImport => '시스템 Bluetooth 본딩 가져오기';

  @override
  String get stockIdentityAlignment => '시스템 Bluetooth 식별 정보 맞추기';

  @override
  String get stockReconnectProbe => '시스템 재연결 테스트';

  @override
  String get advancedToolHint => '이 도구를 사용하려면 연결을 중지하고 시스템 Bluetooth를 꺼야 합니다.';

  @override
  String get advancedToolQuestion => '이 연결 도구를 실행할까요?';

  @override
  String get advancedToolDetail => '백엔드에서 실행 전에 저장된 페어링과 Bluetooth 상태를 검증합니다.';

  @override
  String get pairingDiscovering => '워치를 찾는 중…';

  @override
  String get pairingConnecting => '발견한 워치에 연결 중…';

  @override
  String get pairingSecurity => '보안 페어링 설정 중…';

  @override
  String get pairingActivation => 'Apple 서버에서 활성화 중…';

  @override
  String get pairingIds => '워치 서비스에 연결 중…';

  @override
  String get pairingRegistry => '워치 구성을 읽는 중…';

  @override
  String get pairingConfiguring => '워치 설정 적용 중…';

  @override
  String get pairingActivated => '워치 활성화됨';

  @override
  String get pairingCheckWatch => '초기 동기화를 보냈습니다. 워치를 확인하세요.';

  @override
  String get pairingVerifyReconnect => '워치 설정 상태를 확인했습니다. 연결 검증은 대기 중입니다.';

  @override
  String get pairingPaused => '설정 연결 중지됨';

  @override
  String get watchActivatedHint => '활성화가 완료되었습니다. 워치에 시계 페이스가 표시되면 아래에서 확인하세요.';

  @override
  String get confirmWatchFaceActiveDetail =>
      '설정 세션을 안전하게 연결 해제하고 이 활성화된 페어링의 연결 방식을 일반 연결 모드로 저장합니다.';

  @override
  String get pairingSync => '워치 설정 동기화 중…';

  @override
  String get pairingVerified => '워치 설정 완료';

  @override
  String get pairingFailed => '설정 세션이 오류로 종료되었습니다. 로그를 확인하세요.';

  @override
  String get pairingIdle => '진행 중인 설정 세션이 없습니다.';

  @override
  String get pairingStarting => '워치 세션 시작 중…';

  @override
  String get pairingPinHint => '워치에 표시된 6자리 코드를 입력하세요.';

  @override
  String get pairingCheckingCode => '워치 코드 확인 중…';

  @override
  String get pairingCodeVerifiedBondPending =>
      '워치 코드를 확인했습니다. 이 빌드에서는 Bluetooth 본딩을 아직 사용할 수 없습니다.';

  @override
  String get macosPairingBootstrapRestricted =>
      'macOS에서 워치 페어링에 필요한 Bluetooth 채널을 제한합니다. 자세한 내용은 진단 정보를 확인하세요.';

  @override
  String get windowsPairingBootstrapRestricted =>
      'Bluetooth 검색과 연결은 사용할 수 있습니다. 이 빌드에서는 Windows가 Apple Watch 페어링에 필요한 채널을 제공하지 않습니다.';

  @override
  String get windowsRawHciRequired =>
      '워치의 전체 페어링 기능을 사용하려면 함께 제공되는 프로토콜 런타임과 서명된 UsbDk 1.0.21이 필요합니다. 1.0.22 버전은 Windows 충돌을 일으킬 수 있어 차단됩니다. USB Bluetooth 어댑터를 선택하세요. 세션 중에는 해당 어댑터를 워치 전용으로 사용합니다.';

  @override
  String get sendWatchPin => '코드 보내기';

  @override
  String get pairingCodeOnlyHint => '이 컴퓨터에서는 워치에 표시된 6자리 코드로 페어링하세요.';

  @override
  String get findNearbyWatch => '워치 찾기';

  @override
  String get bluetoothLinkConnected => 'Bluetooth 링크 연결됨';

  @override
  String get watchPairingUnavailable => '이 플랫폼에서는 워치 페어링 서비스를 아직 사용할 수 없습니다.';

  @override
  String get activationOwnerInput => 'Apple에서 소유자 확인을 요청했습니다';

  @override
  String get activationCredentialsHint =>
      '자격 증명은 Apple 활성화 서비스로 전송되며 Companion에는 저장되지 않습니다.';

  @override
  String get submitActivation => '활성화 계속하기';

  @override
  String get retryActivation => '활성화 다시 시도';

  @override
  String get stopSetup => '설정 중지';

  @override
  String get pairedIdentity => '페어링 ID';

  @override
  String get hardwareIdentity => '하드웨어 식별자';

  @override
  String get systemBuild => '시스템 빌드';

  @override
  String get pendingWatchSetup => '이 저장된 페어링은 설정이 완료되지 않았습니다.';

  @override
  String get oneWatchBackend =>
      '백엔드는 현재 페어링 하나를 저장합니다. 연결을 해제해도 저장된 페어링은 유지됩니다.';

  @override
  String get chooseDiscoveredWatch =>
      '주변에서 발견한 워치를 선택하세요. 지원되는 설정 광고 신호만 표시됩니다.';

  @override
  String get desktopCoreUnavailable =>
      '연결 서비스를 시작할 수 없습니다. 자세한 내용은 진단 정보를 확인하세요.';

  @override
  String get manageWatchFaces => '시계 페이스 관리';

  @override
  String get nativeFaceChangesConfirmed => '워치에서 변경 사항을 확인했습니다.';

  @override
  String get nativeFaceWaiting => '워치의 변경 사항 확인 대기 중…';

  @override
  String get nativeFaceSaving => '변경 사항 저장 중…';

  @override
  String get nativeFaceRetry => '다시 시도';

  @override
  String get nativeFaceEditPending => '이 기기에 변경 사항을 저장했습니다. 워치를 기다리는 중…';

  @override
  String get nativeFaceSaveFailed =>
      '이 기기에 변경 사항을 저장할 수 없습니다. 편집기를 닫지 말고 다시 시도하세요.';

  @override
  String get nativeFaceEditUncertain =>
      '변경 사항을 이 기기에 저장했지만 워치에서 확인하지 못했습니다. 다시 시도하여 재전송하세요.';

  @override
  String get nativeFaceRejected =>
      '변경 사항을 보내지 못했습니다. 페이스 모음을 새로 고친 뒤 다시 시도하세요.';

  @override
  String get nativeFaceUnknown => '결과가 확인되지 않았습니다. 다른 항목을 변경하기 전에 새로 고치세요.';

  @override
  String get nativeFaceCustomization => '사용자화';

  @override
  String get nativeFaceComplications => '컴플리케이션';

  @override
  String get nativeFaceSetActive => '현재 페이스로 설정';

  @override
  String get nativeFaceDelete => '시계 페이스 삭제';

  @override
  String get nativeFaceDeleteQuestion => '이 시계 페이스를 삭제할까요?';

  @override
  String get nativeFaceDeleteDetail =>
      '나머지 페이스는 워치에 유지됩니다. 마지막 페이스는 삭제할 수 없습니다.';

  @override
  String get nativeFaceMoveEarlier => '앞으로 이동';

  @override
  String get nativeFaceMoveLater => '뒤로 이동';

  @override
  String get nativeFaceConfiguration => '네이티브 구성';

  @override
  String get nativeFaceApply => '워치에 적용';

  @override
  String get nativeFaceValueHint =>
      'JSON 값을 입력하세요. 문자열은 따옴표로 감싸고 숫자와 불리언은 JSON 표기법을 사용해야 합니다.';

  @override
  String get nativeFaceInvalidValue => '올바르지 않은 구성 값';

  @override
  String get nativeFaceConfigurationUnavailable =>
      '이 페이스의 구성을 불러오려면 Bridge를 업데이트하고 페이스 모음을 새로 고치세요.';

  @override
  String get nativeFaceFileSaved => '시계 페이스 파일을 저장했습니다';

  @override
  String get nativeFaceFileError => '시계 페이스 파일을 열거나 저장할 수 없습니다';

  @override
  String get nativeFaceResourceExportUnavailable =>
      '이 페이스에는 사진이나 다른 리소스가 필요할 수 있습니다. 전체 네이티브 패키지를 읽을 수 있을 때까지 내보내기를 사용할 수 없습니다.';

  @override
  String get nativeFaceComplicationHint =>
      '워치 카탈로그나 기존 페이스에서 호환되는 컴플리케이션을 선택하세요. 저장된 설정과 네이티브 인텐트는 유지됩니다.';

  @override
  String get nativeFaceComplicationCatalogPartial =>
      '일부 설명 정보를 사용할 수 없습니다. 호환되는 선택 항목을 업데이트하려면 페이스 모음을 새로 고치세요.';

  @override
  String get nativeFaceComplicationVariantsMissing =>
      '이 페이스의 일부 컴플리케이션에서 사용 가능한 변형 정보를 제공하지 않았습니다. 현재 설정은 계속 사용할 수 있습니다.';

  @override
  String get nativeFaceNoComplication => '없음';

  @override
  String get nativeFaceUnknownComplication => '알 수 없는 컴플리케이션';

  @override
  String get nativeFaceConflict =>
      '이 페이스가 변경되었거나 워치가 다시 연결되었습니다. 편집 내용을 적용하기 전에 페이스를 다시 여세요.';

  @override
  String get nativeFaceTimeStyle => '시간 스타일';

  @override
  String get nativeFaceDialStyle => '다이얼 스타일';

  @override
  String get nativeFaceWaypoint => '경유지';

  @override
  String get nativeFacePink => '핑크';

  @override
  String get nativeFaceNeonGreen => '네온 그린';

  @override
  String get nativeFaceOrange => '오렌지';

  @override
  String get nativeFacePinkSand => '핑크 / 샌드';

  @override
  String get nativeFaceNeonGreenCloud => '네온 그린 / 클라우드';

  @override
  String get nativeFaceOrangeLightSage => '오렌지 / 라이트 세이지';

  @override
  String get nativeFaceLightBlue => '라이트 블루';

  @override
  String get nativeFaceBrightBlue => '브라이트 블루';

  @override
  String get nativeFaceTerraCotta => '테라코타';

  @override
  String get nativeFaceTopLeft => '왼쪽 상단';

  @override
  String get nativeFaceTop => '상단';

  @override
  String get nativeFaceBottom => '하단';

  @override
  String get nativeFaceTopRight => '오른쪽 상단';

  @override
  String get nativeFaceBottomLeft => '왼쪽 하단';

  @override
  String get nativeFaceBottomRight => '오른쪽 하단';

  @override
  String get nativeFaceCenter => '중앙';

  @override
  String get nativeFaceNightMode => '야간 모드';

  @override
  String get nativeFaceColor => '색상';

  @override
  String get nativeFaceAutomatic => '자동';

  @override
  String get nativeFaceLeft => '왼쪽';

  @override
  String get nativeFaceRight => '오른쪽';

  @override
  String nativeFacePalette(String number) {
    return '팔레트 $number';
  }

  @override
  String nativeFaceSeasonalPalette(String number) {
    return '시즌 색상 $number';
  }

  @override
  String get nativeFaceAdvanced => '고급';

  @override
  String get nativeFaceEditHint => '원하는 설정을 선택한 뒤 워치에 적용하세요.';

  @override
  String get nativeFaceCopyHint =>
      '워치에 있는 페이스로 새 페이스를 만들거나 공유받은 .watchface 파일을 가져오세요.';

  @override
  String get nativeFaceLocalLibrary => '로컬 디자인 라이브러리';

  @override
  String get nativeFaceUnsaved => '저장하지 않은 변경 사항을 버릴까요?';

  @override
  String get nativeFaceDiscard => '버리기';

  @override
  String get nativeFaceImport => '시계 페이스 가져오기';

  @override
  String get nativeFaceSharedPreview => '공유 미리보기';

  @override
  String get nativeFaceImportDone => '완료';

  @override
  String get nativeFaceSharedPreviewUnavailable =>
      '이 파일에는 사용할 수 있는 공유 미리보기가 없습니다.';

  @override
  String get nativeFaceSharedPreviewHint =>
      '파일과 함께 제공된 미리보기입니다. 호환 여부와 설치 성공 여부는 워치에서 확인합니다.';

  @override
  String get nativeFaceDraft => '저장하지 않은 변경 사항';

  @override
  String get nativeFaceDigital => '디지털';

  @override
  String get nativeFaceAnalog => '아날로그';

  @override
  String get nativeFaceAdd => '시계 페이스 추가';

  @override
  String get nativeFaceAvailable => '사용 가능한 페이스';

  @override
  String get nativeFaceAddToWatch => '워치에 추가';

  @override
  String get nativeFaceDefaultPreview => '기본 미리보기';

  @override
  String get nativeFacePreviewUnavailable => '이 설정에서는 미리보기를 사용할 수 없습니다';

  @override
  String get nativeFaceStylePreview => '스타일 미리보기';

  @override
  String get nativeFaceStylePreviewHint =>
      '예시 시간입니다. 실시간 데이터와 컴플리케이션은 워치에 표시됩니다.';

  @override
  String get nativeFaceSearch => '검색';

  @override
  String get nativePhotoChoose => '사진 선택';

  @override
  String get nativePhotoCropHint =>
      '사진을 드래그하여 구도를 맞추세요. 손가락을 벌리거나 슬라이더를 사용해 확대할 수 있습니다.';

  @override
  String nativeIntentSettings(String slot) {
    return '$slot 설정';
  }

  @override
  String nativeIntentParameterSeconds(String name) {
    return '$name (초)';
  }

  @override
  String nativeIntentDurationValue(String name, String seconds) {
    return '$name: $seconds초';
  }

  @override
  String get nativeIntentParameterInvalid => '지원 범위 내의 유한한 시간을 입력하세요.';

  @override
  String get nativeIntentLocalDraft => '이 설정은 초안에 저장됩니다. 워치로 보내려면 페이스를 적용하세요.';

  @override
  String get nativePhotoZoom => '확대';

  @override
  String get nativePhotoAspect => '프레임 모양';

  @override
  String get nativePhotoKeepCrop => '자르기 적용';

  @override
  String nativePhotoSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '사진 $count장 선택됨',
      one: '사진 1장 선택됨',
    );
    return '$_temp0';
  }

  @override
  String get nativePhotoTimeAlignment => '시간 정렬';

  @override
  String get nativePhotoLeading => '시작 쪽';

  @override
  String get nativePhotoTrailing => '끝 쪽';

  @override
  String get nativePhotoTimeSize => '시간 크기';

  @override
  String get nativePhotoSmall => '작게';

  @override
  String get nativePhotoMedium => '중간';

  @override
  String get nativePhotoLarge => '크게';

  @override
  String get nativePhotoXLarge => '매우 크게';

  @override
  String get nativePhotoRemove => '사진 제거';

  @override
  String get nativeFaceShade => '색조';

  @override
  String get nativeFaceAddColors => '색상 추가';

  @override
  String get nativeFaceColorsDone => '완료';

  @override
  String get nativeFaceColorsSaveError => '색상을 저장할 수 없습니다. 다시 시도하세요.';

  @override
  String get nativeFaceColorsPending => 'Apple Watch의 색상 설정을 기다리는 중입니다.';

  @override
  String get nativeFaceColorsAwaiting => 'Apple Watch에 색상을 보내는 중입니다.';

  @override
  String get nativeFaceColorsUncertain => 'Apple Watch에서 다른 색상을 보고했습니다.';

  @override
  String get nativeFaceColorsSent => 'Apple Watch에 색상을 보냈습니다.';

  @override
  String get nativeFaceColorsDeliveryUncertain => '색상 전달 여부를 확인할 수 없습니다.';

  @override
  String get nativeFaceColorsRetry => '색상 동기화 다시 시도';

  @override
  String get nativeFaceMonogram => '모노그램';

  @override
  String get nativeMonogramDone => '완료';

  @override
  String get nativeMonogramInvalid => '이 텍스트는 모노그램으로 사용할 수 없습니다.';

  @override
  String get nativeMonogramUnavailable => '모노그램을 편집하려면 Apple Watch를 연결하세요.';

  @override
  String get nativeMonogramSaveError => '모노그램을 저장할 수 없습니다. 다시 시도하세요.';

  @override
  String get nativeMonogramAwaiting => 'Apple Watch에 모노그램을 보내는 중입니다.';

  @override
  String get nativeMonogramDelivered =>
      '모노그램을 전달했습니다. Apple Watch의 확인을 기다리는 중입니다.';

  @override
  String get nativeMonogramUncertain => '모노그램 동기화를 확인할 수 없습니다.';

  @override
  String get nativeMonogramConfirmed => 'Apple Watch에서 모노그램을 확인했습니다.';

  @override
  String get nativeFaceSeeAllWatchFaces => '모든 시계 페이스 보기';

  @override
  String get nativeFaceAllWatchFaces => '모든 시계 페이스';

  @override
  String get nativeFaceLibraryEdit => '편집';

  @override
  String get nativeFaceLibraryDone => '완료';

  @override
  String nativeFaceLibraryReorder(String face) {
    return '$face 순서 변경';
  }

  @override
  String get notificationForwarding => '휴대전화 알림 전달';

  @override
  String get notificationForwardingDescription =>
      'Android 앱의 알림을 Apple Watch로 전달합니다. 다시 연결하면 기존 알림이 조용히 복원됩니다.';

  @override
  String get notificationBridgeUnavailable =>
      '알림 전달을 설정하려면 최신 Android Bridge에 연결하세요.';

  @override
  String get notificationAccessRequired => '휴대전화에서 알림 접근 권한을 허용하세요.';

  @override
  String get notificationForwardingPaused => '알림 전달이 일시 중지되었습니다.';

  @override
  String get notificationListenerWaiting => 'Android 알림 접근 서비스 연결을 기다리는 중입니다.';

  @override
  String get notificationWatchWaiting => 'Apple Watch 연결을 기다리는 중입니다.';

  @override
  String get notificationForwardingReady => '휴대전화 알림을 전달할 준비가 되었습니다.';

  @override
  String get notificationAllowAccess => '알림 접근 허용';

  @override
  String get notificationManageAccess => '알림 접근 관리';

  @override
  String get notificationAccessDescription =>
      'Android 설정에서 Apple Watch Notification Mirror를 활성화하세요.';

  @override
  String get notificationAccessFailed => '알림 접근 설정을 열 수 없습니다.';

  @override
  String get notificationSettingsFailed => '알림 설정을 저장할 수 없습니다. 다시 시도하세요.';

  @override
  String get notificationAppDiscovery =>
      '앱이 알림을 보내면 여기에 표시됩니다. 전달은 기본적으로 활성화되어 있으며, 워치에서 받지 않을 앱은 끌 수 있습니다.';

  @override
  String get notificationNoApps => '아직 감지된 앱이 없습니다';

  @override
  String notificationPending(int count) {
    return '$count개의 알림 또는 삭제 요청이 전송 대기 중입니다.';
  }

  @override
  String get notificationTestDescription => '알림 전달 준비가 완료되면 테스트 알림을 보냅니다.';

  @override
  String get notificationTestBody => 'Watch Companion에서 보낸 테스트 알림입니다.';

  @override
  String get notificationQueued => '알림 대기열에 추가됨';

  @override
  String get notificationQueuedDetails =>
      '테스트 알림이 Apple Watch 전송 대기열에 추가되었습니다. 워치 화면에서 표시되는지 확인하세요.';

  @override
  String get notificationTestFailed => '알림을 대기열에 추가하지 못함';

  @override
  String get notificationTestFailedDetails =>
      '테스트 알림을 대기열에 추가할 수 없습니다. 워치 연결을 확인한 후 다시 시도하세요.';

  @override
  String get notificationOnlyWhenLocked => '휴대전화가 잠겨 있을 때만';

  @override
  String get notificationOnlyWhenLockedDescription =>
      '휴대전화 화면이 잠금 해제되어 활성 상태일 때는 워치 알림을 표시하지 않습니다.';

  @override
  String get notificationSearchApps => '앱 검색';

  @override
  String get notificationFilterAll => '전체';

  @override
  String get notificationFilterActive => '활성';

  @override
  String get notificationRetryPending => '대기 중인 전송 다시 시도';

  @override
  String get notificationTestAcknowledged =>
      'Apple Watch가 IDS를 통해 수신을 확인했습니다. 워치 화면에 표시되었는지 확인하세요.';

  @override
  String get notificationTestDeliveryFailed =>
      '테스트 알림이 제한 시간 내에 Apple Watch에 도달하지 못했습니다. 워치 연결을 확인하고 다시 시도하세요.';

  @override
  String get updateUnavailable => '이 연결에서는 소프트웨어 업데이트를 사용할 수 없습니다.';

  @override
  String get updateConnect => '업데이트를 확인하려면 Apple Watch를 연결하세요.';

  @override
  String get updateUnknown => '워치의 업데이트 상태를 기다리는 중입니다.';

  @override
  String get updateIdle => '현재 선택된 업데이트가 없습니다. 업데이트 확인을 눌러 Apple 카탈로그를 조회하세요.';

  @override
  String get updateAvailable => '업데이트 사용 가능';

  @override
  String get updatePreparing => 'Apple Watch에서 다운로드 및 준비 중';

  @override
  String get updateWaitingPreparation => '준비 조건 충족 대기 중';

  @override
  String get updatePrepared => '설치 준비 완료';

  @override
  String get updateWaitingInstallation => '설치 조건 충족 대기 중';

  @override
  String get updateInstalling => 'Apple Watch에 설치 중';

  @override
  String get updateRestart => '워치가 재시동되어 새 빌드를 확인할 때까지 기다리는 중입니다';

  @override
  String get updateComplete => '워치가 설치된 빌드를 확인했습니다.';

  @override
  String get updateFailed => '워치에서 업데이트 오류가 보고되었습니다. 계속하기 전에 상태를 새로 고치세요.';

  @override
  String get updateStalled => '워치에서 진행이 중단되었다고 보고했습니다.';

  @override
  String updateStageProgress(String percent) {
    return '현재 단계 $percent%';
  }

  @override
  String get updateRequirements =>
      '워치를 배터리가 50% 이상인 상태로 충전기에 올려두고 Wi-Fi에 연결된 상태를 유지하세요. Companion을 가까이에 두세요. 다운로드, 검증 및 설치는 워치에서 직접 수행됩니다.';

  @override
  String get updateTerms =>
      '워치의 설정 → 일반 → 소프트웨어 업데이트에서 업데이트 약관을 검토하고 동의한 후 여기에서 새로 고치세요.';

  @override
  String get updateDownload => '다운로드 및 준비';

  @override
  String get updateInstall => '업데이트 설치';

  @override
  String get updateRefresh => '상태 새로 고침';

  @override
  String updateConfirm(String version, String build) {
    return 'watchOS $version($build)을(를) 설치하시겠습니까? 워치가 재시동됩니다. 설치가 완료될 때까지 충전기에 올려두세요.';
  }

  @override
  String get updateQueued => '요청이 대기열에 추가되었습니다. 워치의 확인을 기다리는 중입니다.';

  @override
  String get updateNotQueued => '요청을 대기열에 추가할 수 없습니다. 워치 상태를 새로 고친 후 다시 시도하세요.';

  @override
  String get updateUnconfirmed => '전송이 확인되지 않았습니다. 다른 명령을 보내기 전에 상태를 새로 고치세요.';

  @override
  String get updateInstallUnconfirmed =>
      '설치 요청이 이미 전송되었습니다. 상태를 새로 고치거나 워치에서 설치를 완료하세요. 요청은 자동으로 다시 전송되지 않습니다.';

  @override
  String updateDownloadSize(String size) {
    return '다운로드: $size';
  }

  @override
  String updateRequiredSpace(String size) {
    return '필요한 여유 공간: $size';
  }

  @override
  String get opticalCameraPermissionDenied =>
      'Apple Watch를 스캔하려면 카메라 권한이 필요합니다.';

  @override
  String get opticalNoCamera => '이 기기에서는 카메라를 사용할 수 없습니다.';

  @override
  String get filterJournal => '로그 필터';

  @override
  String get jumpToBottom => '맨 아래';

  @override
  String get copiedToClipboard => '복사됨';

  @override
  String get noMatchingJournalLines => '일치하는 로그 항목 없음';

  @override
  String get watchConnectionSubtitle => '페어링, 재연결 및 브리지 링크 상태 확인';
}
