import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  private var textChannel: FlutterMethodChannel?

  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)
    self.setContentSize(NSSize(width: 1040, height: 760))
    self.minSize = NSSize(width: 740, height: 560)

    RegisterGeneratedPlugins(registry: flutterViewController)
    let text = FlutterMethodChannel(name: "dev.applewatchandroid.companion/text",
                                    binaryMessenger: flutterViewController.engine.binaryMessenger)
    text.setMethodCallHandler { call, result in
      guard call.method == "normalizeMonogram" else {
        result(FlutterMethodNotImplemented)
        return
      }
      guard let args = call.arguments as? [String: Any], let input = args["text"] as? String,
            input.utf16.count <= 4096 else {
        result(FlutterError(code: "INVALID_TEXT", message: "Invalid monogram input", details: nil))
        return
      }
      result(NativeMonogramTextNormalizer.normalize(input, locale: .current))
    }
    textChannel = text

    super.awakeFromNib()
  }
}

enum NativeMonogramTextNormalizer {
  static func normalize(_ input: String, locale: Locale) -> String {
    let text = input as NSString
    let range = text.rangeOfComposedCharacterSequences(for: NSRange(location: 0, length: min(text.length, 5)))
    return (text.substring(with: range) as NSString).uppercased(with: locale)
  }
}
