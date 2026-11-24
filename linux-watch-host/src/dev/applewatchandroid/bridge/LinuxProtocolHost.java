package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Uses the working portable protocol chain with a Linux controller, not Android emulation. */
public final class LinuxProtocolHost {
    private static final DesktopProtocolHost.Platform PLATFORM = new DesktopProtocolHost.Platform() {
        @Override public String platformTag() { return "LINUX"; }
        @Override public String cleanupThreadName() { return "linux-watch-cleanup"; }
        @Override public void restrictDirectory(Path state) throws IOException {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        }
        @Override public boolean hasExistingIdentityForConfirm(Path state) {
            return Files.isRegularFile(state.resolve("identity/storage.key"), LinkOption.NOFOLLOW_LINKS);
        }
        @Override public DesktopSecretStore openSecretStore(Path identityDir, Path helper) throws Exception {
            return new LinuxSecretStore(identityDir);
        }
        @Override public BluetoothController openController(Path helper) {
            return new LinuxBluetoothController(helper);
        }
        @Override public RootWifiNetworkReader.Reader wifiReader() {
            return LinuxWifiNetworkReader::currentArchive;
        }
    };

    public static void main(String[] args) throws Exception {
        DesktopProtocolHost.runMain(args, PLATFORM);
    }
}
