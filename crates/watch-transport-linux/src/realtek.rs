//! Restore the installed RTL8821C RAM patch after WinUSB power transitions.
//! Uses the existing OEM firmware, never writes flash or changes the BD_ADDR.
use super::{Decoder, Endpoints, usb_error};
use rusb::{Context, DeviceHandle};
use std::{
    io,
    time::{Duration, Instant},
};

fn command(
    handle: &DeviceHandle<Context>,
    pipes: Endpoints,
    opcode: u16,
    parameters: &[u8],
) -> io::Result<Vec<u8>> {
    let mut bytes = opcode.to_le_bytes().to_vec();
    bytes.push(parameters.len() as u8);
    bytes.extend_from_slice(parameters);
    if handle
        .write_control(0x20, 0, 0, 0, &bytes, Duration::from_secs(2))
        .map_err(usb_error)?
        != bytes.len()
    {
        return Err(io::Error::other("Short Realtek initialization command"));
    }
    let mut decoder = Decoder {
        kind: 4,
        bytes: vec![],
    };
    let mut buffer = vec![0; pipes.event_packet_size as usize];
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        match handle.read_interrupt(pipes.events, &mut buffer, Duration::from_millis(500)) {
            Ok(count) => {
                for packet in decoder.accept(&buffer[..count])? {
                    if packet.len() >= 7
                        && packet[1] == 0x0e
                        && u16::from_le_bytes([packet[4], packet[5]]) == opcode
                    {
                        if packet[6] != 0 {
                            return Err(io::Error::other(format!(
                                "Realtek command 0x{opcode:04x} rejected: status 0x{:02x}",
                                packet[6]
                            )));
                        }
                        return Ok(packet[6..].to_vec());
                    }
                }
            }
            Err(rusb::Error::Timeout) => {}
            Err(error) => return Err(usb_error(error)),
        }
    }
    Err(io::Error::other(format!(
        "Realtek command 0x{opcode:04x} timed out"
    )))
}

fn patch(firmware: &[u8], rom: u8) -> io::Result<Vec<u8>> {
    let invalid = || io::Error::other("Invalid installed RTL8821C firmware");
    if firmware.len() < 22
        || &firmware[..8] != b"Realtech"
        || firmware[firmware.len() - 4..] != [0x51, 0x04, 0xfd, 0x77]
    {
        return Err(invalid());
    }
    let mut cursor = firmware.len() - 4;
    let mut project = None;
    while cursor >= 3 {
        let opcode = firmware[cursor - 1];
        if opcode == 0xff {
            break;
        }
        let length = firmware[cursor - 2] as usize;
        if length == 0 || length + 2 > cursor {
            return Err(invalid());
        }
        if opcode == 0 && length == 1 {
            project = Some(firmware[cursor - 3]);
            break;
        }
        cursor -= length + 2;
    }
    if project != Some(10) {
        return Err(invalid());
    }
    let count = u16::from_le_bytes([firmware[12], firmware[13]]) as usize;
    let table_end = 14 + 8 * count;
    if count == 0 || table_end > firmware.len() - 4 {
        return Err(invalid());
    }
    let mut result = None;
    for index in 0..count {
        let chip = 14 + 2 * index;
        if u16::from_le_bytes([firmware[chip], firmware[chip + 1]]) != rom as u16 + 1 {
            continue;
        }
        if result.is_some() {
            return Err(invalid());
        }
        let length_at = 14 + 2 * count + 2 * index;
        let length = u16::from_le_bytes([firmware[length_at], firmware[length_at + 1]]) as usize;
        let offset_at = 14 + 4 * count + 4 * index;
        let offset =
            u32::from_le_bytes(firmware[offset_at..offset_at + 4].try_into().unwrap()) as usize;
        if length < 4
            || offset < table_end
            || offset
                .checked_add(length)
                .is_none_or(|end| end > firmware.len() - 4)
        {
            return Err(invalid());
        }
        let mut bytes = firmware[offset..offset + length].to_vec();
        bytes[length - 4..].copy_from_slice(&firmware[8..12]);
        result = Some(bytes);
    }
    result.ok_or_else(invalid)
}

