fn main() {
    println!("cargo:rerun-if-changed=native/core_bluetooth.m");
    println!("cargo:rerun-if-changed=native/pairing_pipe.m");
    println!("cargo:rerun-if-changed=native/pairing_pipe.h");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        cc::Build::new()
            .file("native/core_bluetooth.m")
            .file("native/pairing_pipe.m")
            .flag("-fobjc-arc")
            .flag("-fblocks")
            .compile("watch_core_bluetooth");
        println!("cargo:rustc-link-lib=framework=Foundation");
        println!("cargo:rustc-link-lib=framework=CoreBluetooth");
        println!("cargo:rustc-link-lib=framework=Security");
    }
}
