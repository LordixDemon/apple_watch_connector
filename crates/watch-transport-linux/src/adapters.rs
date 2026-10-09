//! BlueZ inventory and a stable address preference, independent of Watch identity.
use serde_json::{Value, json};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::PathBuf,
    process::{Command, Stdio},
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Debug)]
pub(crate) struct Adapter {
    pub(crate) id: String,
    pub(crate) index: u16,
    name: String,
    powered: bool,
}

pub(crate) fn address(value: &str) -> Option<String> {
    (value.len() == 17
        && value.bytes().enumerate().all(|(i, b)| {
            if i % 3 == 2 {
                b == b':'
            } else {
                b.is_ascii_hexdigit()
            }
        })
        && value != "00:00:00:00:00:00")
        .then(|| value.to_ascii_lowercase())
}

fn parse_inventory(bytes: &[u8]) -> Result<Vec<Adapter>, String> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| "ADAPTER_DISCOVERY_FAILED")?;
    if value["type"] != "a{oa{sa{sv}}}" {
        return Err("ADAPTER_DISCOVERY_FAILED".into());
    }
    let objects = value["data"][0]
        .as_object()
        .ok_or("ADAPTER_DISCOVERY_FAILED")?;
    let mut adapters = Vec::new();
    for (path, interfaces) in objects {
        let Some(index) = path
            .strip_prefix("/org/bluez/hci")
            .and_then(|i| i.parse::<u16>().ok())
            .filter(|i| *i != u16::MAX)
        else {
            continue;
        };
        let properties = &interfaces["org.bluez.Adapter1"];
        let Some(id) = properties["Address"]["data"].as_str().and_then(address) else {
            continue;
        };
        let name = properties["Alias"]["data"]
            .as_str()
            .or(properties["Name"]["data"].as_str())
            .unwrap_or("");
        if adapters.iter().any(|a: &Adapter| a.id == id) {
            return Err("ADAPTER_DISCOVERY_FAILED".into());
        }
        adapters.push(Adapter {
            id,
            index,
            name: name.chars().filter(|c| !c.is_control()).take(120).collect(),
            powered: properties["Powered"]["data"] == true,
        });
        if adapters.len() > 64 {
            return Err("ADAPTER_DISCOVERY_FAILED".into());
        }
    }
    adapters.sort_by_key(|a| a.index);
    Ok(adapters)
}

fn discover() -> Result<Vec<Adapter>, String> {
    let mut child = Command::new("busctl")
        .args([
            "--system",
            "--json=short",
            "--timeout=2",
            "call",
            "org.bluez",
            "/",
            "org.freedesktop.DBus.ObjectManager",
            "GetManagedObjects",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| "ADAPTER_DISCOVERY_FAILED")?;
    let output = child.stdout.take().ok_or("ADAPTER_DISCOVERY_FAILED")?;
    let reader = std::thread::spawn(move || {
        let mut bytes = Vec::new();
        output
            .take(1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map(|_| bytes)
    });
    let deadline = Instant::now() + Duration::from_secs(3);
    let success = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status.success(),
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                let _ = crate::worker::finish_child(&mut child, Duration::ZERO);
                break false;
            }
        }
    };
    let bytes = reader
        .join()
        .map_err(|_| "ADAPTER_DISCOVERY_FAILED")?
        .map_err(|_| "ADAPTER_DISCOVERY_FAILED")?;
    if !success || bytes.len() > 1024 * 1024 {
        return Err("ADAPTER_DISCOVERY_FAILED".into());
    }
    parse_inventory(&bytes)
}

#[derive(Default)]
struct Inventory {
    generation: u64,
    adapters: Vec<Adapter>,
    loaded: bool,
    loading: bool,
    updated: Option<Instant>,
    error: Option<String>,
}

impl Inventory {
    fn complete(&mut self, generation: u64, result: Result<Vec<Adapter>, String>) {
        if self.generation != generation {
            return;
        }
        self.loading = false;
        self.loaded = true;
        self.updated = Some(Instant::now());
        match result {
            Ok(adapters) => {
                self.adapters = adapters;
                self.error = None;
            }
            Err(error) => {
                self.adapters.clear();
                self.error = Some(error);
            }
        }
    }
}

pub(crate) struct AdapterRegistry {
    inventory: Arc<Mutex<Inventory>>,
    selected: Option<String>,
    preference_error: Option<String>,
    directory: PathBuf,
    legacy_index: Option<String>,
}

