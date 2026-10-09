package dev.applewatchandroid.bridge;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** A real child must be reaped even when the command pipe fails during close. */
public final class LinuxBluetoothControllerTest {
    public static void main(String[] args) throws Exception {
        LinuxBluetoothController.verifyAdapterAddress("aa:bb:cc:dd:ee:01", "s \"AA:BB:CC:DD:EE:01\"\n");
        for (String report : new String[]{"s \"AA:BB:CC:DD:EE:02\"", "", "s \"AA:BB:CC:DD:EE:01\" trailing"}) {
            boolean rejected = false;
            try { LinuxBluetoothController.verifyAdapterAddress("aa:bb:cc:dd:ee:01", report); }
            catch (IOException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Wrong/stale adapter was accepted");
        }
        Process child = new ProcessBuilder("/bin/sleep", "30").start();
        LinuxBluetoothController controller = new LinuxBluetoothController(Path.of("unused"));
        try {
            // Inject only the owned OS resources; this test never opens Bluetooth.
            var processField = LinuxBluetoothController.class.getDeclaredField("process");
            processField.setAccessible(true);
            processField.set(controller, child);
            var outputField = LinuxBluetoothController.class.getDeclaredField("output");
            outputField.setAccessible(true);
            outputField.set(controller, new DataOutputStream(new OutputStream() {
                @Override public void write(int value) { }
                @Override public void close() throws IOException { throw new IOException("Synthetic pipe failure"); }
            }));
            boolean failed = false;
            try { controller.close(); } catch (IOException expected) { failed = true; }
            if (!failed || child.isAlive()) throw new AssertionError("Pipe failure left a broker alive");
            controller.close();
            System.out.println("Linux controller cleanup: PASS");
        } finally { child.destroyForcibly(); child.waitFor(); }
    }
}
