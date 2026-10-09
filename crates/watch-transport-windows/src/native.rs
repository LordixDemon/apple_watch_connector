use super::{Command, discovery_id, setup_data};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
        mpsc::{Receiver, Sender, SyncSender},
    },
    time::{Duration, Instant},
};
use watch_core::transport::TransportEvent;
use windows::{
    Devices::{
        Bluetooth::{
            Advertisement::{
                BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher,
                BluetoothLEAdvertisementWatcherStoppedEventArgs, BluetoothLEScanningMode,
            },
            BluetoothAdapter, BluetoothAddressType, BluetoothCacheMode, BluetoothConnectionStatus,
            BluetoothError, BluetoothLEDevice,
            GenericAttributeProfile::{
                GattCommunicationStatus, GattDeviceServicesResult, GattSession,
            },
        },
        Radios::{Radio, RadioState},
    },
    Foundation::TypedEventHandler,
    Storage::Streams::DataReader,
    Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize},
    core::{Error, HRESULT, Result, RuntimeType},
};
use windows_future::{AsyncStatus, IAsyncOperation};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
const DEVICE_EXPIRY: Duration = Duration::from_secs(20);
const POLL: Duration = Duration::from_millis(50);
type Inventory = Arc<Mutex<BTreeMap<String, Observation>>>;

struct Observation {
    address: u64,
    address_type: BluetoothAddressType,
    seen: Instant,
    published: Instant,
}

fn current(generation: &AtomicU64, expected: u64) -> bool {
    generation.load(Ordering::SeqCst) == expected
}

/// Only the dedicated MTA worker waits. Cancellation invalidates operations
/// immediately, so the Flutter/FFI thread never blocks on Bluetooth or consent.
fn wait<T: RuntimeType + 'static>(
    operation: IAsyncOperation<T>,
    generation: &AtomicU64,
    expected: u64,
    deadline: Instant,
) -> Result<T> {
    loop {
        if !current(generation, expected) {
            let _ = operation.Cancel();
            return Err(Error::from_hresult(HRESULT(0x80004004u32 as i32)));
        }
        if Instant::now() >= deadline {
            let _ = operation.Cancel();
            return Err(Error::new(
                HRESULT::from_win32(1460),
                "Windows Bluetooth operation timed out",
            ));
        }
        if operation.Status()? != AsyncStatus::Started {
            return operation.GetResults();
        }
        std::thread::sleep(POLL);
    }
}

