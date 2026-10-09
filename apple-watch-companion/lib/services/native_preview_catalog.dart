import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

const nativePreviewManifestAsset =
    'assets/native_faces/configured_previews.json';
const _maxDocumentBytes = 2 * 1024 * 1024;
final _keyPattern = RegExp(r'^[a-f0-9]{64}$');
final _prefixPattern = RegExp(r'^[a-f0-9]{2}$');
final _indexAssetPattern = RegExp(
  r'^assets/native_faces/configured_preview_indexes/([a-f0-9]{64})\.json$',
);

/// Pure metadata decoder; each document has its own bounded allocation budget.
class NativePreviewIndex {
  final Map<String, String> entries;
  final Map<String, NativePreviewShard> shards;
  const NativePreviewIndex(this.entries, this.shards);

  static NativePreviewIndex decode(Uint8List bytes) {
    final value = _document(bytes);
    if (value['locale'] != 'en_US' || value['blankComplications'] != true) {
      throw const FormatException('Incompatible native preview catalog.');
    }
    if (value['version'] == 1 || value['version'] == 2) {
      if (value['entries'] is! Map) {
        throw const FormatException('Invalid preview entries.');
      }
      return NativePreviewIndex(
        decodeEntries(value['entries'] as Map, indexed: value['version'] == 2),
        const {},
      );
    }
    if (value['version'] != 3 ||
        bytes.length > 128 * 1024 ||
        value['shards'] is! Map ||
        (value['shards'] as Map).length > 256 ||
        value.containsKey('entries')) {
      throw const FormatException('Invalid native preview index.');
    }
    final shards = <String, NativePreviewShard>{};
    for (final entry in (value['shards'] as Map).entries) {
      final prefix = entry.key;
      final shard = entry.value;
      if (prefix is! String ||
          !_prefixPattern.hasMatch(prefix) ||
          shard is! Map ||
          shard['asset'] is! String ||
          !_indexAssetPattern.hasMatch(shard['asset'] as String) ||
          shard['count'] is! int ||
          shard['count'] < 1 ||
          shard['count'] > 10000 ||
          shard['bytes'] is! int ||
          shard['bytes'] < 1 ||
          shard['bytes'] > _maxDocumentBytes) {
        throw const FormatException('Invalid native preview shard reference.');
      }
      shards[prefix] = NativePreviewShard(
        prefix,
        shard['asset'] as String,
        shard['count'] as int,
        shard['bytes'] as int,
      );
    }
    return NativePreviewIndex(const {}, Map.unmodifiable(shards));
  }

  static Map<String, String> decodeEntries(
    Map entries, {
    bool indexed = false,
  }) {
    if (entries.length > 10000) {
      throw const FormatException('Preview count exceeded.');
    }
    final asset = RegExp(
      indexed
          ? r'^assets/native_faces/configured_previews/[a-f0-9]{64}\.(webp|nfp)$'
          : r'^assets/native_faces/configured_previews/[a-f0-9]{64}\.webp$',
    );
    final result = <String, String>{};
    for (final entry in entries.entries) {
      if (entry.key is! String ||
          !_keyPattern.hasMatch(entry.key) ||
          entry.value is! String ||
          !asset.hasMatch(entry.value)) {
        throw const FormatException('Invalid native preview asset.');
      }
      result[entry.key as String] = entry.value as String;
    }
    return Map.unmodifiable(result);
  }
}

Map _document(Uint8List bytes) {
  if (bytes.length > _maxDocumentBytes) {
    throw const FormatException('Preview document too large.');
  }
  final value = jsonDecode(utf8.decode(bytes));
  if (value is! Map) throw const FormatException('Invalid preview document.');
  return value;
}

class NativePreviewShard {
  final String prefix, asset;
  final int count, bytes;
  const NativePreviewShard(this.prefix, this.asset, this.count, this.bytes);
}

class NativePreviewShardRequest {
  final NativePreviewShard reference;
  final Uint8List bytes;
  const NativePreviewShardRequest(this.reference, this.bytes);
}

Map<String, String> decodeNativePreviewShard(
  NativePreviewShardRequest request,
) {
  final reference = request.reference;
  if (request.bytes.length != reference.bytes ||
      request.bytes.length > _maxDocumentBytes ||
      sha256.convert(request.bytes).toString() !=
          _indexAssetPattern.firstMatch(reference.asset)?.group(1)) {
    throw const FormatException('Native preview shard identity mismatch.');
  }
  final value = _document(request.bytes);
  if (value['version'] != 1 ||
      value['prefix'] != reference.prefix ||
      value['entries'] is! Map) {
    throw const FormatException('Invalid native preview shard.');
  }
  final entries = NativePreviewIndex.decodeEntries(
    value['entries'] as Map,
    indexed: true,
  );
  if (entries.length != reference.count ||
      entries.keys.any((key) => !key.startsWith(reference.prefix))) {
    throw const FormatException('Native preview shard membership mismatch.');
  }
  return entries;
}

