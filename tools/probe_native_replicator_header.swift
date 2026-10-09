import Foundation
import Darwin

private struct NativeDataWords { let first: UInt64; let second: UInt64 }
@_silgen_name("probeNativeReplicatorHeaderData")
private func nativeHeaderData(_ value: UnsafeRawPointer, _ function: UnsafeRawPointer) -> NativeDataWords

private func binaryHeader<T: Encodable>(_ value: T) throws -> Data {
    let symbol = "$s16ReplicatorEngine10OPACKCoderC6encode_7version10Foundation4DataVSE_p_s6UInt64VSgtKFZ"
    guard let encoder = dlsym(UnsafeMutableRawPointer(bitPattern: -2), symbol) else { throw Failure.nativeEncoder }
    var image = Dl_info()
    guard dladdr(encoder, &image) != 0, let base = image.dli_fbase,
          UInt(bitPattern: encoder) - UInt(bitPattern: base) == 0x1303cc else { throw Failure.nativeEncoder }
    // Audited in the 23S303 arm64 simulator binary, not a firmware offset. The
    // production application never loads or calls this private function.
    let getter = base.advanced(by: 0xa96cc)
    let words = withUnsafePointer(to: value) { nativeHeaderData($0, getter) }
    guard MemoryLayout<Data>.size == MemoryLayout<NativeDataWords>.size else { throw Failure.nativeEncoder }
    return unsafeBitCast(words, to: Data.self)
}

