import Foundation
import Darwin

// Data-only controlled native Zone.ID description. No services or paired IDs.
do {
    guard CommandLine.arguments.count == 1,
          dlopen("/System/Library/PrivateFrameworks/ReplicatorEngine.framework/ReplicatorEngine", RTLD_NOW) != nil,
          let type = _typeByName("16ReplicatorEngine4ZoneC2IDC") as? any Decodable.Type
    else { throw ProbeError.codec }
    for id in ["library_snapshots", "gallery_snapshots"] {
        let data = try JSONSerialization.data(withJSONObject: ["id": id,
            "clientID": "com.apple.nanotimekit.replicator.library"])
        let value = try JSONDecoder().decode(type, from: data)
        guard let printable = value as? any CustomStringConvertible else { throw ProbeError.codec }
        print("Controlled native Zone.ID: \(printable.description)")
    }
} catch {
    fputs("Controlled Zone.ID probe failed: \(error)\n", stderr)
    exit(1)
}
enum ProbeError: Error { case codec }
