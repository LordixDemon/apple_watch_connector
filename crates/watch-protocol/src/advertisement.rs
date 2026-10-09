//! FE25 setup service-data decoder. Bit layout matches HciCodec and
//! WatchSetupMetadataCodec, including the physical Watch7,5 / 26.2 fixture.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchAdvertisement {
    pub pairing_strategy: u8,
    pub pairing_version: u8,
    pub product_major: u8,
    pub product_minor: u8,
    pub system_version: [u16; 3],
}
impl WatchAdvertisement {
    /// CoreBluetooth strips the FE25 UUID. No pairing secret is returned to UI.
    pub fn from_service_data(bytes: &[u8]) -> Option<Self> {
        let subtype = *bytes.first()?;
        if subtype & 0x7f != 6 {
            return None;
        }
        let data = if subtype & 0x80 != 0 {
            let length = (*bytes.get(1)? & 0x1f) as usize;
            bytes.get(2..2 + length)?
        } else {
            bytes.get(1..)?
        };
        if data.len() < 12 || data[0] & 0xe0 != 0x20 {
            return None;
        }
        let m = &data[5..];
        if m[6] & 7 != 0 {
            return None;
        }
        let encoded = ((m[2] as u32 & 7) << 29)
            | ((m[3] as u32) << 21)
            | ((m[4] as u32) << 13)
            | ((m[5] as u32) << 5)
            | ((m[6] as u32) >> 3);
        let result = Self {
            pairing_strategy: data[1] >> 5,
            pairing_version: m[0] >> 2,
            product_major: ((m[0] & 3) << 5) | (m[1] >> 3),
            product_minor: ((m[1] & 7) << 4) | (m[2] >> 4),
            system_version: [
                (encoded >> 16) as u16,
                ((encoded >> 8) & 0xff) as u16,
                (encoded & 0xff) as u16,
            ],
        };
        (result.pairing_strategy == 4 && result.product_major != 0).then_some(result)
    }
    pub fn product_type(&self) -> String {
        format!("Watch{},{}", self.product_major, self.product_minor)
    }
    /// Exact twelve-byte binding used by salted-PIN authentication. Keep it
    /// inside the pairing core; the public device projection only needs metadata.
    pub fn binding_data(bytes: &[u8]) -> Option<[u8; 12]> {
        Self::from_service_data(bytes)?;
        let start = if bytes[0] & 0x80 != 0 { 2 } else { 1 };
        bytes.get(start..start + 12)?.try_into().ok()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    const FIXTURE: [u8; 13] = [
        6, 0x20, 0x86, 0x93, 0xf4, 0xce, 0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0,
    ];
    #[test]
    fn same_physical_fixture_as_java() {
        let decoded = WatchAdvertisement::from_service_data(&FIXTURE).unwrap();
        assert_eq!(decoded.product_type(), "Watch7,5");
        assert_eq!(decoded.system_version, [26, 2, 0]);
        assert_eq!(decoded.pairing_version, 25);
    }
    #[test]
    fn explicit_length_and_future_extension_are_bounded() {
        let mut framed = vec![0x86, 12];
        framed.extend_from_slice(&FIXTURE[1..]);
        framed.extend_from_slice(&[0xaa, 0xbb]);
        assert_eq!(
            WatchAdvertisement::from_service_data(&framed),
            WatchAdvertisement::from_service_data(&FIXTURE)
        );
        framed[1] = 31;
        assert!(WatchAdvertisement::from_service_data(&framed).is_none());
        for length in 0..FIXTURE.len() {
            assert!(WatchAdvertisement::from_service_data(&FIXTURE[..length]).is_none());
        }
    }
    #[test]
    fn unknown_services_and_noncanonical_padding_are_not_watches() {
        let mut data = FIXTURE;
        data[0] = 5;
        assert!(WatchAdvertisement::from_service_data(&data).is_none());
        data[0] = 6;
        data[12] = 1;
        assert!(WatchAdvertisement::from_service_data(&data).is_none());
    }
}