struct Scanner {
    watcher: BluetoothLEAdvertisementWatcher,
    received: Option<i64>,
    stopped: Option<i64>,
}
impl Drop for Scanner {
    fn drop(&mut self) {
        if let Some(token) = self.received.take() {
            let _ = self.watcher.RemoveReceived(token);
        }
        if let Some(token) = self.stopped.take() {
            let _ = self.watcher.RemoveStopped(token);
        }
        let _ = self.watcher.Stop();
    }
}
impl Scanner {
    fn start(
        epoch: u64,
        expected: u64,
        generation: Arc<AtomicU64>,
        inventory: Inventory,
        send: Sender<TransportEvent>,
        discoveries: SyncSender<TransportEvent>,
    ) -> Result<Self> {
        let watcher = BluetoothLEAdvertisementWatcher::new()?;
        watcher.SetScanningMode(BluetoothLEScanningMode::Active)?;
        let mut scanner = Self {
            watcher,
            received: None,
            stopped: None,
        };
        let scan_generation = generation.clone();
        scanner.received = Some(scanner.watcher.Received(&TypedEventHandler::<
            BluetoothLEAdvertisementWatcher,
            BluetoothLEAdvertisementReceivedEventArgs,
        >::new(move |_, args| {
            if !current(&scan_generation, expected) {
                return Ok(());
            }
            let Some(args) = args.as_ref() else {
                return Ok(());
            };
            let advertisement = args.Advertisement()?;
            let sections = advertisement.GetSectionsByType(0x16)?;
            let mut setup = None;
            for index in 0..sections.Size()?.min(32) {
                let data = sections.GetAt(index)?.Data()?;
                let length = data.Length()?;
                if length > 66 {
                    continue;
                }
                let mut bytes = vec![0; length as usize];
                DataReader::FromBuffer(&data)?.ReadBytes(&mut bytes)?;
                if let Some(verified) = setup_data(&bytes) {
                    setup = Some(verified);
                    break;
                }
            }
            let Some(setup) = setup else {
                return Ok(());
            };
            let address = args.BluetoothAddress()?;
            let address_type = args.BluetoothAddressType()?;
            if address > 0xffffffffffff
                || !matches!(
                    address_type,
                    BluetoothAddressType::Public | BluetoothAddressType::Random
                )
            {
                return Ok(());
            }
            let id = discovery_id(address, address_type.0);
            let now = Instant::now();
            let Ok(mut devices) = inventory.lock() else {
                return Ok(());
            };
            devices.retain(|_, observed| now.duration_since(observed.seen) < DEVICE_EXPIRY);
            if let Some(observed) = devices.get_mut(&id) {
                observed.seen = now;
                if now.duration_since(observed.published) < Duration::from_millis(500) {
                    return Ok(());
                }
                observed.published = now;
            } else {
                if devices.len() >= 64 {
                    return Ok(());
                }
                devices.insert(
                    id.clone(),
                    Observation {
                        address,
                        address_type,
                        seen: now,
                        published: now,
                    },
                );
            }
            drop(devices);
            // Bounded queue: dropped advertisements are refreshed by later packets.
            let _ = discoveries.try_send(TransportEvent::Discovered {
                epoch,
                id,
                name: advertisement
                    .LocalName()?
                    .to_string()
                    .chars()
                    .take(96)
                    .collect(),
                rssi: args.RawSignalStrengthInDBm()?,
                apple: false,
                setup,
            });
            Ok(())
        }))?);
        scanner.stopped = Some(scanner.watcher.Stopped(&TypedEventHandler::<
            BluetoothLEAdvertisementWatcher,
            BluetoothLEAdvertisementWatcherStoppedEventArgs,
        >::new(move |_, args| {
            if !current(&generation, expected) {
                return Ok(());
            }
            let Some(args) = args.as_ref() else {
                return Ok(());
            };
            let error = args.Error()?;
            if error == BluetoothError::Success {
                return Ok(());
            }
            let state = match error {
                BluetoothError::RadioNotAvailable | BluetoothError::DisabledByUser => "POWERED_OFF",
                BluetoothError::DisabledByPolicy | BluetoothError::ConsentRequired => {
                    "UNAUTHORIZED"
                }
                BluetoothError::NotSupported | BluetoothError::TransportNotSupported => {
                    "UNSUPPORTED"
                }
                _ => "UNKNOWN",
            };
            if state != "UNKNOWN" {
                let _ = send.send(TransportEvent::Adapter {
                    state: state.into(),
                });
            }
            let _ = send.send(TransportEvent::Failed {
                epoch,
                message: format!("Windows BLE scan stopped ({})", error.0),
            });
            Ok(())
        }))?);
        scanner.watcher.Start()?;
        Ok(scanner)
    }
}

struct Link {
    epoch: u64,
    generation: u64,
    id: String,
    device: BluetoothLEDevice,
    session: Option<GattSession>,
    discovery: Option<IAsyncOperation<GattDeviceServicesResult>>,
    connected: bool,
    deadline: Instant,
}
impl Drop for Link {
    fn drop(&mut self) {
        if let Some(operation) = self.discovery.take() {
            let _ = operation.Cancel();
            if let Ok(result) = operation.GetResults() {
                close_services(&result);
            }
        }
        if let Some(session) = &self.session {
            let _ = session.SetMaintainConnection(false);
            let _ = session.Close();
        }
        let _ = self.device.Close();
    }
}
impl Link {
    fn open(
        epoch: u64,
        expected: u64,
        id: String,
        inventory: &Inventory,
        generation: &AtomicU64,
    ) -> Result<Self> {
        let (address, address_type) = inventory
            .lock()
            .ok()
            .and_then(|devices| {
                devices
                    .get(&id)
                    .filter(|observed| observed.seen.elapsed() < DEVICE_EXPIRY)
                    .map(|observed| (observed.address, observed.address_type))
            })
            .ok_or_else(|| {
                Error::new(
                    HRESULT(0x80070057u32 as i32),
                    "The Watch advertisement expired; scan again",
                )
            })?;
        let deadline = Instant::now() + CONNECT_TIMEOUT;
        let device = wait(
            BluetoothLEDevice::FromBluetoothAddressWithBluetoothAddressTypeAsync(
                address,
                address_type,
            )?,
            generation,
            expected,
            deadline,
        )?;
        let mut link = Self {
            epoch,
            generation: expected,
            id,
            device,
            session: None,
            discovery: None,
            connected: false,
            deadline,
        };
        let session = wait(
            GattSession::FromDeviceIdAsync(&link.device.BluetoothDeviceId()?)?,
            generation,
            expected,
            deadline,
        )?;
        link.session = Some(session);
        // Creating BluetoothLEDevice alone is not a connection. MaintainConnection
        // requests the actual BLE link; only ConnectionStatus confirms success.
        link.session.as_ref().unwrap().SetMaintainConnection(true)?;
        // An uncached request also makes Windows initiate connection immediately,
        // instead of only maintaining a deferred connection interest.
        link.discovery = Some(
            link.device
                .GetGattServicesWithCacheModeAsync(BluetoothCacheMode::Uncached)?,
        );
        Ok(link)
    }
    fn poll(&mut self, send: &Sender<TransportEvent>) -> Result<bool> {
        let connected = self.device.ConnectionStatus()? == BluetoothConnectionStatus::Connected;
        if let Some(operation) = &self.discovery
            && operation.Status()? != AsyncStatus::Started
        {
            let operation = self.discovery.take().unwrap();
            let result = operation.GetResults();
            match result {
                Ok(result) => {
                    close_services(&result);
                    let status = result.Status()?;
                    if !connected && status != GattCommunicationStatus::Success {
                        return Err(Error::new(
                            HRESULT::from_win32(1167),
                            format!(
                                "Windows could not reach the Watch's BLE services ({})",
                                status.0
                            ),
                        ));
                    }
                }
                Err(error) if !connected => return Err(error),
                _ => {}
            }
        }
        if connected && !self.connected {
            self.connected = true;
            let _ = send.send(TransportEvent::Connected {
                epoch: self.epoch,
                id: self.id.clone(),
            });
        } else if !connected && self.connected {
            let _ = send.send(TransportEvent::Disconnected {
                epoch: self.epoch,
                id: self.id.clone(),
            });
            return Ok(false);
        } else if !connected && Instant::now() >= self.deadline {
            return Err(Error::new(
                HRESULT::from_win32(1460),
                "Windows BLE connection timed out",
            ));
        }
        Ok(true)
    }
}