pub(super) fn initialize(handle: &DeviceHandle<Context>, pipes: Endpoints) -> io::Result<()> {
    command(handle, pipes, 0x0c03, &[])?;
    let version = command(handle, pipes, 0x1001, &[])?;
    if version.len() != 9 {
        return Err(io::Error::other("Invalid Realtek version response"));
    }
    // RTL8821C's unpatched ROM: HCI/LMP 4.2, revision 12, manufacturer 0x005d.
    // Loaded firmware has another revision/subversion and is left in place.
    if version != [0, 8, 12, 0, 8, 0x5d, 0, 0x21, 0x88] {
        return Ok(());
    }
    let rom = command(handle, pipes, 0xfc6d, &[])?;
    if rom.len() != 2 {
        return Err(io::Error::other("Invalid Realtek ROM response"));
    }
    let mut directory = [0u16; 32768];
    let count = unsafe {
        windows::Win32::System::SystemInformation::GetWindowsDirectoryW(Some(&mut directory))
    } as usize;
    if count == 0 || count >= directory.len() {
        return Err(io::Error::other(
            "Cannot resolve installed Realtek firmware",
        ));
    }
    let file = std::path::PathBuf::from(String::from_utf16_lossy(&directory[..count]))
        .join("rtl8821c_mp_chip_bt40_fw_asic_rom_patch_new");
    if std::fs::metadata(&file)?.len() > 1024 * 1024 {
        return Err(io::Error::other("Installed Realtek firmware is oversized"));
    }
    let firmware = std::fs::read(file)?;
    let bytes = patch(&firmware, rom[1])?;
    // The final fragment is required even for an exact multiple of 252 bytes.
    let fragments = bytes.len() / 252 + 1;
    let mut sequence = 0u8;
    for index in 0..fragments {
        let last = index + 1 == fragments;
        let start = index * 252;
        let mut parameters = vec![sequence | if last { 0x80 } else { 0 }];
        parameters.extend_from_slice(&bytes[start..std::cmp::min(start + 252, bytes.len())]);
        let reply = command(handle, pipes, 0xfc20, &parameters)?;
        if reply.len() != 2 || reply[1] != parameters[0] {
            return Err(io::Error::other(
                "Realtek firmware fragment was not acknowledged",
            ));
        }
        sequence = if sequence == 0x7f { 1 } else { sequence + 1 };
    }
    let updated = command(handle, pipes, 0x1001, &[])?;
    if updated.len() != 9 || updated[2..4] != firmware[10..12] || updated[7..9] != firmware[8..10] {
        return Err(io::Error::other("Realtek firmware version readback failed"));
    }
    eprintln!("Realtek RTL8821C firmware restored and verified");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let mut data = b"Realtech\x01\x02\x03\x04\x01\x00\x02\x00\x08\x00\x16\x00\x00\x00".to_vec();
        data.extend_from_slice(&[1, 2, 3, 4, 0, 0, 0, 0, 0xff, 10, 1, 0, 0x51, 4, 0xfd, 0x77]);
        data
    }
    #[test]
    fn cut_selection_and_firmware_version_are_validated() {
        assert_eq!(patch(&fixture(), 1).unwrap(), [1, 2, 3, 4, 1, 2, 3, 4]);
        assert!(patch(&fixture(), 0).is_err());
        for end in 0..fixture().len() {
            assert!(patch(&fixture()[..end], 1).is_err());
        }
        let mut wrong = fixture();
        wrong[31] = 13;
        assert!(patch(&wrong, 1).is_err());
        let mut wrong = fixture();
        wrong[18] = 0xff;
        assert!(patch(&wrong, 1).is_err());
    }
}
