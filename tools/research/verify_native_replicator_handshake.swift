import Foundation
import Darwin

// Research-only, data-only check against watchOS's actual OPACK and Codable types.
// No service, network connection, publisher, paired identity or Watch mutation.
func verify() throws {
    let message = CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--message"
    let complete = CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--complete"
    let response = CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--response"
    guard CommandLine.arguments.count == 2 || message || complete || response else { throw Failure.arguments }
    let input = URL(fileURLWithPath: CommandLine.arguments.last!).standardizedFileURL
    guard input.path.hasPrefix("/tmp/") || input.path.hasPrefix("/private/tmp/") else { throw Failure.arguments }
    let data = try Data(contentsOf: input)
    guard !data.isEmpty, data.count <= 4096,
          dlopen("/System/Library/PrivateFrameworks/CoreUtils.framework/CoreUtils", RTLD_NOW) != nil,
          dlopen("/System/Library/PrivateFrameworks/ReplicatorEngine.framework/ReplicatorEngine", RTLD_NOW) != nil,
          let decodeAddress = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "OPACKDecodeData"),
          let type = _typeByName(message ? "16ReplicatorEngine7MessageV" : "16ReplicatorEngine17ReplicatorMessageO") as? any Decodable.Type
    else { throw Failure.codec }
    typealias Decode = @convention(c) (UnsafeRawPointer, UInt32, UnsafeMutablePointer<Int32>) -> UnsafeRawPointer?
    let decode = unsafeBitCast(decodeAddress, to: Decode.self)
    var error: Int32 = 0
    let object: AnyObject = try withExtendedLifetime(data as NSData) { bytes in
        guard let result = decode(Unmanaged.passUnretained(bytes).toOpaque(), 0, &error), error == 0
        else { throw Failure.decode }
        return Unmanaged<AnyObject>.fromOpaque(result).takeRetainedValue()
    }
    let json = try JSONSerialization.data(withJSONObject: jsonValue(object))
    let native = try JSONDecoder().decode(type, from: json)
    guard let encodable = native as? any Encodable else { throw Failure.codec }
    let canonical = try JSONEncoder().encode(encodable)
    if message {
        guard let root = try JSONSerialization.jsonObject(with: canonical) as? [String: Any],
              root["messageType"] as? String == "StateReplicator", root["protocolVersion"] as? Int == 8
        else { throw Failure.schema }
        print("Java Message OPACK accepted by native CoreUtils and Message Decodable; bytes=\(data.count)")
        return
    }
    if complete {
        guard let root = try JSONSerialization.jsonObject(with: canonical) as? [String: Any],
              let handshake = root["handshake"] as? [String: Any], let zero = handshake["_0"] as? [String: Any],
              let complete = zero["complete"] as? [String: Any], let values = complete["_0"] as? [String: Any],
              let manifest = values["recordManifest"] as? [String: Any], let records = manifest["recordVersions"] as? [Any], records.isEmpty
        else { throw Failure.schema }
        print("Java complete OPACK accepted by native CoreUtils and ReplicatorMessage Decodable; empty manifest; bytes=\(data.count)")
        return
    }
    guard let root = try JSONSerialization.jsonObject(with: canonical) as? [String: Any],
          let handshake = root["handshake"] as? [String: Any],
          let zero = handshake["_0"] as? [String: Any],
          let request = zero[response ? "response" : "request"] as? [String: Any],
          let values = request["_0"] as? [String: Any],
          let device = values["device"] as? [String: Any],
          let zones = device["zones"] as? [Any], zones.count == 4,
          device["deviceType"] as? Int == 2, device["isSource"] as? Bool == true
    else { throw Failure.schema }
    print("Java \(response ? "response" : "request") OPACK accepted by native CoreUtils and ReplicatorMessage Decodable; phone source; two snapshot zones; bytes=\(data.count)")
}
private func jsonValue(_ value: Any) -> Any {
    if let data = value as? Data { return data.base64EncodedString() }
    if let map = value as? [String: Any] { return map.mapValues(jsonValue) }
    if let list = value as? [Any] { return list.map(jsonValue) }
    return value
}
enum Failure: Error { case arguments, codec, decode, schema }
do { try verify() } catch { fputs("Native handshake verification failed: \(error)\n", stderr); exit(1) }
