//! A selected USB identity fixes its backend, even when other adapters change.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum UsbBackend {
    WinUsb,
    UsbDk,
}

#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
impl UsbBackend {
    pub(crate) fn select(preference: Option<&str>, winusb_present: bool) -> Self {
        match preference {
            Some("winusb") => Self::WinUsb,
            Some("usbdk") => Self::UsbDk,
            _ if winusb_present => Self::WinUsb,
            _ => Self::UsbDk,
        }
    }

    pub(crate) fn prefix(self) -> &'static str {
        match self {
            Self::WinUsb => "winusb",
            Self::UsbDk => "usb",
        }
    }

    pub(crate) fn from_identity(id: &str) -> Option<Self> {
        let parts = id.split(':').collect::<Vec<_>>();
        let [prefix, bus, ports, vendor, product] = parts.as_slice() else {
            return None;
        };
        if bus.len() > 3
            || !bus.bytes().all(|b| b.is_ascii_digit())
            || bus.parse::<u8>().is_err()
            || ports.len() > 32
            || !ports.split('.').all(|port| {
                !port.is_empty()
                    && port.bytes().all(|b| b.is_ascii_digit())
                    && port.parse::<u8>().is_ok_and(|number| number != 0)
            })
            || ![vendor, product].iter().all(|value| {
                value.len() == 4
                    && value
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
        {
            return None;
        }
        match *prefix {
            "winusb" => Some(Self::WinUsb),
            "usb" => Some(Self::UsbDk),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::UsbBackend;

    #[test]
    fn discovery_uses_the_current_inventory_and_explicit_preference() {
        assert_eq!(UsbBackend::select(None, false), UsbBackend::UsbDk);
        assert_eq!(UsbBackend::select(None, true), UsbBackend::WinUsb);
        assert_eq!(UsbBackend::select(None, false), UsbBackend::UsbDk);
        assert_eq!(UsbBackend::select(Some("usbdk"), true), UsbBackend::UsbDk);
        assert_eq!(
            UsbBackend::select(Some("winusb"), false),
            UsbBackend::WinUsb
        );
    }

    #[test]
    fn opening_uses_the_selected_identity_instead_of_another_controllers_backend() {
        for (prefix, expected) in [("winusb", UsbBackend::WinUsb), ("usb", UsbBackend::UsbDk)] {
            let id = format!("{prefix}:1:2.3:1234:abcd");
            assert_eq!(UsbBackend::from_identity(&id), Some(expected));
        }
    }

    #[test]
    fn malformed_or_non_usb_identities_are_rejected() {
        for id in [
            "",
            "winusb:256:1:1234:abcd",
            "usb:1:0:1234:abcd",
            "usb:1:1..2:1234:abcd",
            "usb:1:1:1234:abcd:extra",
            "usb:1:1:1234:ABCD",
            "hci:1:1:1234:abcd",
            "usb:+1:1:1234:abcd",
        ] {
            assert_eq!(UsbBackend::from_identity(id), None, "{id}");
        }
    }
}
