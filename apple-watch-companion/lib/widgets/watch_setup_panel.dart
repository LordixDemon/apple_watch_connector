import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import '../l10n/strings.dart';
import '../models/watch_connection_state.dart';
import '../providers/watch_connection_provider.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';
import 'setup_progress_label.dart';

/// Forms remain local to this widget. Sensitive inputs are cleared after each request.
class WatchSetupPanel extends StatefulWidget {
  final WatchConnectionProvider connection;
  const WatchSetupPanel({super.key, required this.connection});
  @override
  State<WatchSetupPanel> createState() => _WatchSetupPanelState();
}

class _WatchSetupPanelState extends State<WatchSetupPanel> {
  final _pin = TextEditingController();
  final Map<String, TextEditingController> _credentials = {};
  int? _challenge;
  @override
  void didUpdateWidget(covariant WatchSetupPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncChallenge();
  }

  void _syncChallenge() {
    final id = widget.connection.state.challengeId;
    if (_challenge == id) return;
    for (final field in _credentials.values) {
      field.clear();
      field.dispose();
    }
    _credentials.clear();
    _challenge = id;
    for (final field in widget.connection.state.challengeFields) {
      _credentials[field] = TextEditingController();
    }
  }

  @override
  void initState() {
    super.initState();
    _syncChallenge();
  }

  @override
  void dispose() {
    _pin.clear();
    _pin.dispose();
    for (final field in _credentials.values) {
      field.clear();
      field.dispose();
    }
    super.dispose();
  }

  Future<void> _submitPin() async {
    final pin = _pin.text;
    _pin.clear();
    await widget.connection.submitPin(pin);
  }

  Future<void> _respond(String action) async {
    final values = {
      for (final field in _credentials.entries) field.key: field.value.text,
    };
    for (final field in _credentials.values) {
      field.clear();
    }
    await widget.connection.respond(action, values);
    values.clear();
  }

  @override
  Widget build(BuildContext context) {
    final provider = widget.connection,
        state = provider.state,
        s = Strings.current;
    return Container(
      padding: const EdgeInsets.all(CompanionSpacing.cardInset),
      decoration: BoxDecoration(
        color: IosColors.secondaryGroupedBackground,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              if (state.setupWorking)
                const Padding(
                  padding: EdgeInsets.only(right: 12),
                  child: CupertinoActivityIndicator(),
                ),
              Expanded(
                child: Text(
                  setupProgressLabel(state),
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: IosColors.label,
                  ),
                ),
              ),
            ],
          ),
          if (state.discoveredProductType.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(
                '${state.discoveredProductType} • watchOS ${state.discoveredWatchOs}',
                style: const TextStyle(
                  fontSize: 13,
                  color: IosColors.secondaryLabel,
                ),
              ),
            ),
          if (state.setupPhase == 'DISCOVERING') ...[
            const SizedBox(height: 12),
            Text(
              s.chooseDiscoveredWatch,
              style: const TextStyle(
                color: IosColors.secondaryLabel,
                fontSize: 13,
              ),
            ),
            for (final watch in state.discoveredWatches)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: CupertinoButton.filled(
                  onPressed: provider.sending
                      ? null
                      : () => provider.selectWatch(watch.token),
                  child: Column(
                    children: [
                      Text(
                        WatchConnectionState.nameForProduct(watch.productType),
                      ),
                      Text(
                        '${watch.productType} • watchOS ${watch.watchOs} • ${watch.rssi} dBm',
                        style: const TextStyle(fontSize: 11),
                      ),
                      Text(
                        watch.token.substring(0, 8),
                        style: const TextStyle(fontSize: 11),
                      ),
                    ],
                  ),
                ),
              ),
          ],
          if (state.pinRequired) ...[
            const SizedBox(height: 16),
            CupertinoTextField(
              controller: _pin,
              placeholder: '••••••',
              obscureText: true,
              keyboardType: TextInputType.number,
              maxLength: 6,
              autofillHints: const [],
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              padding: const EdgeInsets.all(16),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) {
                if (_pin.text.length == 6 && !provider.sending) _submitPin();
              },
            ),
            const SizedBox(height: 12),
            CupertinoButton.filled(
              onPressed: _pin.text.length == 6 && !provider.sending
                  ? _submitPin
                  : null,
              child: Text(s.sendWatchPin),
            ),
          ],
          if (state.challengeId != null) ...[
            const SizedBox(height: 14),
            Text(
              state.challengeMessage,
              style: const TextStyle(
                color: IosColors.secondaryLabel,
                fontSize: 14,
              ),
            ),
            if (_credentials.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(
                s.activationCredentialsHint,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 12,
                ),
              ),
              for (final field in _credentials.entries)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: CupertinoTextField(
                    controller: field.value,
                    placeholder: field.key == 'login'
                        ? s.appleAccount
                        : field.key,
                    obscureText: field.key != 'login',
                    autocorrect: false,
                    enableSuggestions: false,
                    autofillHints: const [],
                    onChanged: (_) => setState(() {}),
                    padding: const EdgeInsets.all(14),
                  ),
                ),
            ],
            const SizedBox(height: 12),
            CupertinoButton.filled(
              onPressed:
                  provider.sending ||
                      _credentials.values.any((field) => field.text.isEmpty)
                  ? null
                  : () => _respond(_credentials.isEmpty ? 'retry' : 'submit'),
              child: Text(
                _credentials.isEmpty ? s.retryActivation : s.submitActivation,
              ),
            ),
            CupertinoButton(
              onPressed: provider.sending ? null : () => _respond('cancel'),
              child: Text(s.cancel),
            ),
          ],
        ],
      ),
    );
  }
}
