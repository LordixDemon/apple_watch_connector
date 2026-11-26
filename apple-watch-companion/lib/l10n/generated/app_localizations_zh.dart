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
  String get activity => '活动';

  @override
  String get heartRate => '心率';

  @override
  String get music => '音乐';

  @override
  String get messages => '信息';

  @override
  String get phone => '电话';

  @override
  String get weather => '天气';

  @override
  String get maps => '地图';

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
  String get notificationMirroring => '通知镜像';

  @override
  String get setUpSmsCallsAndMessagingApps => '设置短信、通话和各类即时通讯应用';

  @override
  String get resources => '资源';

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
  String get nightMode => '夜间模式';

  @override
  String get bezelStyle => '表圈样式';

  @override
  String get seconds => '秒';

  @override
  String get exportWatchfacePackage => '导出 .watchface 包';

  @override
  String get cancel => '取消';

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
  String get action => '操作';

  @override
  String get stopwatch => '秒表';

  @override
  String get backtrack => '回溯';

  @override
  String get flashlight => '手电筒';

  @override
  String get holdForSiren => '长按警笛';

  @override
  String version(String value0, String value1) {
    return '$value0 • 版本 $value1';
  }

  @override
  String get brightness => '亮度';

  @override
  String get textSize => '文字大小';

  @override
  String get boldText => '粗体文字';

  @override
  String get wakeOnWristRaise => '抬腕唤醒';

  @override
  String get wakeDuration => '唤醒时长';

  @override
  String get fallDetection => '摔倒检测';

  @override
  String get crashDetection => '车祸检测';

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
  String get airplaneMode => '飞行模式';

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
  String get notifications => '通知';

  @override
  String get installedOnAppleWatch => '已安装在 Apple Watch 上';

  @override
  String get installed => '已安装';

  @override
  String get hidden => '已隐藏';

  @override
  String get notificationIndicator => '通知指示灯';

  @override
  String get notificationPrivacy => '通知隐私';

  @override
  String get mirrorPhoneAlerts => '镜像手机提醒';

  @override
  String get mail => '邮件';

  @override
  String get sendTestNotification => '发送测试通知';

  @override
  String get testMessage => '测试信息';

  @override
  String get unlockWithPhone => '用手机解锁';

  @override
  String get wristDetection => '手腕检测';

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
  String get hapticAlerts => '触感提醒';

  @override
  String get prominent => '明显';

  @override
  String get crownHaptics => '表冠触感';

  @override
  String get systemHaptics => '系统触感';

  @override
  String get coverToMute => '遮盖静音';

  @override
  String get watchStorage => '手表存储空间';

  @override
  String get reclaimable => '可回收';

  @override
  String get purgeableData => '可清除的数据';

  @override
  String get watchContent => '手表内容';

  @override
  String get pairAppleWatch => '配对 Apple Watch';

  @override
  String get alwaysOnDisplay => '全天候显示';

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
  String get collectionReceivedFromTheWatch => '已从手表收到表盘合集。';

  @override
  String get connectionChangedRefreshAgain => '连接已变化。请再刷新一次。';

  @override
  String get waitingForTheWatchFaceCollection => '正在等待表盘合集…';

  @override
  String get requestNotSentCheckTheConnection => '请求未发送。请检查连接。';

  @override
  String get newCollectionNotReceivedYetRefreshAgain => '尚未收到新的合集。请再刷新一次。';

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
  String get bluetooth => '蓝牙';

  @override
  String get wiFi => 'Wi-Fi';

  @override
  String get digitalCrown => '数码表冠';

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
  String get nativeFaceApply => '应用到手表';

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

  @override
  String get notificationForwarding => '转发手机通知';

  @override
  String get notificationForwardingDescription =>
      '将 Android 应用的通知转发到你的 Apple Watch。重新连接后会静默恢复现有通知。';

  @override
  String get notificationBridgeUnavailable => '请连接到更新版 Android Bridge 以配置转发。';

  @override
  String get notificationAccessRequired => '请在手机上允许通知使用权。';

  @override
  String get notificationForwardingPaused => '通知转发已暂停。';

  @override
  String get notificationListenerWaiting => '正在等待 Android 通知使用权连接。';

  @override
  String get notificationWatchWaiting => '正在等待 Apple Watch 连接。';

  @override
  String get notificationForwardingReady => '已准备好转发手机通知。';

  @override
  String get notificationAllowAccess => '允许通知使用权';

  @override
  String get notificationManageAccess => '管理通知使用权';

  @override
  String get notificationAccessDescription =>
      '请在 Android 设置中启用 Apple Watch Notification Mirror。';

  @override
  String get notificationAccessFailed => '无法打开通知使用权设置。';

  @override
  String get notificationSettingsFailed => '无法保存通知设置，请重试。';

  @override
  String get notificationAppDiscovery => '应用发送通知后会显示在此处。默认开启转发；可关闭不想在手表上显示的应用。';

  @override
  String get notificationNoApps => '暂未检测到应用';

  @override
  String notificationPending(int count) {
    return '$count 条通知或移除操作正在等待发送。';
  }

  @override
  String get notificationTestDescription => '转发就绪时发送测试提醒。';

  @override
  String get notificationTestBody => '这是一条来自 Watch Companion 的测试通知。';

  @override
  String get notificationQueued => '通知已加入队列';

  @override
  String get notificationQueuedDetails =>
      '测试提醒已加入 Apple Watch 发送队列。请查看手表以确认显示。';

  @override
  String get notificationTestFailed => '通知未能加入队列';

  @override
  String get notificationTestFailedDetails => '无法将测试提醒加入队列。请检查手表连接后重试。';

  @override
  String get notificationOnlyWhenLocked => '仅在手机锁屏时';

  @override
  String get notificationOnlyWhenLockedDescription =>
      '手机解锁且处于活动状态时，不在手表上推送通知提醒。';

  @override
  String get notificationSearchApps => '搜索应用';

  @override
  String get notificationFilterAll => '全部';

  @override
  String get notificationFilterActive => '活跃';

  @override
  String get notificationRetryPending => '重试待发送队列';

  @override
  String get notificationTestAcknowledged =>
      'Apple Watch 已通过 IDS 确认接收。请查看手表屏幕以确认显示。';

  @override
  String get notificationTestDeliveryFailed =>
      '测试提醒未能及时送达 Apple Watch。请检查手表连接后重试。';

  @override
  String get updateUnavailable => '当前连接不支持软件更新。';

  @override
  String get updateConnect => '请连接你的 Apple Watch 以检查更新。';

  @override
  String get updateUnknown => '正在等待手表的更新状态。';

  @override
  String get updateIdle => '当前未选择任何更新。请点击检查更新以查询 Apple 目录。';

  @override
  String get updateAvailable => '有可用更新';

  @override
  String get updatePreparing => '正在 Apple Watch 上下载并准备';

  @override
  String get updateWaitingPreparation => '正在等待准备条件';

  @override
  String get updatePrepared => '准备安装';

  @override
  String get updateWaitingInstallation => '正在等待安装条件';

  @override
  String get updateInstalling => '正在 Apple Watch 上安装';

  @override
  String get updateRestart => '正在等待手表重新启动并确认新版本';

  @override
  String get updateComplete => '手表已确认所安装的版本。';

  @override
  String get updateFailed => '手表报告更新出错。继续前请先刷新状态。';

  @override
  String get updateStalled => '手表报告进度已停滞。';

  @override
  String updateStageProgress(String percent) {
    return '当前阶段进度 $percent%';
  }

  @override
  String get updateRequirements =>
      '请确保手表放在充电器上、电量至少为 50% 并已连接 Wi-Fi，同时将 Companion 放在附近。下载、验证和安装均由手表完成。';

  @override
  String get updateTerms => '请在手表上前往“设置”→“通用”→“软件更新”查看并同意更新协议，然后在此刷新。';

  @override
  String get updateDownload => '下载并准备';

  @override
  String get updateInstall => '安装更新';

  @override
  String get updateRefresh => '刷新状态';

  @override
  String updateConfirm(String version, String build) {
    return '要安装 watchOS $version（$build）吗？手表将会重新启动。在安装完成前，请保持手表放在充电器上。';
  }

  @override
  String get updateQueued => '请求已加入队列，正在等待手表确认。';

  @override
  String get updateNotQueued => '请求未能加入队列。请刷新手表状态后重试。';

  @override
  String get updateUnconfirmed => '送达尚未确认。发送其他命令前请先刷新状态。';

  @override
  String get updateInstallUnconfirmed => '安装请求已提交。请刷新状态或在手表上完成安装。系统不会自动重发该请求。';

  @override
  String updateDownloadSize(String size) {
    return '下载大小：$size';
  }

  @override
  String updateRequiredSpace(String size) {
    return '所需可用空间：$size';
  }

  @override
  String get opticalCameraPermissionDenied => '需要相机权限才能扫描 Apple Watch。';

  @override
  String get opticalNoCamera => '此设备上没有可用相机。';

  @override
  String get filterJournal => '筛选日志';

  @override
  String get jumpToBottom => '底部';

  @override
  String get copiedToClipboard => '已复制';

  @override
  String get noMatchingJournalLines => '没有匹配的日志行';

  @override
  String get watchConnectionSubtitle => '配对、重连以及查看 Bridge 连接状态';

  @override
  String get includeLegacyWatches => '包含旧款 Apple Watch (watchOS 1–9)';

  @override
  String get includeLegacyWatchesSubtitle => '实验性支持旧款手表的配对策略 1–3。';

  @override
  String legacyStrategyBadge(String strategy) {
    return '旧版 $strategy';
  }
}
