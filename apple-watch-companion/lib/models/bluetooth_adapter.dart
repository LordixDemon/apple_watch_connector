/// Host controller identity. HCI numbers are current routing data, not identity.
final class BluetoothAdapter {
  final String id, name, address, controller;
  final int index;
  const BluetoothAdapter(
    this.id,
    this.name,
    this.address,
    this.controller,
    this.index,
  );

  static BluetoothAdapter? parse(dynamic value) {
    if (value is! Map ||
        value['id'] is! String ||
        value['name'] is! String ||
        value['address'] is! String ||
        value['controller'] is! String ||
        value['index'] is! int) {
      return null;
    }
    final id = value['id'] as String, index = value['index'] as int;
    final usb = RegExp(
      r'^(?:usb|winusb):[0-9]{1,3}:[0-9.]{1,32}:[0-9a-f]{4}:[0-9a-f]{4}$',
    ).hasMatch(id);
    final bluetooth =
        RegExp(r'^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$').hasMatch(id) &&
        id != '00:00:00:00:00:00';
    if ((!bluetooth && !usb) ||
        value['address'] != id ||
        index < 0 ||
        index >= 65535 ||
        value['controller'] != (usb ? 'usb$index' : 'hci$index') ||
        (value['name'] as String).length > 240) {
      return null;
    }
    return BluetoothAdapter(
      id,
      value['name'] as String,
      id,
      usb ? 'usb$index' : 'hci$index',
      index,
    );
  }
}
