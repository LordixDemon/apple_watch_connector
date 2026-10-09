package dev.applewatchandroid.bridge;
import java.nio.file.*;
import java.util.*;

public final class WindowsSecretStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("watch-dpapi-test-");Path helper=Path.of(System.getProperty("watch.test.helper"));
        try {
            WindowsSecretStore store=new WindowsSecretStore(directory,helper);byte[] secret=new byte[1024*100];new java.security.SecureRandom().nextBytes(secret);
            store.store("bond.sealed",secret);if(!Arrays.equals(secret,store.load("bond.sealed")))throw new AssertionError("DPAPI roundtrip");
            if(Arrays.equals(secret,Files.readAllBytes(directory.resolve("bond.sealed"))))throw new AssertionError("Plaintext record");
            store.store("pairing.sealed",new byte[]{1,2,3});Files.copy(directory.resolve("bond.sealed"),directory.resolve("pairing.sealed"),StandardCopyOption.REPLACE_EXISTING);
            try {store.load("pairing.sealed");throw new AssertionError("Swapped records accepted");}catch(java.io.IOException expected){}
            byte[] ciphertext=Files.readAllBytes(directory.resolve("bond.sealed"));ciphertext[ciphertext.length-1]^=1;Files.write(directory.resolve("bond.sealed"),ciphertext);
            try {store.load("bond.sealed");throw new AssertionError("Tampered record accepted");}catch(java.io.IOException expected){}
            WindowsSecretStore restored=new WindowsSecretStore(directory,helper);restored.store("bond.sealed",secret);
            if(!Arrays.equals(secret,restored.load("bond.sealed")))throw new AssertionError("Restart persistence");
            Arrays.fill(secret,(byte)0);System.out.println("Windows DPAPI persistence: passed");
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
