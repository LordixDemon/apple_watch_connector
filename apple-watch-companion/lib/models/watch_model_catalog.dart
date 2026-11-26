import '../l10n/strings.dart';

/// Display metadata only. Catalog membership never controls pairing or features.
final class WatchModelCatalog {
  static const _models = <String, ({String name, String caseSize})>{
    'Watch6,18': (name: 'Apple Watch Ultra', caseSize: '49 mm'),
    'Watch7,5': (name: 'Apple Watch Ultra 2', caseSize: '49 mm'),
  };

  static String nameForProduct(String product) =>
      _models[product]?.name ??
      (product.isEmpty
          ? Strings.current.appleWatch
          : (product.startsWith('Apple Watch') ||
                    product.startsWith(Strings.current.appleWatch)
                ? product
                : '${Strings.current.appleWatch} ($product)'));

  static String caseSizeForProduct(String product) =>
      _models[product]?.caseSize ?? '—';
}
