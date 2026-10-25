// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get bluetoothAdapter => '蓝牙适配器';

  @override
  String get bluetoothController => '蓝牙控制器';

  @override
  String get refreshBluetoothAdapters => '刷新适配器';

  @override
  String get bluetoothAdaptersLoading => '正在查找蓝牙适配器…';

  @override
  String get bluetoothAdaptersEmpty => '未找到蓝牙适配器。请接入适配器后刷新列表。';

  @override
  String get bluetoothAdapterChoiceHint => '为手表选择适配器。所选适配器会用于后续连接。';

  @override
  String get bluetoothAdapterDisconnectHint => '更换蓝牙适配器前，请先断开手表连接。';

  @override
  String get bluetoothAdapterMissing => '已保存的适配器不可用';

  @override
  String get bluetoothAdapterMissingHint => '请重新连接已保存的适配器，或改选其他适配器。手表配对关系会保留。';

  @override
  String get bluetoothAdapterDiscoveryFailed =>
      '无法读取蓝牙适配器。请确认 BlueZ 正在运行，然后刷新列表。';

  @override
  String get bluetoothAdapterSaveFailed =>
      '无法读取或保存适配器设置。请检查应用状态文件夹的访问权限，然后重新选择适配器。';

  @override
  String get bluetoothAdapterSaved => '蓝牙适配器已保存。';

  @override
  String get opticalScanTitle => '配对 Apple Watch';

  @override
  String get opticalPairWithCamera => '用相机配对';

  @override
  String get opticalScanHint => '让配对动画保持在取景框内，然后点按“扫描”。读取过程中请保持手机稳定。';

  @override
  String get opticalScan => '扫描';

  @override
  String get opticalNoMatch => '未识别到配对动画。请把相机对准手表后重试。';

  @override
  String get opticalRecognizedTitle => '已找到 Apple Watch';

  @override
  String opticalRecognizedDetail(String name) {
    return '已识别 $name 的配对码。下一步是蓝牙认证。';
  }

  @override
  String opticalReplaceDetail(String name) {
    return '已识别 $name 的配对码。手表需要处于等待新配对的状态。替换前会先备份原有的加密配对。';
  }

  @override
  String get opticalReplacePair => '替换已保存的配对';

  @override
  String get opticalStartPairing => '配对手表';

  @override
  String opticalAnalyzedFrames(int count) {
    return '已分析 $count 帧';
  }

  @override
  String get opticalPairingLab => '光学配对实验室';

  @override
  String get opticalCaptureHint =>
      '让配对动画保持在取景框内。可录制一小段样本以测试光学解码器。识别功能尚未启用。离开此页面时样本会被删除。';

  @override
  String get opticalCameraError => '相机拍摄失败。请检查相机权限后重试。';

  @override
  String opticalFrames(int count) {
    return '已采集 $count 帧';
  }

  @override
  String get opticalCapture => '采集样本';

  @override
  String get opticalRetry => '重试';

  @override
  String get opticalStopCapture => '停止采集';

  @override
  String get backendFeatures => '可用功能';

  @override
  String get backendFeaturesHint =>
      '此处列出的是当前连接后端已实现的服务。连上之后也不代表所有 watchOS 功能、机型或固件版本都受支持。';

  @override
  String get backendFeaturesUnknown => '尚未收到连接服务发来的功能信息。';

  @override
  String get featureNotSupported => '暂不支持';

  @override
  String get phoneFindFeature => '查找手机';

  @override
  String get ok => '好';

  @override
  String get digitalCrownTimeTravel => '数码表冠 / 时间旅行';

  @override
  String get rightWrist => '右手佩戴';

  @override
  String get rotateScreen180 => '屏幕旋转 180°';

  @override
  String get message24Hourtime => '24 小时制';

  @override
  String get readyToConnect => '可以连接';

  @override
  String get workout => '体能训练';

  @override
  String get trackActivityHeartRatePowerZonesAndGpsRoutes =>
      '记录活动、心率、功率区间和 GPS 路线。';

  @override
  String get activity => '活动';

  @override
  String get moveExerciseAndStandRings => '活动、锻炼和站立圆环。';

  @override
  String get heartRate => '心率';

  @override
  String get heartRateMeasurementsAndAlerts => '心率测量与提醒。';

  @override
  String get compassWaypoints => '指南针与航点';

  @override
  String get elevationInclineCoordinatesAndBacktrackRoutes => '海拔、坡度、坐标和回溯路线。';

  @override
  String get music => '音乐';

  @override
  String get streamAudioAndControlPlayback => '串流音频并控制播放。';

  @override
  String get messages => '信息';

  @override
  String get sendAndReceiveMessagesUseDictationAndQuickReplies =>
      '收发信息，支持听写和快捷回复。';

  @override
  String get phone => '电话';

  @override
  String get callsOverBluetoothOrCellular => '通过蓝牙或蜂窝网络通话。';

  @override
  String get weather => '天气';

  @override
  String get forecastsUvIndexPrecipitationAndWind => '天气预报、紫外线指数、降水和风力。';

  @override
  String get maps => '地图';

  @override
  String get turnByTurnDirectionsWithHapticFeedback => '逐向导航，带触感反馈。';

  @override
  String get noise => '噪声';

  @override
  String get monitorAmbientNoiseLevelsInDecibels => '以分贝为单位监测环境噪声。';

  @override
  String get naturalTitanium => '原色钛金属';

  @override
  String get message49Mm => '49 毫米';

  @override
  String get battery => '电池';

  @override
  String get message72Bpm => '72 次/分';

  @override
  String get message21Sunny => '+21° 晴';

  @override
  String get compass => '指南针';

  @override
  String get message124Se182M => '124° 东南 • 182 米';

  @override
  String get outdoorRun => '户外跑步';

  @override
  String get nowPlaying => '正在播放';

  @override
  String get calendar => '日历';

  @override
  String get meetingAt1930 => '19:30 开会';

  @override
  String get custom => '自定';

  @override
  String get imported => '已导入';

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
  String get pointTheCameraAtYourAppleWatchScreen => '将相机对准 Apple Watch 屏幕';

  @override
  String get enterThe6DigitCodeShownOnYourWatch => '输入手表上显示的 6 位代码';

  @override
  String get waitingForBridgeConfirmation => '正在等待 Bridge 确认…';

  @override
  String get setupConfirmedByTheWatch => '手表已确认设置';

  @override
  String get setupIsNotConfirmedYetAppleWatchBridgeIs =>
      '设置尚未确认。Apple Watch Bridge 仍在配置手表。';

  @override
  String get noSetupConfirmationFromBridge => 'Bridge 未返回设置确认';

  @override
  String get discover => '发现';

  @override
  String get newInWatchos11 => '手表伴侣';

  @override
  String get appleWatchUltra2OnAndroid => 'Apple Watch 伴侣';

  @override
  String get syncNotificationsCallsWorkoutsAndWatchFacesWithYour =>
      '用手机或电脑连接 Apple Watch。当前连接后端实现了哪些服务，请到“我的手表”里的“可用功能”查看。';

  @override
  String get guidesTips => '指南与技巧';

  @override
  String get gesturesControls => '手势与操作';

  @override
  String get doubleTapDigitalCrownAndActionButton => '互点两下、数码表冠和操作按钮';

  @override
  String get healthWorkouts => '健康与体能训练';

  @override
  String get setUpHeartRateZonesGpsRoutesAndActivity => '设置心率区间、GPS 路线和活动圆环';

  @override
  String get compassBacktrack => '指南针与回溯';

  @override
  String get navigateWithoutAnInternetConnection => '无需联网也能导航';

  @override
  String get notificationMirroring => '通知镜像';

  @override
  String get setUpSmsCallsAndMessagingApps => '设置短信、通话和各类即时通讯应用';

  @override
  String get resources => '资源';

  @override
  String get appleWatchUserGuide => 'Apple Watch 使用手册';

  @override
  String get halSupportDiagnostics => 'HAL 支持与诊断';

  @override
  String get appleWatchUltra2Controls => 'Apple Watch Ultra 2 操作';

  @override
  String get useDoubleTapTheDigitalCrownAndTheOrange => '可使用互点两下、数码表冠和橙色操作按钮。';

  @override
  String get essentialGestures => '常用手势';

  @override
  String get doubleTap => '互点两下';

  @override
  String get tapYourIndexFingerAndThumbTogetherTwiceTo =>
      '用食指和拇指互点两下，即可接听电话、停止计时器或滚动智能叠放。';

  @override
  String get turnToScrollThroughListsAndZoomOrPress => '转动可滚动列表、缩放画面，按下可返回表盘。';

  @override
  String get orangeActionButton => '橙色操作按钮';

  @override
  String get startAWorkoutMarkAWaypointOrHoldTo => '开始体能训练、标记航点，长按可启动 86 分贝警笛。';

  @override
  String get waterLock => '入水锁定';

  @override
  String get preventsAccidentalTouchesInWaterUseTheDigitalCrown =>
      '防止在水中误触。转动数码表冠可解锁屏幕并排出扬声器里的水。';

  @override
  String get faceGallery => '表盘图库';

  @override
  String get save => '保存';

  @override
  String get reset => '重置';

  @override
  String get colorPalette => '配色方案';

  @override
  String get nightMode => '夜间模式';

  @override
  String get bezelStyle => '表圈样式';

  @override
  String get elevationIncline => '海拔 / 坡度';

  @override
  String get seconds => '秒';

  @override
  String get noBezel => '无表圈';

  @override
  String get complicationsTapToChange => '复杂功能（点按可更改）';

  @override
  String get saveLayoutOnPhone => '在手机上保存布局';

  @override
  String get exportWatchfacePackage => '导出 .watchface 包';

  @override
  String chooseAComplicationFor(String value0) {
    return '为 $value0 选择复杂功能';
  }

  @override
  String get cancel => '取消';

  @override
  String get watchfaceGeneratedSuccessfully => '.watchface 已生成';

  @override
  String createdAnAppleWatchPackageBytes(String value0) {
    return '已创建 Apple Watch 包（$value0 字节）。 ';
  }

  @override
  String get includesFacePlistMetadataPlistAndSlotConfiguration =>
      '包含 Face.plist、metadata.plist 和槽位配置。';

  @override
  String get topSubdial => '上部小表盘';

  @override
  String get topLeft => '左上';

  @override
  String get topRight => '右上';

  @override
  String get center => '中间';

  @override
  String get bottomLeft => '左下';

  @override
  String get bottomRight => '右下';

  @override
  String get bottomSubdial => '下部小表盘';

  @override
  String get bezel => '表圈';

  @override
  String get import => '导入';

  @override
  String get onlineGallery => '在线图库';

  @override
  String get downloadedFromTheAppleWatchFaceCatalogServer =>
      '已从 Apple Watch 表盘目录服务器下载。';

  @override
  String get importedWayfinder => '已导入的寻路者';

  @override
  String get importAppleWatchFace => '导入 Apple Watch 表盘';

  @override
  String get loadAWatchfacePackageZipWithFacePlist =>
      '载入 .watchface 包（含 Face.plist 的 ZIP）';

  @override
  String get watchFaceImported => '表盘已导入！';

  @override
  String layoutSavedOnYourPhone(String value0) {
    return '布局“$value0”已保存在手机上。';
  }

  @override
  String get importSampleWatchface => '导入示例 .watchface';

  @override
  String get myWatch => '我的手表';

  @override
  String get about => '关于';

  @override
  String get general => '通用';

  @override
  String get name => '名称';

  @override
  String get model => '型号';

  @override
  String get modelNumber => '型号号码';

  @override
  String get watchCase => '表壳';

  @override
  String get watchosVersion => 'watchOS 版本';

  @override
  String get serialNumber => '序列号';

  @override
  String get networkAddresses => '网络地址';

  @override
  String get wiFiAddress => 'Wi-Fi 地址';

  @override
  String get storage => '存储空间';

  @override
  String get available => '可用';

  @override
  String get songs => '歌曲';

  @override
  String get photos => '照片';

  @override
  String get userApps => '用户 App';

  @override
  String get actionButton => '操作按钮';

  @override
  String get quickAccessToWorkoutsWaypointsTheStopwatchAndThe =>
      '快速打开体能训练、航点、秒表和紧急警报。';

  @override
  String get action => '操作';

  @override
  String get stopwatch => '秒表';

  @override
  String get compassWaypoint => '指南针航点';

  @override
  String get backtrack => '回溯';

  @override
  String get diveDepth => '潜水与深度';

  @override
  String get flashlight => '手电筒';

  @override
  String get safetySiren => '警笛';

  @override
  String get holdTheActionButtonOnAppleWatchUltraTo =>
      '在 Apple Watch Ultra 上长按操作按钮，可发出 86 分贝的警笛声，最远 180 米外都能听见。';

  @override
  String get holdForSiren => '长按警笛';

  @override
  String version(String value0, String value1) {
    return '$value0 • 版本 $value1';
  }

  @override
  String get showAppOnAppleWatch => '在 Apple Watch 上显示应用';

  @override
  String get displayBrightness => '显示与亮度';

  @override
  String get brightness => '亮度';

  @override
  String get textSize => '文字大小';

  @override
  String get boldText => '粗体文字';

  @override
  String get keepTheWatchFaceAndAppInformationVisibleWhen =>
      '手腕放下时，仍保持显示表盘和应用信息。';

  @override
  String get alwaysOn => '全天候显示';

  @override
  String get wristRaise => '抬腕';

  @override
  String get wakeOnWristRaise => '抬腕唤醒';

  @override
  String get wakeDuration => '唤醒时长';

  @override
  String get message70Seconds => '70 秒';

  @override
  String get message15Seconds => '15 秒';

  @override
  String get screenWakeDurationOnTap => '点按后的亮屏时长';

  @override
  String get emergencySos => '紧急呼救';

  @override
  String get fallDetection => '摔倒检测';

  @override
  String get ifAppleWatchDetectsAHardFallAndYou =>
      '如果 Apple Watch 检测到严重摔倒，而你在大约一分钟内没有反应，它会发出触感提醒和警报声，并联系紧急服务。';

  @override
  String get fallDetection2 => '摔倒检测';

  @override
  String get crashDetection => '车祸检测';

  @override
  String get afterASevereCarCrashAppleWatchShowsThe =>
      '发生严重车祸后，Apple Watch 会显示紧急呼叫滑块，并在 20 秒后自动联系紧急服务。';

  @override
  String get crashDetection2 => '车祸检测';

  @override
  String get medicalInformation => '医疗信息';

  @override
  String get setUpMedicalId => '设置医疗急救卡';

  @override
  String get softwareUpdate => '软件更新';

  @override
  String get orientationTime => '方向与时间';

  @override
  String get leftWrist => '左手佩戴';

  @override
  String get notReceived => '未收到';

  @override
  String get backgroundAppRefresh => '后台 App 刷新';

  @override
  String get on => '开启';

  @override
  String get off => '关闭';

  @override
  String get connectivity => '连接';

  @override
  String get airplaneMode => '飞行模式';

  @override
  String get resetUnpair => '还原与取消配对';

  @override
  String get unpairAppleWatch => '取消配对 Apple Watch';

  @override
  String get allDataWatchFacesAndSettingsWillBeErased => '设备上的所有数据、表盘和设置都将被抹掉。';

  @override
  String get unpair => '取消配对';

  @override
  String get activityHealth => '活动与健康';

  @override
  String get noHealthDataReceivedFromTheWatchYet => '尚未收到手表发来的健康数据。';

  @override
  String get watchMeasurements => '手表测量数据';

  @override
  String get bpm => '次/分';

  @override
  String get steps => '步数';

  @override
  String km(String value0) {
    return '$value0 公里';
  }

  @override
  String get move => '活动';

  @override
  String kcal(String value0, String value1) {
    return '$value0 / $value1 千卡';
  }

  @override
  String get exercise => '锻炼';

  @override
  String min(String value0, String value1) {
    return '$value0 / $value1 分钟';
  }

  @override
  String get stand => '站立';

  @override
  String hr(String value0, String value1) {
    return '$value0 / $value1 小时';
  }

  @override
  String get activityRings => '活动圆环';

  @override
  String get standReminders => '站立提醒';

  @override
  String get remindsYouToStandAndMove10MinutesBefore => '在每小时结束前 10 分钟提醒你起身活动。';

  @override
  String get dailyCoaching => '每日指导';

  @override
  String get goalCompletions => '目标达成';

  @override
  String get heartBreathing => '心脏与呼吸';

  @override
  String get highHeartRateNotifications => '高心率通知';

  @override
  String bpm2(String value0) {
    return '> $value0 次/分';
  }

  @override
  String get lowHeartRateNotifications => '低心率通知';

  @override
  String bpm3(String value0) {
    return '< $value0 次/分';
  }

  @override
  String get bloodOxygen => '血氧';

  @override
  String get sleepTracking => '睡眠追踪';

  @override
  String get allWatches => '所有手表';

  @override
  String get connected => '已连接';

  @override
  String get disconnected => '未连接';

  @override
  String get soundsHaptics => '声音与触感';

  @override
  String get notifications => '通知';

  @override
  String get passcode => '密码';

  @override
  String get installedOnAppleWatch => '已安装在 Apple Watch 上';

  @override
  String get installed => '已安装';

  @override
  String get hidden => '已隐藏';

  @override
  String get manageConnectedWatches => '管理已连接的手表';

  @override
  String get addAppleWatch => '添加 Apple Watch';

  @override
  String get notificationIndicator => '通知指示灯';

  @override
  String get showsARedDotAtTheTopOfThe => '有未读通知时，在表盘顶部显示红点。';

  @override
  String get notificationPrivacy => '通知隐私';

  @override
  String get hideNotificationDetailsUntilYouTapTheScreen => '点按屏幕前隐藏通知详情。';

  @override
  String get notificationSummary => '通知摘要';

  @override
  String get mirrorPhoneAlerts => '镜像手机提醒';

  @override
  String get forwardAlertsFromTheseAppsToAppleWatchWith =>
      '将这些应用的提醒以触感反馈转发到 Apple Watch。';

  @override
  String get messagesSmsRcs => '信息（短信与 RCS）';

  @override
  String get phoneCalls => '电话与通话';

  @override
  String get mail => '邮件';

  @override
  String get calendarReminders => '日历与提醒事项';

  @override
  String get bankingApps => '银行类应用';

  @override
  String get bulletinRelayTest => 'Bulletin 中继测试';

  @override
  String get sendATestNotificationToAppleWatchOverThe =>
      '通过安全的 com.apple.private.alloy.bulletindistributor IDS 通道，向 Apple Watch 发送一条测试通知。';

  @override
  String get sendTestNotification => '发送测试通知';

  @override
  String get simulatedTelegramSmsWithTapticFeedback => '模拟 Telegram / 短信，带触感反馈';

  @override
  String get testMessage => '测试信息';

  @override
  String get androidAppleWatchConnectionIsWorking =>
      'Android ↔ Apple Watch 连接正常！';

  @override
  String get notificationSent => '通知已发送';

  @override
  String get bulletinrequestWasHandedToTheWatchProtocolStack =>
      'BulletinRequest 已交给手表协议栈。';

  @override
  String get changePasscode => '更改密码';

  @override
  String get simplePasscode4Digits => '简单密码（4 位）';

  @override
  String get unlockWithPhone => '用手机解锁';

  @override
  String get unlockingYourPhoneAutomaticallyUnlocksYourTrustedAppleWatch =>
      '佩戴手表时，解锁手机会自动解锁已信任的 Apple Watch。';

  @override
  String get wristDetection => '手腕检测';

  @override
  String get lockAppleWatchWhenYouTakeItOffTo =>
      '取下手表时锁定 Apple Watch，以保护数据和 Apple Pay 卡片。';

  @override
  String get wristDetection2 => '手腕检测';

  @override
  String get dataSecurity => '数据安全';

  @override
  String get eraseAllAppleWatchDataAfter10FailedPasscode =>
      '连续 10 次输错密码后抹掉 Apple Watch 上的所有数据。';

  @override
  String get eraseData10Attempts => '抹掉数据（10 次尝试）';

  @override
  String get automaticUpdates => '自动更新';

  @override
  String get betaUpdates => 'Beta 版更新';

  @override
  String get checkingForUpdates => '正在检查更新…';

  @override
  String get yourAppleWatchSoftwareIsUpToDate => '你的 Apple Watch 软件已是最新版本。';

  @override
  String get checkAgain => '再次检查';

  @override
  String get alertVolume => '提醒音量';

  @override
  String get silentMode => '静音模式';

  @override
  String get hapticFeedback => '触感反馈';

  @override
  String get hapticAlerts => '触感提醒';

  @override
  String get defaultHaptic => '默认';

  @override
  String get prominent => '明显';

  @override
  String get playAnAdditionalHapticBeforeANotification => '通知前额外播放一次触感。';

  @override
  String get systemFeedback => '系统反馈';

  @override
  String get crownHaptics => '表冠触感';

  @override
  String get systemHaptics => '系统触感';

  @override
  String get coverToMute => '遮盖静音';

  @override
  String get coverTheDisplayWithYourPalmForAtLeast =>
      '用手掌盖住屏幕至少 3 秒，即可让来电或提醒静音。';

  @override
  String get watchStorage => '手表存储空间';

  @override
  String get reclaimable => '可回收';

  @override
  String get purgeableData => '可清除的数据';

  @override
  String get watchContent => '手表内容';

  @override
  String get pointTheCamera => '将相机对准手表';

  @override
  String get keepAppleWatchInTheViewfinder => '让 Apple Watch 保持在取景框内';

  @override
  String get placeTheAppleWatchScreenInTheCenterOf =>
      '把 Apple Watch 屏幕放在画面中央，即可自动识别配对动画。';

  @override
  String get pairAppleWatchManually => '手动配对 Apple Watch';

  @override
  String get pairManually => '手动配对';

  @override
  String get enterThe6DigitCode => '输入 6 位代码';

  @override
  String get tapTheIIconOnYourAppleWatchTo => '在 Apple Watch 上点按“i”图标以查看配对码。';

  @override
  String get syncComplete => '同步完成！';

  @override
  String get syncingAppleWatch => '正在同步 Apple Watch';

  @override
  String get setUpAppleWatch => '设置 Apple Watch';

  @override
  String get keepBluetoothOnAndYourWatchCloseToYour => '保持蓝牙开启，并让手表靠近手机。';

  @override
  String get connectAppleWatchToYourAndroidPhoneToSync =>
      '将 Apple Watch 连接到 Android 手机，以同步通知、通话和体能训练。';

  @override
  String get pairAppleWatch => '配对 Apple Watch';

  @override
  String get continueAction => '继续';

  @override
  String get finishSetup => '完成设置';

  @override
  String get whichWristDoYouWearYourWatchOn => '你把手表戴在哪只手上？';

  @override
  String get appleWatchAdjustsWristRaiseAndGestureRecognitionFor =>
      'Apple Watch 会根据所选手腕调整抬腕唤醒和手势识别。';

  @override
  String get digitalCrownPosition => '数码表冠位置';

  @override
  String get chooseWhichSideTheDigitalCrownIsOn => '选择数码表冠在表壳的哪一侧。';

  @override
  String get digitalCrownOnRight => '数码表冠在右侧';

  @override
  String get digitalCrownOnLeft => '数码表冠在左侧';

  @override
  String get alwaysOnDisplay => '全天候显示';

  @override
  String get theAlwaysOnRetinaDisplayShowsTheTimeAnd =>
      '全天候视网膜显示屏无需抬腕，也能显示时间和重要信息。';

  @override
  String get enableAlwaysOnDisplay => '启用全天候显示';

  @override
  String get createAPasscode => '创建密码';

  @override
  String get aPasscodeProtectsYourDataApplePayCardsAnd =>
      '密码可保护你的数据、Apple Pay 卡片和医疗信息。';

  @override
  String get addA4DigitPasscode => '添加 4 位密码';

  @override
  String get titanium => '钛金属';

  @override
  String get sunny => '晴';

  @override
  String get exclusiveAppleWatchUltraFacesWithACustomizableBezel =>
      'Apple Watch Ultra 专属表盘，可自定义表圈，支持夜间模式和 8 项复杂功能。';

  @override
  String get modular => '模块';

  @override
  String get largeInformationBlocksAndGraphsForDataAtA => '大块信息与图表，数据一眼可见。';

  @override
  String get classic => '经典';

  @override
  String get traditionalAnalogFacesWithRomanAndArabicNumerals =>
      '传统指针表盘，可选罗马数字和阿拉伯数字。';

  @override
  String get infographAstronomy => '图文与天文';

  @override
  String get detailedAstronomicalAndWeatherDisplays => '详尽的天文和天气显示。';

  @override
  String get activityNike => '活动与 Nike';

  @override
  String get activityRingsWorkoutsAndSportsMetrics => '活动圆环、体能训练和运动数据。';

  @override
  String get wayfinderAlpine => '寻路者 高山';

  @override
  String get wayfinderNightMode => '寻路者 夜间模式';

  @override
  String get modularDuo => '双行模块';

  @override
  String get modularMulticolor => '多色模块';

  @override
  String get california => '加州';

  @override
  String get californiaOcean => '加州 海洋';

  @override
  String get chronographPro => '计时码表 Pro';

  @override
  String get infograph => '图文';

  @override
  String get solarDial => '太阳刻度盘';

  @override
  String get activityAnalog => '活动指针';

  @override
  String get modularUltra => '模块 Ultra';

  @override
  String get activityDigital => '活动数字';

  @override
  String get selectionConfirmedByTheWatch => '手表已确认该选择。';

  @override
  String get collectionReceivedFromTheWatch => '已从手表收到表盘合集。';

  @override
  String get incompleteCollectionReceivedRefreshAgain => '收到的合集不完整。请再刷新一次。';

  @override
  String get connectionChangedRefreshAgain => '连接已变化。请再刷新一次。';

  @override
  String get waitingForTheWatchFaceCollection => '正在等待表盘合集…';

  @override
  String get requestNotSentCheckTheConnection => '请求未发送。请检查连接。';

  @override
  String get newCollectionNotReceivedYetRefreshAgain => '尚未收到新的合集。请再刷新一次。';

  @override
  String get waitingForTheNewWatchFace => '正在等待新表盘…';

  @override
  String get waitingForTheSelectedWatchFace => '正在等待所选表盘…';

  @override
  String get commandNotSentRefreshTheWatchFaceCollection => '指令未发送。请刷新表盘合集。';

  @override
  String get resultNotConfirmedRefreshTheWatchFaceCollection =>
      '结果未确认。请刷新表盘合集。';

  @override
  String lastSync(String value0) {
    return '上次同步：$value0。';
  }

  @override
  String get myFaces => '我的表盘';

  @override
  String myFacesCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '我的表盘 · $count',
      zero: '我的表盘 · 0',
    );
    return '$_temp0';
  }

  @override
  String get collectionNotReceivedYet => '尚未收到合集';

  @override
  String get partialCollectionReceived => '已收到部分合集';

  @override
  String get collectionIsEmpty => '合集为空';

  @override
  String face(String value0) {
    return '表盘 $value0';
  }

  @override
  String get selectedOnWatch => '已在手表上选中';

  @override
  String get createCopy => '创建副本';

  @override
  String get refreshFromWatch => '从手表刷新';

  @override
  String get enable => '启用';

  @override
  String get disable => '停用';

  @override
  String get commandSentWaitingForTheWatchResult => '指令已发送。正在等待手表返回结果。';

  @override
  String get commandNotSentCheckTheConnection => '指令未发送。请检查连接。';

  @override
  String get watchSettings => '手表设置';

  @override
  String get valuesComeFromWatchMessagesNotReceivedMeansThe =>
      '这些值来自手表发来的消息。“未收到”表示手表尚未上报该项设置。';

  @override
  String get connectYourWatchToChangeSettings => '连接手表后才能更改设置。';

  @override
  String get stopNotConfirmed => '停止操作未确认';

  @override
  String get couldNotOpenFlashlightPermission => '无法打开手电筒权限';

  @override
  String get phoneServiceUnavailable => '手机服务不可用';

  @override
  String get alertStatusIsStale => '提醒状态已过期';

  @override
  String get flashlightIsOn => '手电筒已打开';

  @override
  String get soundAndFlashlightAreOn => '声音和手电筒均已打开';

  @override
  String get soundAlertStarted => '声音提醒已启动';

  @override
  String get lastAlertDidNotStart => '上次提醒未能启动';

  @override
  String get requestHadNoSoundOrFlashlight => '请求中没有声音或手电筒';

  @override
  String get alertStopped => '提醒已停止';

  @override
  String get noAlertIsRunning => '当前没有正在运行的提醒';

  @override
  String get findPhoneFromWatch => '从手表查找手机';

  @override
  String get localApkTest => '本地 APK 测试';

  @override
  String get stopping => '正在停止…';

  @override
  String get stopAlert => '停止提醒';

  @override
  String get cameraPermissionIsRequiredToFindYourPhoneWith =>
      '需要用相机权限才能通过手电筒查找手机。';

  @override
  String get allowFlashlight => '允许使用手电筒';

  @override
  String get incline42 => '坡度 42°';

  @override
  String get north124 => '北 124°';

  @override
  String get message182M => '182 米';

  @override
  String get activity2 => '活动';

  @override
  String get message620800Kcal => '620 / 800 千卡';

  @override
  String get today => '今天';

  @override
  String get message1930Meeting => '19:30 开会';

  @override
  String get watchSync => '手表同步';

  @override
  String get message72Bpm5Minago => '72 次/分 • 5 分钟前';

  @override
  String get theWatchHasNotRepliedYetRefreshAgain => '手表尚未响应。请再刷新一次。';

  @override
  String get dataUpdated => '数据已更新';

  @override
  String get couldNotSendRequest => '无法发送请求';

  @override
  String get noWatchDataYet => '尚无手表数据';

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
  String get chargingUnknown => '充电状态未知';

  @override
  String get charging => '正在充电';

  @override
  String get notCharging => '未充电';

  @override
  String get battery2 => '电池：—';

  @override
  String batteryReading(String value0, String value1, String value2) {
    return '$value0：$value1% · $value2';
  }

  @override
  String get noConnectionToWatch => '未连接到手表';

  @override
  String get waitingForWatchReply => '正在等待手表响应…';

  @override
  String get refreshData => '刷新数据';

  @override
  String get updated => '已更新';

  @override
  String get dataIsStale => '数据已过期';

  @override
  String get battery3 => '电池';

  @override
  String get lastBattery => '上次电量';

  @override
  String get live => '实时';

  @override
  String get alwaysOn2 => '全天候显示';

  @override
  String get bluetooth => '蓝牙';

  @override
  String get wiFi => 'Wi-Fi';

  @override
  String get digitalCrown => '数码表冠';

  @override
  String get appleWatchUltra2ActionButton => 'Apple Watch Ultra 2 操作按钮';

  @override
  String get appleWatch => 'Apple Watch';

  @override
  String get watch => '手表';

  @override
  String get ultra => 'Ultra';

  @override
  String get wayfinder => '寻路者';

  @override
  String get ultraModular => 'Ultra 模块';

  @override
  String get ultraModularTrail => 'Ultra 越野模块';

  @override
  String get nikeBounceVolt => 'Nike Bounce Volt';

  @override
  String get appleMusic => 'Apple Music';

  @override
  String get appleAccount => 'Apple 账户';

  @override
  String get watchConnection => '手表连接';

  @override
  String get savedWatch => '已保存的手表';

  @override
  String get noPairedWatch => '没有已配对的手表';

  @override
  String get noPairedWatchHint => '将手表放在附近并进入配对模式即可开始。';

  @override
  String get bridgeUnavailable => 'Bridge 不可用';

  @override
  String get bridgeUnavailableHint => '请安装对应的 Bridge 后端后再连接手表。';

  @override
  String get identityLoading => '正在读取已保存的配对…';

  @override
  String get identityReadError => '已保存的配对不可用。请先查看诊断信息，再重新配对。';

  @override
  String get connectWatch => '连接';

  @override
  String get disconnectWatch => '断开';

  @override
  String get connectionConnecting => '正在连接…';

  @override
  String get connectionStopping => '正在断开…';

  @override
  String get connectionPermissions => '允许访问蓝牙';

  @override
  String get pairingRunning => '正在设置';

  @override
  String get resumeWatchSetup => '继续设置';

  @override
  String get finishWatchSetup => '完成设置';

  @override
  String get watchStillSettingUp => '我的手表仍显示设置界面';

  @override
  String get resumeWatchSync => '恢复同步';

  @override
  String get resumeWatchSyncHint => '仅在手表仍显示设置界面时使用。如果已经能看到表盘，请改用“完成设置”。';

  @override
  String get confirmWatchFace => '已显示表盘';

  @override
  String get confirmWatchFaceHint => '请等手表显示表盘后再确认。当前的设置连接会被安全断开。';

  @override
  String get confirmWatchFaceQuestion => '手表是否已显示表盘？';

  @override
  String get confirmWatchFaceDetail => '这会将当前已激活的配对保存为正常连接模式。';

  @override
  String get connectionRequestAccepted => '请求已接受。正在等待手表…';

  @override
  String get connectionRequestRejected => '请求未被接受。请检查当前连接和诊断信息。';

  @override
  String get connectionPermissionRequired => '请先允许访问蓝牙，然后重试。';

  @override
  String get connectionBusy => '已有一个连接或设置操作正在进行。';

  @override
  String get syncCurrentWifi => '发送手机 Wi-Fi 网络';

  @override
  String get wifiReceiptHint => '该网络已进入发送队列。请在手表上的“设置”→“Wi-Fi”中确认。';

  @override
  String get watchDiagnostics => '连接诊断';

  @override
  String get bridgeJournal => 'Bridge 日志';

  @override
  String get copyBridgeJournal => '复制日志';

  @override
  String get journalCopied => '日志已复制';

  @override
  String get advancedConnectionTools => '高级连接工具';

  @override
  String get stockBondAudit => '检查系统蓝牙绑定';

  @override
  String get stockBondImport => '导入系统蓝牙绑定';

  @override
  String get stockIdentityAlignment => '对齐系统蓝牙身份';

  @override
  String get stockReconnectProbe => '测试系统重连';

  @override
  String get advancedToolHint => '使用这些工具前，需要先断开连接并关闭系统蓝牙。';

  @override
  String get advancedToolQuestion => '要运行此连接工具吗？';

  @override
  String get advancedToolDetail => '运行前，后端会校验已保存的配对和蓝牙状态。';

  @override
  String get pairingDiscovering => '正在查找手表…';

  @override
  String get pairingConnecting => '正在连接发现的手表…';

  @override
  String get pairingSecurity => '正在建立安全配对…';

  @override
  String get pairingActivation => '正在向 Apple 激活…';

  @override
  String get pairingIds => '正在连接手表服务…';

  @override
  String get pairingRegistry => '正在读取手表配置…';

  @override
  String get pairingConfiguring => '正在应用手表设置…';

  @override
  String get pairingActivated => '手表已激活';

  @override
  String get pairingCheckWatch => '已发送初始同步。请查看手表。';

  @override
  String get pairingVerifyReconnect => '已检测到手表完成设置。连接验证尚未完成。';

  @override
  String get pairingPaused => '设置连接已停止';

  @override
  String get watchActivatedHint => '激活已完成。等手表显示表盘后，在下方确认。';

  @override
  String get confirmWatchFaceActiveDetail => '这会安全断开设置会话，并将此已激活的配对保存为正常连接模式。';

  @override
  String get pairingSync => '正在同步手表设置…';

  @override
  String get pairingVerified => '手表设置完成';

  @override
  String get pairingFailed => '设置会话因错误结束。请查看日志。';

  @override
  String get pairingIdle => '当前没有正在运行的设置会话。';

  @override
  String get pairingStarting => '正在启动手表会话…';

  @override
  String get pairingPinHint => '请输入手表上显示的六位代码。';

  @override
  String get pairingCheckingCode => '正在校验手表代码…';

  @override
  String get pairingCodeVerifiedBondPending => '手表代码已验证。当前版本暂不支持蓝牙绑定。';

  @override
  String get macosPairingBootstrapRestricted =>
      'macOS 限制了手表配对所需的蓝牙通道。详情请见诊断信息。';

  @override
  String get windowsPairingBootstrapRestricted =>
      '蓝牙发现和连接可用。但当前版本中，Windows 未开放 Apple Watch 配对所需的通道。';

  @override
  String get windowsRawHciRequired =>
      '要完整配对 Watch，需要随附的协议运行时和已签名的 UsbDk 1.0.21。1.0.22 版已被禁用，因为它可能导致 Windows 崩溃。请选择一个 USB 蓝牙适配器；会话期间该适配器将专供手表使用。';

  @override
  String get sendWatchPin => '发送代码';

  @override
  String get pairingCodeOnlyHint => '在这台电脑上，请使用手表上显示的六位代码完成配对。';

  @override
  String get findNearbyWatch => '查找手表';

  @override
  String get bluetoothLinkConnected => '蓝牙链路已连接';

  @override
  String get watchPairingUnavailable => '此平台暂不支持手表配对服务。';

  @override
  String get activationOwnerInput => 'Apple 要求机主确认';

  @override
  String get activationCredentialsHint => '凭据仅发送到 Apple 的激活服务，Companion 不会保存。';

  @override
  String get submitActivation => '继续激活';

  @override
  String get retryActivation => '重试激活';

  @override
  String get stopSetup => '停止设置';

  @override
  String get pairedIdentity => '配对 ID';

  @override
  String get hardwareIdentity => '硬件标识符';

  @override
  String get systemBuild => '系统构建版本';

  @override
  String get pendingWatchSetup => '这个已保存的配对尚未完成设置。';

  @override
  String get oneWatchBackend => '后端目前只保存一个配对。断开连接时，已保存的配对会保留。';

  @override
  String get finishInitialSync => '完成初始同步';

  @override
  String get chooseDiscoveredWatch => '选择附近找到的手表。列表中只显示受支持的设置广播。';

  @override
  String get desktopCoreUnavailable => '连接服务无法启动。详情请见诊断信息。';

  @override
  String get manageWatchFaces => '管理表盘';

  @override
  String get nativeFaceChangesConfirmed => '手表已确认更改。';

  @override
  String get nativeFaceWaiting => '正在等待手表确认更改…';

  @override
  String get nativeFaceSaving => '正在保存更改…';

  @override
  String get nativeFaceRetry => '重试';

  @override
  String get nativeFaceEditPending => '更改已保存在本机。正在等待手表…';

  @override
  String get nativeFaceSaveFailed => '无法在本机保存更改。请保持编辑器打开并重试。';

  @override
  String get nativeFaceEditUncertain => '更改已保存在本机，但手表尚未确认。可重试以再次发送。';

  @override
  String get nativeFaceRejected => '更改未发送。请刷新合集后重试。';

  @override
  String get nativeFaceUnknown => '结果未确认。请先刷新，再进行其他修改。';

  @override
  String get nativeFaceImportHint =>
      '请选择原生 .watchface 包，其中可包含照片和资源（最大 16 MiB）。兼容性由已连接的手表确认。';

  @override
  String get nativeFaceCustomization => '自定';

  @override
  String get nativeFaceComplications => '复杂功能';

  @override
  String get nativeFaceSetActive => '设为当前表盘';

  @override
  String get nativeFaceDelete => '删除表盘';

  @override
  String get nativeFaceDeleteQuestion => '要删除这个表盘吗？';

  @override
  String get nativeFaceDeleteDetail => '其余表盘会保留在手表上。最后一个表盘无法删除。';

  @override
  String get nativeFaceMoveEarlier => '前移';

  @override
  String get nativeFaceMoveLater => '后移';

  @override
  String get nativeFaceConfiguration => '原生配置';

  @override
  String get nativeFaceConfigurationHint =>
      '这些值来自手表。请不要改动未知字段。可选值和复杂功能支持取决于表盘与 watchOS。';

  @override
  String get nativeFaceApply => '应用到手表';

  @override
  String get nativeFaceEditValue => '编辑值';

  @override
  String get nativeFaceValueHint => '请输入 JSON 值。字符串需加引号，数字和布尔值遵循 JSON 写法。';

  @override
  String get nativeFaceInvalidValue => '配置值无效';

  @override
  String get nativeFaceConfigurationUnavailable =>
      '请更新 Bridge 并刷新合集，以载入此表盘的配置。';

  @override
  String get nativeFaceFileSaved => '表盘文件已保存';

  @override
  String get nativeFaceFileError => '无法打开或保存表盘文件';

  @override
  String get nativeFaceResourceExportUnavailable =>
      '此表盘可能需要照片或其他资源。在完整读取原生包之前，无法导出。';

  @override
  String get nativeFaceLocalLayoutHint =>
      '此图库只包含本地预览布局。原生表盘请在“我的手表”→“管理表盘”中管理。';

  @override
  String get nativeFaceComplicationHint =>
      '可从手表目录或现有表盘中选择兼容的复杂功能。已保存的设置和原生 intent 会被保留。';

  @override
  String get nativeFaceComplicationCatalogPartial => '部分描述符不可用。请刷新合集以更新可选项。';

  @override
  String get nativeFaceComplicationVariantsMissing =>
      '这些表盘上的部分复杂功能尚未提供可选变体，但仍可使用当前设置。';

  @override
  String get nativeFaceNoComplication => '无';

  @override
  String get nativeFaceUnknownComplication => '未知复杂功能';

  @override
  String get nativeFaceConflict => '此表盘已更改，或手表重新连接过。请重新打开表盘后再应用修改。';

  @override
  String get exportLocalFaceLayout => '导出本地布局';

  @override
  String get localFaceLayoutSaved => '本地布局已保存';

  @override
  String get localFaceLayoutSavedHint =>
      '这个 .watchlayout 文件包含 Companion 的预览布局，不是可安装的原生表盘。';

  @override
  String get nativeFaceTimeStyle => '时间样式';

  @override
  String get nativeFaceDialStyle => '表盘样式';

  @override
  String get nativeFaceWaypoint => '航点';

  @override
  String get nativeFacePink => '粉色';

  @override
  String get nativeFaceNeonGreen => '霓虹绿';

  @override
  String get nativeFaceOrange => '橙色';

  @override
  String get nativeFacePinkSand => '粉 / 沙色';

  @override
  String get nativeFaceNeonGreenCloud => '霓虹绿 / 云白';

  @override
  String get nativeFaceOrangeLightSage => '橙色 / 浅鼠尾草绿';

  @override
  String get nativeFaceLightBlue => '浅蓝色';

  @override
  String get nativeFaceBrightBlue => '亮蓝色';

  @override
  String get nativeFaceTerraCotta => '陶土色';

  @override
  String get nativeFaceTopLeft => '左上';

  @override
  String get nativeFaceTop => '上';

  @override
  String get nativeFaceBottom => '下';

  @override
  String get nativeFaceTopRight => '右上';

  @override
  String get nativeFaceBottomLeft => '左下';

  @override
  String get nativeFaceBottomRight => '右下';

  @override
  String get nativeFaceCenter => '中间';

  @override
  String get nativeFaceNightMode => '夜间模式';

  @override
  String get nativeFaceColor => '颜色';

  @override
  String get nativeFaceAutomatic => '自动';

  @override
  String get nativeFaceLeft => '左';

  @override
  String get nativeFaceRight => '右';

  @override
  String nativeFacePalette(String number) {
    return '配色 $number';
  }

  @override
  String nativeFaceSeasonalPalette(String number) {
    return '季节配色 $number';
  }

  @override
  String get nativeFaceAdvanced => '高级';

  @override
  String get nativeFaceEditHint => '选好设置后，将其应用到手表。';

  @override
  String get nativeFaceCopyHint => '基于手表上的表盘创建新表盘，或导入分享的 .watchface 文件。';

  @override
  String get nativeFaceLocalLibrary => '本地设计库';

  @override
  String get nativeFaceUnsaved => '放弃未保存的更改？';

  @override
  String get nativeFaceDiscard => '放弃';

  @override
  String get nativeFaceImport => '导入表盘';

  @override
  String get nativeFaceSharedPreview => '分享的预览图';

  @override
  String get nativeFaceImportDone => '完成';

  @override
  String get nativeFaceSharedPreviewUnavailable => '此文件没有可用的分享预览图。';

  @override
  String get nativeFaceSharedPreviewHint => '预览图由文件提供。兼容性和能否成功安装由手表确认。';

  @override
  String get nativeFaceDraft => '未保存的更改';

  @override
  String get nativeFaceDigital => '数字';

  @override
  String get nativeFaceAnalog => '指针';

  @override
  String get nativeFaceAdd => '添加表盘';

  @override
  String get nativeFaceAvailable => '可用表盘';

  @override
  String get nativeFaceAddToWatch => '添加到手表';

  @override
  String get nativeFaceTemplateHint => '将表盘添加到手表前，请先选好颜色和复杂功能。';

  @override
  String get nativeFaceDefaultPreview => '默认预览';

  @override
  String get nativeFacePreviewUnavailable => '当前设置无法预览';

  @override
  String get nativeFaceStylePreview => '样式预览';

  @override
  String get nativeFaceStylePreviewHint => '示例时间。实时数据和复杂功能会显示在手表上。';

  @override
  String get nativeFaceSearch => '搜索';

  @override
  String get nativePhotoChoose => '选择照片';

  @override
  String get nativePhotoCropHint => '拖动可调整照片构图。双指捏合或使用滑块缩放。';

  @override
  String nativeIntentSettings(String slot) {
    return '$slot 的设置';
  }

  @override
  String nativeIntentParameterSeconds(String name) {
    return '$name（秒）';
  }

  @override
  String nativeIntentDurationValue(String name, String seconds) {
    return '$name：$seconds 秒';
  }

  @override
  String get nativeIntentParameterInvalid => '请输入受支持范围内的有限时长。';

  @override
  String get nativeIntentLocalDraft => '这些设置已保存到草稿。应用表盘后才会发送到手表。';

  @override
  String get nativePhotoZoom => '缩放';

  @override
  String get nativePhotoAspect => '取景框形状';

  @override
  String get nativePhotoKeepCrop => '保留裁剪';

  @override
  String nativePhotoSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '已选择 $count 张照片',
      one: '已选择 1 张照片',
    );
    return '$_temp0';
  }

  @override
  String get nativePhotoTimeAlignment => '时间对齐';

  @override
  String get nativePhotoLeading => '靠左';

  @override
  String get nativePhotoTrailing => '靠右';

  @override
  String get nativePhotoTimeSize => '时间字号';

  @override
  String get nativePhotoSmall => '小';

  @override
  String get nativePhotoMedium => '中';

  @override
  String get nativePhotoLarge => '大';

  @override
  String get nativePhotoXLarge => '特大';

  @override
  String get nativePhotoRemove => '移除照片';

  @override
  String get nativeFaceShade => '深浅';

  @override
  String get nativeFaceAddColors => '添加颜色';

  @override
  String get nativeFaceColorsDone => '完成';

  @override
  String get nativeFaceColorsSaveError => '无法保存颜色。请重试。';

  @override
  String get nativeFaceColorsPending => '正在等待 Apple Watch 发来颜色设置。';

  @override
  String get nativeFaceColorsAwaiting => '正在把颜色发送到 Apple Watch。';

  @override
  String get nativeFaceColorsUncertain => 'Apple Watch 上报的颜色与所选不一致。';

  @override
  String get nativeFaceColorsSent => '颜色已发送到 Apple Watch。';

  @override
  String get nativeFaceColorsDeliveryUncertain => '无法确认颜色是否送达。';

  @override
  String get nativeFaceColorsRetry => '重试颜色同步';

  @override
  String get nativeFaceMonogram => '字母组合';

  @override
  String get nativeMonogramDone => '完成';

  @override
  String get nativeMonogramInvalid => '这段文字不能用作字母组合。';

  @override
  String get nativeMonogramUnavailable => '连接 Apple Watch 后才能编辑其字母组合。';

  @override
  String get nativeMonogramSaveError => '无法保存字母组合。请重试。';

  @override
  String get nativeMonogramAwaiting => '正在把字母组合发送到 Apple Watch。';

  @override
  String get nativeMonogramDelivered => '字母组合已送达，正在等待 Apple Watch 确认。';

  @override
  String get nativeMonogramUncertain => '无法确认字母组合是否同步。';

  @override
  String get nativeMonogramConfirmed => 'Apple Watch 已确认字母组合。';

  @override
  String get nativeFaceSeeAllWatchFaces => '查看所有表盘';

  @override
  String get nativeFaceAllWatchFaces => '所有表盘';

  @override
  String get nativeFaceLibraryEdit => '编辑';

  @override
  String get nativeFaceLibraryDone => '完成';

  @override
  String nativeFaceLibraryReorder(String face) {
    return '调整 $face 的顺序';
  }
}
