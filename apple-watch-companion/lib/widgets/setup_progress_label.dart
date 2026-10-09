import '../l10n/strings.dart';
import '../models/watch_connection_state.dart';

/// Both setup surfaces describe the same native observation.
String setupProgressLabel(WatchConnectionState state) {
  final s = Strings.current;
  return switch (state.effectiveSetupPhase) {
    'STARTING' => s.pairingStarting,
    'DISCOVERING' => s.pairingDiscovering,
    'CONNECTING' => s.pairingConnecting,
    'PIN_REQUIRED' => s.pairingPinHint,
    'CHECKING_CODE' => s.pairingCheckingCode,
    'BOND_REQUIRED' => s.pairingCodeVerifiedBondPending,
    'SECURITY' => s.pairingSecurity,
    'IDS' => s.pairingIds,
    'REGISTRY' => s.pairingRegistry,
    'CONFIGURING' => s.pairingConfiguring,
    'ACTIVATING' => s.pairingActivation,
    'ACTIVATION_INPUT' => s.activationOwnerInput,
    'ACTIVATED' => s.pairingActivated,
    'SYNCING' => s.pairingSync,
    'WAITING_FOR_WATCH' => s.pairingCheckWatch,
    'VERIFYING_RECONNECT' => s.pairingVerifyReconnect,
    'VERIFIED' => s.pairingVerified,
    'FAILED' => s.pairingFailed,
    'STOPPING' => s.connectionStopping,
    'STOPPED' => s.pairingPaused,
    'BLUETOOTH_LINK' => s.bluetoothLinkConnected,
    _ => s.pairingIdle,
  };
}