// Simulator-only controlled codec probe. Does not create a service or connection.
func run() throws {
    guard CommandLine.arguments.count == 2 || CommandLine.arguments.count == 3 else { throw Failure.arguments }
    let mode = CommandLine.arguments.count == 3 ? CommandLine.arguments[1] : "--header"
    let destination = URL(fileURLWithPath: CommandLine.arguments.last!).standardizedFileURL
    guard destination.path.hasPrefix("/tmp/") || destination.path.hasPrefix("/private/tmp/"),
          !FileManager.default.fileExists(atPath: destination.path) else { throw Failure.destination }
    guard dlopen("/System/Library/PrivateFrameworks/ReplicatorEngine.framework/ReplicatorEngine", RTLD_NOW) != nil
    else {
        if let reason = dlerror() { fputs("Native load: \(String(cString: reason))\n", stderr) }
        throw Failure.nativeType
    }
    let nativeName: String
    let input: [String: Any]
    switch mode {
    case "--header", "--binary-header", "--binary-headers":
        nativeName = "16ReplicatorEngine17NetworkSyncHeaderV"
        input = [
            "prefix": mode != "--header" ? "64d52923-8384-4b61-b55b-e53e9d20272c"
                : "00112233-4455-6677-8899-aabbccddeeff",
            "headerLength": mode != "--header" ? 72 : 100,
            "messageID": "11223344-5566-7788-9900-aabbccddeeff",
            "senderID": "22334455-6677-8899-0011-aabbccddeeff",
            "length": 432, "messageType": 0, "sequenceCount": 1,
            "sequenceIndex": 0, "priority": 0,
        ]
    case "--message":
        nativeName = "16ReplicatorEngine7MessageV"
        input = [
            "id": "11223344-5566-7788-9900-aabbccddeeff",
            "messageType": "controlled.codec.probe", "senderDeviceID": "controlled-simulator",
            "protocolVersion": 8, "encodedBody": Data([1, 2, 3, 4]).base64EncodedString(),
        ]
    case "--ack":
        nativeName = "16ReplicatorEngine17ReplicatorMessageO"
        input = ["ack": ["_0": [:]]]
    case "--advertisement":
        nativeName = "16ReplicatorEngine24ZoneVersionAdvertisementV"
        input = [
            "remoteDevice": [
                "id": "controlled-simulator", "name": "Controlled codec probe",
                "protocolVersion": ["current": 8, "minimum": 8], "deviceType": 4,
                "zones": [], "messageTypes": [],
            ],
            "zoneVersions": [:],
        ]
    case "--handshake-complete":
        nativeName = "16ReplicatorEngine17ReplicatorMessageO"
        input = ["handshake": ["_0": ["complete": ["_0": [
            "sessionID": "33445566-7788-9900-1122-aabbccddeeff",
            "relationshipState": ["paired": [:]], "mismatchedZones": [],
            "recordManifest": ["recordVersions": []],
        ]]]]]
    case "--handshake", "--handshake-readonly":
        nativeName = "16ReplicatorEngine17ReplicatorMessageO"
        let zones: [[String: Any]] = ["library_snapshots", "gallery_snapshots"].flatMap { name in
            let id: [String: Any] = ["id": name, "clientID": "com.apple.nanotimekit.replicator.library"]
            return [id, ["id": id, "protocolVersion": ["current": 6, "minimum": 6]]]
        }
        input = ["handshake": ["_0": ["request": ["_0": [
            "sessionID": "33445566-7788-9900-1122-aabbccddeeff",
            "relationshipState": ["paired": [:]],
            "device": [
                "id": "controlled-simulator", "name": "Controlled codec probe",
                "protocolVersion": ["current": 8, "minimum": 8],
                "deviceType": mode == "--handshake-readonly" ? 2 : 4,
                "zones": mode == "--handshake-readonly" ? zones : [], "messageTypes": [],
            ],
            "zoneVersions": mode == "--handshake-readonly" ? [:] : ["controlled-zone": ["empty": [:]]],
        ]]]]]
    case "--sync", "--sync-v0":
        nativeName = "16ReplicatorEngine17ReplicatorMessageO"
        let recordID: [String: Any] = [
            "identifier": "controlled-snapshot",
            "zoneIdentifier": ["id": "controlled-zone", "clientID": "controlled-client"],
            "ownership": ["local": [:]],
        ]
        input = ["sync": ["_0": [
            "sessionID": "33445566-7788-9900-1122-aabbccddeeff",
            "record": ["id": recordID, "protocolVersion": 6, "value": ["data": [
                "_0": ["id": recordID, "version": "44556677-8899-0011-2233-aabbccddeeff",
                    "destination": ["all": [:]], "options": 0],
                "_1": Data([1, 2, 3, 4]).base64EncodedString(),
            ]]],
        ]]]
    default: throw Failure.arguments
    }
    let resolved = _typeByName(nativeName)
    guard let type = resolved as? any Decodable.Type else {
        fputs("Native codec type: \(String(describing: resolved))\n", stderr)
        throw Failure.nativeType
    }
    // Use the actual native Decodable and Encodable witnesses, not a local clone.
    let object = try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: input))
    guard let encodable = object as? any Encodable else { throw Failure.nativeType }
    let symbol = "$s16ReplicatorEngine10OPACKCoderC6encode_7version10Foundation4DataVSE_p_s6UInt64VSgtKFZ"
    guard let address = dlsym(UnsafeMutableRawPointer(bitPattern: -2), symbol) else { throw Failure.nativeEncoder }
    // Verified public Swift static function ABI in the same runtime. No network
    // publisher is invoked; input is controlled data with no paired IDs.
    typealias Encode = @convention(thin) (any Encodable, UInt64?) throws -> Data
    let encode = unsafeBitCast(address, to: Encode.self)
    let version: UInt64? = mode == "--sync" || mode == "--handshake-readonly" || mode == "--handshake-complete" ? 8 : mode == "--sync-v0" ? 0 : nil
    let data: Data
    if mode == "--binary-headers" {
        var captured = Data()
        for messageType in 0..<5 {
            for priority in 0..<3 {
                var row = input
                row["messageType"] = messageType
                row["priority"] = priority
                let decoded = try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: row))
                guard let native = decoded as? any Encodable else { throw Failure.nativeType }
                let header = try _openExistential(native, do: binaryHeader)
                guard header.count == 72 else { throw Failure.size }
                captured.append(header)
            }
        }
        data = captured
    } else {
        data = mode == "--binary-header" ? try _openExistential(encodable, do: binaryHeader)
            : try encode(encodable, version)
    }
    guard !data.isEmpty, data.count <= 4096 else { throw Failure.size }
    try data.write(to: destination, options: .withoutOverwriting)
    let canonical = try JSONEncoder().encode(encodable)
    FileHandle.standardOutput.write(canonical)
    print("\nNative \(mode) bytes: \(data.count)")
}

enum Failure: Error { case arguments, destination, nativeType, nativeEncoder, size }
do { try run() } catch { fputs("Native header probe failed: \(error)\n", stderr); exit(1) }
