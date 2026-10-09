package dev.applewatchandroid.bridge;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Per-user Windows DPAPI records; private bytes never enter stdout or journals. */
final class WindowsSecretStore implements DesktopSecretStore {
    private final Path directory, helper;
    WindowsSecretStore(Path directory, Path helper) throws IOException {
        this.directory=directory; this.helper=helper;
        Files.createDirectories(directory); WindowsFiles.restrict(directory);
    }
    public boolean contains(String name) { return Files.isRegularFile(directory.resolve(name),LinkOption.NOFOLLOW_LINKS); }
    private byte[] crypt(String mode,byte[] bytes) throws Exception {
        Process process=new ProcessBuilder(helper.toString(),mode).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Drain output while sending: a sealed record can exceed both pipe buffers.
        var result=new java.util.concurrent.FutureTask<byte[]>(()->process.getInputStream().readNBytes(512*1024+1));
        Thread reader=new Thread(result,"windows-private-record"); reader.setDaemon(true);reader.start();
        try {
            try(OutputStream input=process.getOutputStream()){input.write(bytes);}
            if(!process.waitFor(10,TimeUnit.SECONDS)||process.exitValue()!=0) throw new IOException("Windows DPAPI operation failed");
            byte[] output=result.get(2,TimeUnit.SECONDS);
            if(output.length==0||output.length>512*1024){Arrays.fill(output,(byte)0);throw new IOException("Invalid Windows protected record");}
            return output;
        } finally { process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); }
    }
    private static void name(String name) {if(!name.matches("[a-z-]+\\.sealed"))throw new IllegalArgumentException("Invalid record name");}
    public byte[] load(String name) throws Exception {
        name(name);Path path=directory.resolve(name);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>512*1024)throw new IOException("Invalid protected state file");
        byte[] record=Files.readAllBytes(path);
        try{
            byte[] bound=crypt("--unprotect",record);
            try {
                ByteBuffer buffer=ByteBuffer.wrap(bound); int length=buffer.getInt();
                if(length!=name.length()||length>buffer.remaining())throw new IOException("Protected record name mismatch");
                byte[] label=new byte[length];buffer.get(label);
                if(!name.equals(new String(label,StandardCharsets.US_ASCII)))throw new IOException("Protected record name mismatch");
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
            }finally{Arrays.fill(bound,(byte)0);}
        }finally{Arrays.fill(record,(byte)0);}
    }
    public void store(String name,byte[] bytes) throws Exception {
        name(name);if(bytes.length>256*1024)throw new IllegalArgumentException("Record too large");
        // Bind the filename inside DPAPI to reject swapping two protected records.
        byte[] label=name.getBytes(StandardCharsets.US_ASCII);
        byte[] bound=ByteBuffer.allocate(4+label.length+bytes.length).putInt(label.length).put(label).put(bytes).array();
        byte[] encrypted=null;
        try {
            encrypted=crypt("--protect",bound);atomicWrite(directory.resolve(name),encrypted);
            byte[] verified=load(name);
            try{if(!java.security.MessageDigest.isEqual(bytes,verified))throw new IOException("Record readback mismatch");}finally{Arrays.fill(verified,(byte)0);}
        }finally{Arrays.fill(bound,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
    }
    private void atomicWrite(Path path,byte[] bytes) throws Exception {
        if(Files.isSymbolicLink(path))throw new IOException("Linked record path");
        Path pending=Files.createTempFile(directory,".pending-",".tmp");
        try {
            WindowsFiles.restrict(pending);
            try(FileChannel channel=FileChannel.open(pending,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(pending,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
    }
    public String ownerConfirmedPair() throws Exception {
        if(!contains("owner-confirmed.sealed"))return null;
        byte[] bytes=load("owner-confirmed.sealed");try{String id=new String(bytes,StandardCharsets.US_ASCII);NtkFacePayloadCodec.uuid(id);return id;}finally{Arrays.fill(bytes,(byte)0);}
    }
    public void publishPair(PairingSessionRecord pair) throws Exception {
        String id=OperationalSessionPolicy.pairingId(pair);if(id==null)return;NtkFacePayloadCodec.uuid(id);
        String product=pair.peerProductType();if(product==null||!product.matches("Watch[0-9]{1,3},[0-9]{1,3}"))product="";
        boolean activated=pair.state().wireValue()>=PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue();
        boolean verified=OperationalSessionPolicy.mayUseOperationalMode(pair.state(),id,null,false,pair.hasObservedSetupEvidence());
        String json="{\"pairId\":\""+id+"\",\"productType\":\""+product+"\",\"activationConfirmed\":"+activated+",\"protocolVerified\":"+verified+",\"ownerConfirmed\":"+(activated&&id.equals(ownerConfirmedPair()))+"}";
        atomicWrite(directory.resolve("pair-public.json"),json.getBytes(StandardCharsets.UTF_8));
    }
}
