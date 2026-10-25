import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/l10n/generated/app_localizations.dart';

void main() {
  test('Chinese catalog resolves and covers the English messages', () {
    final zh = lookupAppLocalizations(const Locale('zh'));
    expect(zh.localeName, 'zh');
    expect(zh.myWatch, '我的手表');
    expect(zh.faceGallery, '表盘图库');
    expect(zh.watchSettings, '手表设置');
    expect(zh.nativeFaceComplications, '复杂功能');
    expect(zh.digitalCrown, '数码表冠');
  });

  test('Chinese placeholders and plurals keep the English structure', () {
    final zh = lookupAppLocalizations(const Locale('zh'));
    expect(zh.opticalAnalyzedFrames(12), contains('12'));
    expect(zh.chooseAComplicationFor('顶部'), contains('顶部'));
    expect(zh.layoutSavedOnYourPhone('布局 A'), contains('布局 A'));

    expect(zh.myFacesCount(0), '我的表盘 · 0');
    expect(zh.myFacesCount(5), '我的表盘 · 5');
    expect(zh.nativePhotoSelected(1), '已选择 1 张照片');
    expect(zh.nativePhotoSelected(4), '已选择 4 张照片');
  });

  test('Unsupported locales still fall back to English', () {
    expect(AppLocalizations.supportedLocales, contains(const Locale('zh')));
    expect(AppLocalizations.supportedLocales, contains(const Locale('en')));
    final en = lookupAppLocalizations(const Locale('en'));
    expect(en.myWatch, 'My Watch');
  });
}
