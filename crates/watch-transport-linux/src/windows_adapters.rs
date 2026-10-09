//! USB identity selection. Descriptor enumeration does not capture the controller.
#![cfg_attr(not(target_os = "windows"), allow(dead_code))]
use crate::windows_usb_backend::UsbBackend;
use serde_json::{Value, json};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
#[derive(Clone)]
pub(crate) struct Adapter {
    pub(crate) id: String,
    pub(crate) index: u16,
    value: Value,
}
#[derive(Default)]
struct Inventory {
    adapters: Vec<Adapter>,
    loading: bool,
    loaded: bool,
    generation: u64,
    updated: Option<Instant>,
    error: Option<String>,
    raw_hci_available: bool,
}
impl Inventory {
    fn complete(&mut self, generation: u64, result: Result<Value, String>) {
        if self.generation != generation {
            return;
        }
        self.loading = false;
        self.loaded = true;
        self.updated = Some(Instant::now());
        let parsed = result.and_then(|value| {
            let values = value["adapters"]
                .as_array()
                .ok_or("ADAPTER_DISCOVERY_FAILED")?;
            if values.len() > 64 {
                return Err("ADAPTER_DISCOVERY_FAILED".into());
            }
            let mut adapters = Vec::new();
            for (index, value) in values.iter().enumerate() {
                let id = value["id"].as_str().ok_or("ADAPTER_DISCOVERY_FAILED")?;
                if UsbBackend::from_identity(id).is_none()
                    || adapters.iter().any(|adapter: &Adapter| adapter.id == id)
                {
                    return Err("ADAPTER_DISCOVERY_FAILED".into());
                }
                adapters.push(Adapter {
                    id: id.into(),
                    index: index as u16,
                    value: value.clone(),
                });
            }
            Ok((adapters, value["rawHciAvailable"] == true))
        });
        match parsed {
            Ok((adapters, available)) => {
                self.adapters = adapters;
                self.raw_hci_available = available;
                self.error = None;
            }
            Err(error) => {
                self.adapters.clear();
                self.raw_hci_available = false;
                self.error = Some(error);
            }
        }
    }
}

