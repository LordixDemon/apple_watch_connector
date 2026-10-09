//! Narrow packet broker. Only this executable needs Bluetooth capabilities.
#[cfg(target_os = "linux")]
mod platform {
    use std::io::{self, Read, Write};
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

    #[repr(C)]
    struct HciAddress {
        family: u16,
        device: u16,
        channel: u16,
    }

    fn valid_packet(packet: &[u8], outgoing: bool) -> bool {
        match packet.first() {
            Some(1) if outgoing => packet.len() >= 4 && packet.len() == 4 + packet[3] as usize,
            Some(2) => {
                packet.len() >= 5
                    && packet.len() == 5 + u16::from_le_bytes([packet[3], packet[4]]) as usize
            }
            Some(4) if !outgoing => packet.len() >= 3 && packet.len() == 3 + packet[2] as usize,
            _ => false,
        }
    }
    fn unbuffered_input(fd: i32) -> io::Result<std::fs::File> {
        // The duplicate is owned here; closing it never closes the caller's fd.
        let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
        if duplicate < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(std::fs::File::from(unsafe {
            OwnedFd::from_raw_fd(duplicate)
        }))
    }
    pub fn run() -> io::Result<()> {
        let index = std::env::args()
            .nth(1)
            .ok_or_else(|| io::Error::other("Missing controller index"))?;
        let device = index
            .parse::<u16>()
            .map_err(|_| io::Error::other("Invalid controller index"))?;
        if device == u16::MAX {
            return Err(io::Error::other("Invalid controller index"));
        }
        // Linux UAPI sockaddr_hci and HCI_CHANNEL_USER. A failed bind never
        // downgrades to shared RAW ownership; bluetoothd must release this adapter.
        let raw = unsafe { libc::socket(31, libc::SOCK_RAW | libc::SOCK_CLOEXEC, 1) };
        if raw < 0 {
            return Err(io::Error::last_os_error());
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let address = HciAddress {
            family: 31,
            device,
            channel: 1,
        };
        let result = unsafe {
            libc::bind(
                fd.as_raw_fd(),
                (&address as *const HciAddress).cast(),
                std::mem::size_of::<HciAddress>() as libc::socklen_t,
            )
        };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        // StdinLock uses an internal buffer. It can prefetch the next envelope,
        // making poll(0) see an empty pipe while an ACL continuation remains
        // buffered forever. Read directly from a duplicated fd with no read-ahead.
        let mut input = unbuffered_input(0)?;
        let mut output = io::stdout().lock();
        output.write_all(&[0, 1, 0])?;
        output.flush()?;
        let mut packet = vec![0_u8; 65535];
        loop {
            let mut fds = [
                libc::pollfd {
                    fd: fd.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: 0,
                    events: libc::POLLIN,
                    revents: 0,
                },
            ];
            let result = unsafe { libc::poll(fds.as_mut_ptr(), 2, -1) };
            if result < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(error);
            }
            if fds[1].revents & (libc::POLLIN | libc::POLLHUP) != 0 {
                let mut header = [0; 2];
                match input.read_exact(&mut header) {
                    Ok(()) => {}
                    Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
                    Err(error) => return Err(error),
                }
                let length = u16::from_be_bytes(header) as usize;
                input.read_exact(&mut packet[..length])?;
                if !valid_packet(&packet[..length], true) {
                    return Err(io::Error::other("Invalid outbound H4 packet"));
                }
                let count = unsafe {
                    libc::send(
                        fd.as_raw_fd(),
                        packet.as_ptr().cast(),
                        length,
                        libc::MSG_NOSIGNAL,
                    )
                };
                if count != length as isize {
                    return Err(io::Error::last_os_error());
                }
            }
            if fds[0].revents & libc::POLLIN != 0 {
                let count = unsafe {
                    libc::recv(fd.as_raw_fd(), packet.as_mut_ptr().cast(), packet.len(), 0)
                };
                if count <= 0 {
                    return Err(io::Error::last_os_error());
                }
                let length = count as usize;
                if valid_packet(&packet[..length], false) {
                    output.write_all(&(length as u16).to_be_bytes())?;
                    output.write_all(&packet[..length])?;
                    output.flush()?;
                }
            }
            if fds[0].revents & (libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
                return Err(io::Error::other("Controller disconnected"));
            }
        }
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn coalesced_envelopes_remain_visible_to_poll() {
            let (read, mut write) = std::os::unix::net::UnixStream::pair().unwrap();
            let mut input = unbuffered_input(read.as_raw_fd()).unwrap();
            write
                .write_all(&[0, 4, 1, 3, 12, 0, 0, 4, 1, 3, 12, 0])
                .unwrap();
            let mut first = [0; 6];
            input.read_exact(&mut first).unwrap();
            let mut pending = libc::pollfd {
                fd: read.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            };
            assert_eq!(unsafe { libc::poll(&mut pending, 1, 0) }, 1);
            let mut second = [0; 6];
            input.read_exact(&mut second).unwrap();
            assert_eq!(first, second);
        }
        #[test]
        fn packet_boundary_rejects_truncation_and_wrong_direction() {
            assert!(valid_packet(&[1, 3, 12, 0], true));
            assert!(!valid_packet(&[1, 3, 12, 0], false));
            assert!(valid_packet(&[2, 1, 0, 2, 0, 0xaa, 0xbb], true));
            assert!(!valid_packet(&[2, 1, 0, 2, 0, 0xaa], true));
            assert!(valid_packet(&[4, 14, 1, 0], false));
            assert!(!valid_packet(&[4, 14, 1, 0], true));
            assert!(!valid_packet(&[], true));
        }
    }
}

fn main() {
    #[cfg(target_os = "linux")]
    if let Err(error) = platform::run() {
        eprintln!("Linux HCI lease failed: {error}");
        std::process::exit(1);
    }
    #[cfg(not(target_os = "linux"))]
    {
        eprintln!("HCI user channel requires Linux");
        std::process::exit(1);
    }
}
