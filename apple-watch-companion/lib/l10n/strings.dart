import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'generated/app_localizations.dart';
import 'generated/app_localizations_en.dart';

/// Shared UI/presentation strings; English is available before widget startup.
abstract final class Strings {
  static AppLocalizations _current = AppLocalizationsEn();
  static AppLocalizations get current => _current;
  static const delegate = _StringsDelegate();
  static List<Locale> get supportedLocales => AppLocalizations.supportedLocales;
}

class _StringsDelegate extends LocalizationsDelegate<AppLocalizations> {
  const _StringsDelegate();

  @override
  bool isSupported(Locale locale) =>
      AppLocalizations.delegate.isSupported(locale);

  @override
  Future<AppLocalizations> load(Locale locale) {
    final strings = lookupAppLocalizations(locale);
    Strings._current = strings;
    return SynchronousFuture(strings);
  }

  @override
  bool shouldReload(_StringsDelegate old) => false;
}