impl AdapterRegistry {
    pub(crate) fn new(directory: PathBuf) -> Self {
        let path = directory.join("bluetooth-adapter.json");
        let mut error = None;
        let selected = match fs::symlink_metadata(&path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Ok(metadata) if metadata.is_file() && metadata.len() <= 1024 => {
                let value = fs::read(&path)
                    .ok()
                    .and_then(|b| serde_json::from_slice::<Value>(&b).ok());
                let id = value
                    .filter(|v| v["schema"] == 1)
                    .and_then(|v| v["id"].as_str().and_then(address));
                if id.is_none() {
                    error = Some("ADAPTER_PREFERENCE_FAILED".into());
                }
                id
            }
            _ => {
                error = Some("ADAPTER_PREFERENCE_FAILED".into());
                None
            }
        };
        Self {
            inventory: Arc::new(Mutex::new(Inventory::default())),
            selected,
            preference_error: error,
            directory,
            legacy_index: std::env::var("WATCH_HCI_INDEX").ok(),
        }
    }

    pub(crate) fn refresh(&self, force: bool, leased: bool) {
        if leased {
            return;
        }
        let mut state = self.inventory.lock().unwrap();
        if state.loading
            || !force
                && state
                    .updated
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(2))
        {
            return;
        }
        state.loading = true;
        let generation = state.generation;
        let inventory = Arc::clone(&self.inventory);
        std::thread::spawn(move || {
            let result = discover();
            let mut state = inventory.lock().unwrap();
            state.complete(generation, result);
        });
    }

    pub(crate) fn freeze(&self) {
        let mut state = self.inventory.lock().unwrap();
        state.generation += 1;
        state.loading = false;
        state.updated = Some(Instant::now());
    }

    pub(crate) fn invalidate(&self) {
        self.inventory.lock().unwrap().updated = None;
    }

    fn save(&mut self, id: &str) -> Result<(), String> {
        let mut directory = fs::DirBuilder::new();
        directory.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            directory.mode(0o700);
        }
        directory
            .create(&self.directory)
            .map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        if !fs::symlink_metadata(&self.directory).is_ok_and(|m| m.is_dir()) {
            return Err("ADAPTER_PREFERENCE_FAILED".into());
        }
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let temporary = self
            .directory
            .join(format!(".adapter-{}-{nonce}.tmp", std::process::id()));
        let result = (|| -> std::io::Result<()> {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(&temporary)?;
            file.write_all(json!({"schema":1,"id":id}).to_string().as_bytes())?;
            file.sync_all()?;
            fs::rename(&temporary, self.directory.join("bluetooth-adapter.json"))?;
            fs::File::open(&self.directory)?.sync_all()
        })();
        let _ = fs::remove_file(temporary);
        result.map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        self.selected = Some(id.into());
        self.preference_error = None;
        Ok(())
    }

    pub(crate) fn select(&mut self, id: &str) -> Result<(), String> {
        let id = address(id).ok_or("INVALID_REQUEST")?;
        if !self
            .inventory
            .lock()
            .unwrap()
            .adapters
            .iter()
            .any(|a| a.id == id)
        {
            return Err("ADAPTER_UNAVAILABLE".into());
        }
        self.save(&id)
    }

    pub(crate) fn snapshot(&mut self, leased: bool) -> Value {
        self.refresh(false, leased);
        let state = self.inventory.lock().unwrap();
        let adapters = state.adapters.clone();
        let loaded = state.loaded;
        let loading = state.loading;
        let error = state.error.clone();
        drop(state);
        if self.selected.is_none() && loaded && error.is_none() && self.preference_error.is_none() {
            let preferred = if let Some(index) = &self.legacy_index {
                index
                    .parse::<u16>()
                    .ok()
                    .and_then(|i| adapters.iter().find(|a| a.index == i))
            } else if adapters.len() == 1 {
                adapters.first()
            } else {
                None
            };
            if let Some(adapter) = preferred
                && let Err(error) = self.save(&adapter.id)
            {
                self.preference_error = Some(error);
            }
        }
        let selected = adapters
            .iter()
            .find(|a| Some(&a.id) == self.selected.as_ref());
        let status = if !loaded {
            "LOADING"
        } else if error.is_some() || self.preference_error.is_some() {
            "ERROR"
        } else if self.selected.is_none() {
            "REQUIRED"
        } else if selected.is_none() {
            "MISSING"
        } else {
            "READY"
        };
        json!({"bluetoothAdapters":adapters.iter().map(|a| json!({"id":a.id,"address":a.id,"name":a.name,"index":a.index,"controller":format!("hci{}",a.index),"powered":a.powered})).collect::<Vec<_>>(),
            "selectedAdapterId":self.selected,"adapterSelectionStatus":status,"adapterSelectionBusy":leased,
            "adapterInventoryRefreshing":loading,"adapterSelectionError":error.or(self.preference_error.clone())})
    }

    pub(crate) fn resolve(&mut self) -> Result<Adapter, String> {
        let snapshot = self.snapshot(false);
        if snapshot["adapterSelectionStatus"] != "READY" {
            return Err(match snapshot["adapterSelectionStatus"].as_str() {
                Some("LOADING") => "ADAPTER_LOADING",
                Some("REQUIRED") => "ADAPTER_REQUIRED",
                Some("MISSING") => "ADAPTER_UNAVAILABLE",
                _ => "ADAPTER_DISCOVERY_FAILED",
            }
            .into());
        }
        self.inventory
            .lock()
            .unwrap()
            .adapters
            .iter()
            .find(|a| Some(&a.id) == self.selected.as_ref())
            .cloned()
            .ok_or("ADAPTER_UNAVAILABLE".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    fn adapter(index: u16, id: &str) -> Adapter {
        Adapter {
            id: id.into(),
            index,
            name: "USB Bluetooth".into(),
            powered: false,
        }
    }
    fn registry(directory: &Path, adapters: Vec<Adapter>) -> AdapterRegistry {
        let mut registry = AdapterRegistry::new(directory.into());
        registry.legacy_index = None;
        *registry.inventory.lock().unwrap() = Inventory {
            adapters,
            loaded: true,
            updated: Some(Instant::now()),
            ..Inventory::default()
        };
        registry
    }
    fn directory() -> PathBuf {
        std::env::temp_dir().join(format!(
            "watch-adapters-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }
    #[test]
    fn selection_survives_index_changes_and_never_falls_back_to_other_hardware() {
        let dir = directory();
        let first = "aa:bb:cc:dd:ee:01";
        let second = "aa:bb:cc:dd:ee:02";
        let mut original = registry(&dir, vec![adapter(0, first), adapter(1, second)]);
        assert_eq!(original.resolve().unwrap_err(), "ADAPTER_REQUIRED");
        original.select(second).unwrap();
        let mut reopened = registry(&dir, vec![adapter(0, second), adapter(3, first)]);
        assert_eq!(reopened.resolve().unwrap().index, 0);
        reopened.inventory.lock().unwrap().adapters = vec![adapter(0, first)];
        assert_eq!(reopened.resolve().unwrap_err(), "ADAPTER_UNAVAILABLE");
        assert_eq!(reopened.snapshot(false)["selectedAdapterId"], second);
        assert!(reopened.select("not-an-address").is_err());
        assert!(reopened.select("aa:bb:cc:dd:ee:ff").is_err());
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 1);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn single_controller_is_saved_without_a_hci_zero_assumption() {
        let dir = directory();
        let mut registry = registry(&dir, vec![adapter(7, "aa:bb:cc:dd:ee:07")]);
        assert_eq!(registry.resolve().unwrap().index, 7);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(dir.join("bluetooth-adapter.json"))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn bluez_inventory_uses_adapter_interfaces_and_ignores_device_objects() {
        let bytes = br#"{"type":"a{oa{sa{sv}}}","data":[{"/org/bluez/hci4":{"org.bluez.Adapter1":{"Address":{"type":"s","data":"AA:BB:CC:DD:EE:04"},"Alias":{"type":"s","data":"USB"},"Powered":{"type":"b","data":false}}},"/org/bluez/hci4/dev_x":{"org.bluez.Device1":{"Address":{"data":"AA:BB:CC:DD:EE:FF"}}}}]}"#;
        let adapters = parse_inventory(bytes).unwrap();
        assert_eq!(adapters.len(), 1);
        assert_eq!(adapters[0].index, 4);
        assert_eq!(adapters[0].id, "aa:bb:cc:dd:ee:04");
        assert!(parse_inventory(b"{broken").is_err());
    }
    #[test]
    fn corrupt_preferences_require_explicit_reselection() {
        let dir = directory();
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("bluetooth-adapter.json"), b"broken").unwrap();
        let mut registry = registry(&dir, vec![adapter(2, "aa:bb:cc:dd:ee:02")]);
        assert_eq!(registry.snapshot(false)["adapterSelectionStatus"], "ERROR");
        registry.select("aa:bb:cc:dd:ee:02").unwrap();
        assert_eq!(registry.resolve().unwrap().index, 2);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn late_inventory_cannot_remove_the_leased_adapter() {
        let dir = directory();
        let mut registry = registry(&dir, vec![adapter(3, "aa:bb:cc:dd:ee:03")]);
        registry.select("aa:bb:cc:dd:ee:03").unwrap();
        let generation = registry.inventory.lock().unwrap().generation;
        registry.freeze();
        // BlueZ omits an HCI USER controller; an in-flight reply must be fenced.
        registry
            .inventory
            .lock()
            .unwrap()
            .complete(generation, Ok(vec![]));
        assert_eq!(registry.snapshot(true)["adapterSelectionStatus"], "READY");
        assert_eq!(
            registry.snapshot(true)["bluetoothAdapters"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        fs::remove_dir_all(dir).unwrap();
    }
}
