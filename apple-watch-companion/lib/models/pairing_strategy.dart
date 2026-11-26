import 'package:flutter/foundation.dart';

/// Metadata for an Apple Watch pairing strategy (Strategies 1–4).
@immutable
final class PairingStrategyInfo {
  final int id;
  final String slug;
  final String maturity;
  final String watchOs;

  const PairingStrategyInfo({
    required this.id,
    required this.slug,
    required this.maturity,
    required this.watchOs,
  });

  static const List<int> productionIds = [4];
  static const List<int> allIds = [4, 3, 2, 1];

  static const List<PairingStrategyInfo> defaults = [
    PairingStrategyInfo(
      id: 4,
      slug: 'network_relay',
      maturity: 'production',
      watchOs: 'watchOS 9+',
    ),
    PairingStrategyInfo(
      id: 3,
      slug: 'direct_ipsec_legacy',
      maturity: 'experimental',
      watchOs: 'watchOS 6-9',
    ),
    PairingStrategyInfo(
      id: 2,
      slug: 'sequential_legacy',
      maturity: 'experimental',
      watchOs: 'watchOS 3-5',
    ),
    PairingStrategyInfo(
      id: 1,
      slug: 'simultaneous_legacy',
      maturity: 'experimental',
      watchOs: 'watchOS 1-3',
    ),
  ];

  bool get isExperimental => maturity.toLowerCase() == 'experimental';
  bool get experimental => isExperimental;
  bool get defaultEnabled => !isExperimental && id == 4;
  String get watchOsRange => watchOs;
  String get shortBadge => badgeFor(id);
  String get badgeLabel => isExperimental ? 'Legacy $shortBadge' : shortBadge;

  static String badgeFor(int strategyId) => 'S$strategyId';

  static List<int> idsFor({required bool includeLegacy}) =>
      includeLegacy ? allIds : productionIds;

  static PairingStrategyInfo? byId(int id) {
    for (final strategy in defaults) {
      if (strategy.id == id) return strategy;
    }
    return null;
  }

  factory PairingStrategyInfo.fromJson(Map<dynamic, dynamic> json) {
    final rawId = json['id'] ?? json['wireId'];
    if (rawId is! int || rawId < 1 || rawId > 4) {
      throw FormatException('Invalid pairing strategy id: $rawId');
    }
    final fallback = byId(rawId);
    final rawSlug = json['slug'];
    final rawMaturity = json['maturity'];
    final rawWatchOs = json['watchOs'] ?? json['watchOsRange'];
    final experimentalFlag = json['experimental'];
    final maturity = rawMaturity is String && rawMaturity.trim().isNotEmpty
        ? rawMaturity.trim().toLowerCase()
        : (experimentalFlag is bool
              ? (experimentalFlag ? 'experimental' : 'production')
              : (fallback?.maturity ??
                    (rawId == 4 ? 'production' : 'experimental')));
    return PairingStrategyInfo(
      id: rawId,
      slug: rawSlug is String && rawSlug.trim().isNotEmpty
          ? rawSlug.trim()
          : (fallback?.slug ?? 'strategy_$rawId'),
      maturity: maturity,
      watchOs: rawWatchOs is String && rawWatchOs.trim().isNotEmpty
          ? rawWatchOs.trim()
          : (fallback?.watchOs ?? ''),
    );
  }

  static PairingStrategyInfo? parse(dynamic value) {
    if (value is int) return byId(value);
    if (value is Map) {
      final rawId = value['id'] ?? value['wireId'];
      if (rawId is! int || rawId < 1 || rawId > 4) return null;
      return PairingStrategyInfo.fromJson(value);
    }
    return null;
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PairingStrategyInfo &&
          id == other.id &&
          slug == other.slug &&
          maturity == other.maturity &&
          watchOs == other.watchOs;

  @override
  int get hashCode => Object.hash(id, slug, maturity, watchOs);

  @override
  String toString() =>
      'PairingStrategyInfo(id: $id, slug: $slug, maturity: $maturity, watchOs: $watchOs)';
}
