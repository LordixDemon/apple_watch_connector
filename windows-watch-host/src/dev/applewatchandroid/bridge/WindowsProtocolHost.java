package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Uses the working portable protocol chain with a Windows USB HCI controller. */
public final class WindowsProtocolHost {
    private static final DesktopProtocolHost.Platform PLATFORM = new DesktopProtocolHost.Platform() {
        @Override public String platformTag() { return "WINDOWS"; }
        @Override public String cleanupThreadName() { return "windows-watch-cleanup"; }
        @Override public void restrictDirectory(Path state) throws IOException {
            WindowsFiles.restrict(state);
        }
        @Override public boolean hasExistingIdentityForConfirm(Path state) {
            return Files.isRegularFile(state.resolve("identity/pairing.sealed"), LinkOption.NOFOLLOW_LINKS);
        }
        @Override public DesktopSecretStore openSecretStore(Path identityDir, Path helper) throws Exception {
            return new WindowsSecretStore(identityDir, helper);
        }
        @Override public BluetoothController openController(Path helper) {
            return new WindowsBluetoothController(helper);
        }
        @Override public RootWifiNetworkReader.Reader wifiReader() {
            return () -> { throw new IOException("Windows Wi-Fi provisioning is unavailable"); };
        }
    };

    public static void main(String[] args) throws Exception {
        DesktopProtocolHost.runMain(args, PLATFORM);
    }
}