fn close_services(result: &GattDeviceServicesResult) {
    if let Ok(services) = result.Services()
        && let Ok(count) = services.Size()
    {
        for index in 0..count {
            if let Ok(service) = services.GetAt(index) {
                let _ = service.Close();
            }
        }
    }
}

struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe {
            RoUninitialize();
        }
    }
}

fn fail(send: &Sender<TransportEvent>, epoch: u64, error: &Error) {
    if error.code() == HRESULT(0x80070005u32 as i32) {
        let _ = send.send(TransportEvent::Adapter {
            state: "UNAUTHORIZED".into(),
        });
    }
    let _ = send.send(TransportEvent::Failed {
        epoch,
        message: error.to_string(),
    });
}

fn radio_state(radio: &Radio) -> Result<&'static str> {
    Ok(match radio.State()? {
        RadioState::On => "POWERED_ON",
        RadioState::Off => "POWERED_OFF",
        RadioState::Disabled => "UNAUTHORIZED",
        _ => "UNKNOWN",
    })
}

pub(super) fn run(
    requests: Receiver<Command>,
    send: Sender<TransportEvent>,
    discoveries: SyncSender<TransportEvent>,
    generation: Arc<AtomicU64>,
) {
    if let Err(error) = unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
        fail(&send, 0, &error);
        let _ = send.send(TransportEvent::Adapter {
            state: "UNSUPPORTED".into(),
        });
        return;
    }
    let _apartment = Apartment;
    let inventory: Inventory = Arc::new(Mutex::new(BTreeMap::new()));
    let mut scanner = None;
    let mut link: Option<Link> = None;
    let mut radio: Option<Radio> = None;
    let mut last_radio_state = "UNKNOWN";
    loop {
        match requests.recv_timeout(POLL) {
            Ok(Command::Shutdown) | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
            Ok(Command::StopScan) => {
                scanner = None;
            }
            Ok(Command::Disconnect { epoch, id }) => {
                scanner = None;
                link = None;
                let _ = send.send(TransportEvent::Disconnected { epoch, id });
            }
            Ok(Command::Scan {
                epoch,
                generation: expected,
            }) => {
                scanner = None;
                link = None;
                if !current(&generation, expected) {
                    continue;
                }
                if let Ok(mut devices) = inventory.lock() {
                    devices.clear();
                }
                let result = (|| -> Result<Scanner> {
                    let deadline = Instant::now() + Duration::from_secs(10);
                    let adapter = wait(
                        BluetoothAdapter::GetDefaultAsync()?,
                        &generation,
                        expected,
                        deadline,
                    )
                    .inspect_err(|error| {
                        if error.code() == HRESULT(0x80004003u32 as i32)
                            && current(&generation, expected)
                        {
                            let _ = send.send(TransportEvent::Adapter {
                                state: "UNSUPPORTED".into(),
                            });
                        }
                    })?;
                    if !adapter.IsLowEnergySupported()? {
                        let _ = send.send(TransportEvent::Adapter {
                            state: "UNSUPPORTED".into(),
                        });
                        return Err(Error::new(
                            HRESULT(0x80004001u32 as i32),
                            "Windows adapter does not support BLE",
                        ));
                    }
                    let selected_radio =
                        wait(adapter.GetRadioAsync()?, &generation, expected, deadline)?;
                    last_radio_state = radio_state(&selected_radio)?;
                    let _ = send.send(TransportEvent::Adapter {
                        state: last_radio_state.into(),
                    });
                    radio = Some(selected_radio);
                    if last_radio_state != "POWERED_ON" {
                        return Err(Error::new(
                            HRESULT(0x80004005u32 as i32),
                            "Turn on Bluetooth in Windows Settings",
                        ));
                    }
                    Scanner::start(
                        epoch,
                        expected,
                        generation.clone(),
                        inventory.clone(),
                        send.clone(),
                        discoveries.clone(),
                    )
                })();
                match result {
                    Ok(value) if current(&generation, expected) => {
                        scanner = Some(value);
                    }
                    Err(error) if current(&generation, expected) => {
                        fail(&send, epoch, &error);
                    }
                    _ => {}
                }
            }
            Ok(Command::Connect {
                epoch,
                generation: expected,
                id,
            }) => {
                scanner = None;
                link = None;
                if !current(&generation, expected) {
                    continue;
                }
                match Link::open(epoch, expected, id.clone(), &inventory, &generation) {
                    Ok(value) if current(&generation, expected) => {
                        link = Some(value);
                    }
                    Err(error) if current(&generation, expected) => {
                        fail(&send, epoch, &error);
                        let _ = send.send(TransportEvent::Disconnected { epoch, id });
                    }
                    _ => {}
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
        }
        if let Some(selected_radio) = &radio {
            let state = radio_state(selected_radio).unwrap_or("UNSUPPORTED");
            if state != last_radio_state {
                last_radio_state = state;
                let _ = send.send(TransportEvent::Adapter {
                    state: state.into(),
                });
                if state != "POWERED_ON" {
                    scanner = None;
                    link = None;
                    generation.fetch_add(1, Ordering::SeqCst);
                }
            }
        }
        if let Some(active) = &mut link {
            if !current(&generation, active.generation) {
                link = None;
                continue;
            }
            match active.poll(&send) {
                Ok(true) => {}
                Ok(false) => {
                    link = None;
                }
                Err(error) => {
                    fail(&send, active.epoch, &error);
                    let _ = send.send(TransportEvent::Disconnected {
                        epoch: active.epoch,
                        id: active.id.clone(),
                    });
                    link = None;
                }
            }
        }
    }
    // WinRT objects are closed before uninitializing the worker's apartment.
    drop(scanner);
    drop(link);
    drop(radio);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cancelled_attempt_cannot_consume_even_a_completed_result() {
        let generation = AtomicU64::new(2);
        let result = wait(
            IAsyncOperation::<u32>::ready(Ok(7)),
            &generation,
            1,
            Instant::now() + Duration::from_secs(1),
        );
        assert_eq!(result.unwrap_err().code(), HRESULT(0x80004004u32 as i32));
    }

    #[test]
    fn deadline_and_platform_errors_are_preserved() {
        let generation = AtomicU64::new(1);
        let result = wait(
            IAsyncOperation::<u32>::ready(Ok(7)),
            &generation,
            1,
            Instant::now(),
        );
        assert_eq!(result.unwrap_err().code(), HRESULT::from_win32(1460));
        let denied = HRESULT(0x80070005u32 as i32);
        let result = wait(
            IAsyncOperation::<u32>::ready(Err(Error::from_hresult(denied))),
            &generation,
            1,
            Instant::now() + Duration::from_secs(1),
        );
        assert_eq!(result.unwrap_err().code(), denied);
    }

    #[test]
    fn slow_operation_is_cancelled_without_waiting_for_its_completion() {
        let generation = Arc::new(AtomicU64::new(1));
        let operation = IAsyncOperation::<u32>::spawn(|| {
            std::thread::sleep(Duration::from_millis(500));
            Ok(7)
        });
        let cancel = generation.clone();
        let cancellation = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(10));
            cancel.store(2, Ordering::SeqCst);
        });
        let started = Instant::now();
        let result = wait(operation, &generation, 1, started + Duration::from_secs(2));
        cancellation.join().unwrap();
        assert_eq!(result.unwrap_err().code(), HRESULT(0x80004004u32 as i32));
        assert!(started.elapsed() < Duration::from_millis(400));
    }
}