fn discover() -> Result<Value, String> {
    #[cfg(target_os = "windows")]
    {
        crate::usb::inventory()
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err("Windows USB inventory is unavailable on this platform".into())
    }
}
pub(crate) struct AdapterRegistry {
    inventory: Arc<Mutex<Inventory>>,
    directory: PathBuf,
    selected: Option<String>,
}
impl AdapterRegistry {
    pub(crate) fn new(directory: PathBuf) -> Self {
        let selected = std::fs::read(directory.join("windows-usb-adapter.json"))
            .ok()
            .filter(|b| b.len() < 4096)
            .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
            .filter(|v| v["schema"] == 1)
            .and_then(|v| {
                v["id"]
                    .as_str()
                    .filter(|id| UsbBackend::from_identity(id).is_some())
                    .map(str::to_owned)
            });
        Self {
            inventory: Arc::new(Mutex::new(Inventory::default())),
            directory,
            selected,
        }
    }
    pub(crate) fn refresh(&self, force: bool, leased: bool) {
        if leased {
            return;
        }
        let mut inventory = self.inventory.lock().unwrap();
        if inventory.loading
            || !force
                && inventory
                    .updated
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(2))
        {
            return;
        }
        inventory.loading = true;
        let generation = inventory.generation;
        let state = self.inventory.clone();
        std::thread::spawn(move || {
            let result = discover();
            let mut inventory = state.lock().unwrap();
            inventory.complete(generation, result);
        });
    }
    pub(crate) fn freeze(&self) {
        let mut inventory = self.inventory.lock().unwrap();
        inventory.generation += 1;
        inventory.loading = false;
        inventory.updated = Some(Instant::now());
    }
    pub(crate) fn invalidate(&self) {
        self.inventory.lock().unwrap().updated = None;
    }
    // GUI polling reads the last background result. It never opens a USB
    // context or enumerates the controller on the Flutter command thread.
    pub(crate) fn raw_hci_available(&self) -> bool {
        self.inventory.lock().unwrap().raw_hci_available
    }
    pub(crate) fn select(&mut self, id: &str) -> Result<(), String> {
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
        std::fs::create_dir_all(&self.directory).map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        let temporary = self
            .directory
            .join(format!(".usb-adapter-{}.tmp", std::process::id()));
        use std::io::Write;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&temporary)
            .map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        file.write_all(json!({"schema":1,"id":id}).to_string().as_bytes())
            .and_then(|_| file.sync_all())
            .map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        drop(file);
        std::fs::rename(temporary, self.directory.join("windows-usb-adapter.json"))
            .map_err(|_| "ADAPTER_PREFERENCE_FAILED")?;
        self.selected = Some(id.into());
        Ok(())
    }
    pub(crate) fn snapshot(&mut self, leased: bool) -> Value {
        self.refresh(false, leased);
        let inventory = self.inventory.lock().unwrap();
        let status = if !inventory.loaded {
            "LOADING"
        } else if inventory.error.is_some() {
            "ERROR"
        } else if self.selected.is_none() {
            "REQUIRED"
        } else if !inventory
            .adapters
            .iter()
            .any(|a| Some(&a.id) == self.selected.as_ref())
        {
            "MISSING"
        } else {
            "READY"
        };
        json!({"bluetoothAdapters":inventory.adapters.iter().map(|a|a.value.clone()).collect::<Vec<_>>(),"selectedAdapterId":self.selected,
            "adapterSelectionStatus":status,"adapterSelectionBusy":leased,"adapterInventoryRefreshing":inventory.loading,"adapterSelectionError":inventory.error})
    }
    pub(crate) fn resolve(&mut self) -> Result<Adapter, String> {
        if self.snapshot(false)["adapterSelectionStatus"] != "READY" {
            return Err("ADAPTER_REQUIRED".into());
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
    const FIRST: &str = "winusb:1:2.3:1234:abcd";
    const SECOND: &str = "winusb:1:4:1234:abcd";
    fn result(id: Option<&str>, available: bool) -> Result<Value, String> {
        Ok(
            json!({"adapters":id.into_iter().map(|id|json!({"id":id,"address":id,"name":"Test controller","index":0,"controller":"usb0"})).collect::<Vec<_>>(),"rawHciAvailable":available}),
        )
    }

    #[test]
    fn refresh_observes_plug_unplug_and_driver_failure_without_restart() {
        let mut inventory = Inventory::default();
        assert!(!inventory.raw_hci_available);
        inventory.complete(0, result(Some(FIRST), true));
        assert!(inventory.raw_hci_available);
        inventory.complete(0, result(None, false));
        assert!(!inventory.raw_hci_available && inventory.adapters.is_empty());
        inventory.complete(0, result(Some(FIRST), true));
        assert!(inventory.raw_hci_available);
        inventory.complete(0, Err("USB enumeration failed".into()));
        assert!(!inventory.raw_hci_available && inventory.adapters.is_empty());
    }

    #[test]
    fn late_inventory_cannot_change_the_leased_controller_or_its_capability() {
        let mut inventory = Inventory::default();
        inventory.complete(0, result(Some(FIRST), true));
        let registry = AdapterRegistry {
            inventory: Arc::new(Mutex::new(inventory)),
            directory: PathBuf::new(),
            selected: Some(FIRST.into()),
        };
        registry.freeze();
        registry
            .inventory
            .lock()
            .unwrap()
            .complete(0, result(None, false));
        assert!(registry.raw_hci_available());
        assert_eq!(registry.inventory.lock().unwrap().adapters[0].id, FIRST);
    }

    #[test]
    fn another_controller_never_replaces_the_saved_selection() {
        let mut inventory = Inventory::default();
        inventory.complete(0, result(Some(FIRST), true));
        let mut registry = AdapterRegistry {
            inventory: Arc::new(Mutex::new(inventory)),
            directory: PathBuf::new(),
            selected: Some(FIRST.into()),
        };
        assert_eq!(registry.snapshot(true)["adapterSelectionStatus"], "READY");
        registry
            .inventory
            .lock()
            .unwrap()
            .complete(0, result(Some(SECOND), true));
        assert_eq!(registry.snapshot(true)["adapterSelectionStatus"], "MISSING");
        assert_eq!(registry.snapshot(true)["selectedAdapterId"], FIRST);
        registry
            .inventory
            .lock()
            .unwrap()
            .complete(0, result(Some(FIRST), true));
        assert_eq!(registry.snapshot(true)["adapterSelectionStatus"], "READY");
    }

    #[test]
    fn malformed_inventory_cannot_enable_pairing() {
        let mut inventory = Inventory::default();
        inventory.complete(
            0,
            Ok(json!({"rawHciAvailable":true,"adapters":[{"id":"not a USB identity"}]})),
        );
        assert!(!inventory.raw_hci_available);
        assert!(inventory.error.is_some());
    }
}
