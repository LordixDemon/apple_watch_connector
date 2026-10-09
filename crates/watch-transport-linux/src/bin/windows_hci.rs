#[cfg(target_os = "windows")]
fn main() {
    use std::io::{self, Read, Write};
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        let args = std::env::args().skip(1).collect::<Vec<_>>();
        match args.as_slice() {
            [mode] if mode == "--list" => println!(
                "{}",
                watch_transport_linux::usb::inventory().map_err(io::Error::other)?
            ),
            [mode, id] if mode == "--open" => watch_transport_linux::usb::run(id)?,
            [mode] if mode == "--protect" || mode == "--unprotect" => {
                use windows::{
                    Win32::{
                        Foundation::{HLOCAL, LocalFree},
                        Security::Cryptography::{
                            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData,
                            CryptUnprotectData,
                        },
                    },
                    core::PCWSTR,
                };
                let mut input = Vec::new();
                io::stdin().take(512 * 1024 + 1).read_to_end(&mut input)?;
                if input.is_empty() || input.len() > 512 * 1024 {
                    return Err("Invalid private record length".into());
                }
                let source = CRYPT_INTEGER_BLOB {
                    cbData: input.len() as u32,
                    pbData: input.as_mut_ptr(),
                };
                let mut output = CRYPT_INTEGER_BLOB::default();
                unsafe {
                    let result = if mode == "--protect" {
                        CryptProtectData(
                            &source,
                            PCWSTR::null(),
                            None,
                            None,
                            None,
                            CRYPTPROTECT_UI_FORBIDDEN,
                            &mut output,
                        )
                    } else {
                        CryptUnprotectData(
                            &source,
                            None,
                            None,
                            None,
                            None,
                            CRYPTPROTECT_UI_FORBIDDEN,
                            &mut output,
                        )
                    };
                    input.fill(0);
                    result?;
                    let bytes =
                        std::slice::from_raw_parts_mut(output.pbData, output.cbData as usize);
                    let write = io::stdout().write_all(bytes);
                    bytes.fill(0);
                    LocalFree(Some(HLOCAL(output.pbData.cast())));
                    write?;
                }
            }
            _ => {
                return Err(
                    "Expected --list, --open <USB identity>, --protect or --unprotect".into(),
                );
            }
        }
        Ok(())
    })();
    if let Err(error) = result {
        // Native transport failures must reach the persistent public journal,
        // rather than being hidden behind a later Java broken-pipe exception.
        if std::env::args().nth(1).as_deref() == Some("--open") {
            let message = error.to_string().chars().take(512).collect::<String>();
            let mut output = io::stdout().lock();
            let _ = output.write_all(&((message.len() + 1) as u16).to_be_bytes());
            let _ = output.write_all(&[0x7f]);
            let _ = output.write_all(message.as_bytes());
            let _ = output.flush();
        }
        eprintln!("Windows HCI helper: {error}");
        std::process::exit(1);
    }
}
#[cfg(not(target_os = "windows"))]
fn main() {
    eprintln!("Windows HCI helper requires Windows");
    std::process::exit(1);
}