typedef NativePreviewAssetLoader = Future<Uint8List> Function(String asset);
typedef NativePreviewIndexDecoder =
    FutureOr<NativePreviewIndex> Function(Uint8List bytes);
typedef NativePreviewShardDecoder =
    FutureOr<Map<String, String>> Function(NativePreviewShardRequest request);

/// Load only the hash prefixes actually displayed. Queue and encoded-data caches
/// are bounded; resolved widgets retain their own immutable asset path.
class NativePreviewCatalog {
  final NativePreviewAssetLoader loadAsset;
  final NativePreviewIndexDecoder decodeIndex;
  final NativePreviewShardDecoder decodeShard;
  final int maxCachedShards, maxCachedBytes, maxMemoEntries;
  NativePreviewCatalog({
    required this.loadAsset,
    this.decodeIndex = NativePreviewIndex.decode,
    this.decodeShard = decodeNativePreviewShard,
    this.maxCachedShards = 64,
    this.maxCachedBytes = 4 * 1024 * 1024,
    this.maxMemoEntries = 512,
  }) : assert(maxCachedShards > 0 && maxCachedBytes > 0 && maxMemoEntries > 0);

  NativePreviewIndex? _index;
  Future<void>? _loading;
  final Map<String, Map<String, String>> _cache = {};
  final Map<String, String?> _memo = {};
  final _flights = <String, Completer<Map<String, String>>>{};
  final _pending = Queue<String>();
  int _active = 0, _cacheBytes = 0, _memoBytes = 0;
  int get cachedShardCount => _cache.length;
  int get cachedEncodedBytes => _cacheBytes;
  int get memoEntryCount => _memo.length;
  int get activeLoads => _active;
  int get pendingLoads => _pending.length;

  Future<void> load() => _loading ??= _load();
  Future<void> _load() async {
    try {
      _index = await decodeIndex(await loadAsset(nativePreviewManifestAsset));
    } catch (_) {
      _loading = null;
      rethrow;
    }
  }

  bool isResolved(String key) =>
      !_keyPattern.hasMatch(key) ||
      _memo.containsKey(key) ||
      (_index != null &&
          (_index!.shards.isEmpty ||
              !_index!.shards.containsKey(key.substring(0, 2)) ||
              _cache.containsKey(key.substring(0, 2))));

  String? peek(String key) {
    if (!_keyPattern.hasMatch(key)) return null;
    if (_memo.containsKey(key)) {
      final result = _memo.remove(key);
      _memo[key] = result;
      return result;
    }
    final prefix = key.substring(0, 2);
    final entries = _cache.remove(prefix);
    if (entries != null) _cache[prefix] = entries;
    return entries?[key] ?? _index?.entries[key];
  }

  Future<String?> resolve(String key) async {
    if (!_keyPattern.hasMatch(key)) return null;
    await load();
    if (isResolved(key)) {
      final result = peek(key);
      _remember(key, result);
      return result;
    }
    final prefix = key.substring(0, 2);
    var flight = _flights[prefix];
    if (flight == null) {
      flight = Completer<Map<String, String>>();
      _flights[prefix] = flight;
      _pending.add(prefix);
      _pump();
    }
    final result = (await flight.future)[key];
    _remember(key, result);
    return result;
  }

  void _remember(String key, String? asset) {
    if (_memo.containsKey(key)) {
      _memoBytes -= key.length + (_memo.remove(key)?.length ?? 0);
    }
    _memo[key] = asset;
    _memoBytes += key.length + (asset?.length ?? 0);
    while (_memo.length > maxMemoEntries || _memoBytes > 128 * 1024) {
      final first = _memo.keys.first;
      _memoBytes -= first.length + (_memo.remove(first)?.length ?? 0);
    }
  }

  void _pump() {
    while (_active < 2 && _pending.isNotEmpty) {
      final prefix = _pending.removeFirst();
      _active++;
      unawaited(_read(prefix));
    }
  }

  Future<void> _read(String prefix) async {
    final flight = _flights[prefix]!;
    final reference = _index!.shards[prefix]!;
    try {
      final entries = await decodeShard(
        NativePreviewShardRequest(reference, await loadAsset(reference.asset)),
      );
      _cache[prefix] = entries;
      _cacheBytes += reference.bytes;
      while (_cache.length > maxCachedShards || _cacheBytes > maxCachedBytes) {
        final first = _cache.keys.first;
        _cache.remove(first);
        _cacheBytes -= _index!.shards[first]!.bytes;
      }
      flight.complete(entries);
    } catch (error, stack) {
      flight.completeError(error, stack);
    } finally {
      _flights.remove(prefix);
      _active--;
      _pump();
    }
  }
}
