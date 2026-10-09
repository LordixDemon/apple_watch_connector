package dev.applewatchandroid.bridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.crypto.spec.SecretKeySpec;

/** Real Android SQLite probes. Test APK only; synthetic defaults never touch the Watch/production DB/keys. */
public final class HealthDefaultsInstrumentation extends Instrumentation {
    private final UUID pair=UUID.randomUUID(),local=UUID.randomUUID(),peer=UUID.randomUUID();
    private final NativeHealthSyncCodec.Identity identity=new NativeHealthSyncCodec.Identity(17,UUID.randomUUID(),UUID.randomUUID());
    private final HealthObservationCipher cipher=new HealthObservationCipher(
            new SecretKeySpec(new byte[32],"AES"),new SecretKeySpec(new byte[32],"HmacSHA256"));
    private File file;
    private HealthDefaultsDatabase database;
    private int passed;
    @FunctionalInterface private interface Probe { void run() throws Exception; }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();int resultCode=Activity.RESULT_CANCELED;
        try {
            // Exclusive random test path. No production-file deletion, cleanup, migration or key access.
            file=new File(getTargetContext().getCacheDir(),"health-defaults-probe-"+UUID.randomUUID()+".db");
            database=new HealthDefaultsDatabase(file,existing->cipher);
            probe("new typed value and authenticated read",()-> {
                var merged=write(dictionary("d",entry("k",1,vi(3,10))));
                require(merged.replaced()==1 && merged.summary().integers()==1);require(value("k").integerValue()==10);
            });
            probe("equal date retains first value",()-> { var r=write(dictionary("d",entry("k",1,vi(3,11))));require(r.retained()==1);require(value("k").integerValue()==10); });
            probe("older date cannot overwrite",()-> { write(dictionary("d",entry("k",0,vi(3,9))));require(value("k").integerValue()==10); });
            probe("newer date replaces",()-> { write(dictionary("d",entry("k",2,vi(3,12))));require(value("k").integerValue()==12); });
            probe("tombstone retained against stale resurrection",()-> {
                write(dictionary("d",entry("k",3)));write(dictionary("d",entry("k",2,vi(3,14))));
                require(kind("k")==NativeHealthDefaults.Kind.TOMBSTONE);
            });
            probe("newer resurrection allowed",()-> { write(dictionary("d",entry("k",4,vi(3,15))));require(value("k").integerValue()==15); });
            probe("duplicate keys preserve native list order",()-> {
                write(dictionary("d",entry("dup",2,vi(3,2)),entry("dup",2,vi(3,3)),entry("dup",1,vi(3,1)),entry("dup",3,vi(3,4))));
                require(value("dup").integerValue()==4);
            });
            probe("all value kinds and raw bytes round trip",()-> {
                write(dictionary("d",entry("double",0,dbl(4,-0.0)),entry("str",0,string(5,"")),entry("bytes",0,blob(6,new byte[]{0,(byte)255})),entry("empty",0,blob(6,new byte[0])),entry("delete",0)));
                var summary=read(record->{ });require(summary.doubles()==1 && summary.strings()==1 && summary.bytes()==2 && summary.tombstones()==1);
                require(Double.doubleToRawLongBits(value("double").doubleValue())==Long.MIN_VALUE);
                require("".equals(value("str").stringValue()));require(Arrays.equals(new byte[]{0,(byte)255},value("bytes").bytesValue()));
            });
            probe("foreign identity cannot read another scope",()-> {
                var other=new NativeHealthSyncCodec.Identity(17,identity.persistent(),UUID.randomUUID());
                require(database.visit(pair,local,peer,other,"23S303",record->{throw new AssertionError();}).records()==0);
            });
            probe("late invalid dictionary rolls back earlier writes",()-> {
                int before=read(record->{ }).records();
                rejects(()->write(dictionary("d",entry("rollback",0,vi(3,1))),join(vi(1,105),string(2,"d"))));
                require(read(record->{ }).records()==before);require(valueOrNull("rollback")==null);
            });
            probe("epoch changes before commit roll back",()-> {
                int[] calls={0};int before=read(record->{ }).records();
                rejects(()->write(()->++calls[0]==1,dictionary("d",entry("epoch",0,vi(3,1)))));
                require(calls[0]==2 && read(record->{ }).records()==before && valueOrNull("epoch")==null);
            });
            probe("failed eligibility does not open storage",()-> {
                File untouched=new File(getTargetContext().getCacheDir(),"health-defaults-probe-"+UUID.randomUUID()+".db");
                try {
                    var unopened=new HealthDefaultsDatabase(untouched,existing->{throw new AssertionError("Keys accessed after hold");});
                    try(var change=change(dictionary("d",entry("k",0)))) {
                        rejects(()->unopened.mirror(pair,local,peer,identity,"23S303",change,()->false));require(!untouched.exists());
                    }
                } finally { SQLiteDatabase.deleteDatabase(untouched); }
            });
            probe("non-speculative and nonzero unknown sequence remain held",()-> {
                for(byte[] flags:new byte[][]{new byte[0],join(vi(8,1),vi(7,1))}) {
                    try(var change=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,
                            join(vi(1,16),flags,blob(4,dictionary("d",entry("held",0)))))) {
                        rejects(()->database.mirror(pair,local,peer,identity,"23S303",change,()->true));
                    }
                }
                require(valueOrNull("held")==null);
            });
            probe("protected and ordinary categories do not collide",()-> {
                try(var change=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,
                        join(vi(1,17),vi(8,1),blob(4,join(vi(1,105),string(2,"d"),blob(3,entry("k",10,vi(3,99)))))))) {
                    var merged=database.mirror(pair,local,peer,identity,"23S303",change,()->true);require(merged.replaced()==1);
                }
                List<Long> numbers=new ArrayList<>();read(record->{ if(record.entry().key().equals("k"))numbers.add(record.entry().integerValue()); });
                require(numbers.contains(15L) && numbers.contains(99L));
            });
            probe("domain/key delimiters do not collide",()-> {
                write(dictionary("a\nb",entry("c",0,vi(3,1))));write(dictionary("a",entry("b\nc",0,vi(3,2))));
                List<Long> found=new ArrayList<>();read(record->{if(record.entry().key().equals("c") || record.entry().key().equals("b\nc"))found.add(record.entry().integerValue());});
                require(found.contains(1L) && found.contains(2L));
            });
            probe("callback records are closed after visit",()-> {
                List<HealthDefaultsRecord> expired=new ArrayList<>();read(expired::add);
                rejects(()->expired.get(0).entry());
            });
            probe("missing key on existing storage fails without replacement",()-> {
                var unavailable=new HealthDefaultsDatabase(file,existing->{require(existing);throw new GeneralSecurityException("Unavailable test key");});
                rejects(()->unavailable.visit(pair,local,peer,identity,"23S303",record->{ }));
                try(var change=change(dictionary("d",entry("no-key",0)))) {
                    rejects(()->unavailable.mirror(pair,local,peer,identity,"23S303",change,()->true));
                }
                require(valueOrNull("no-key")==null);
            });
            probe("reopen preserves data with authenticated read",()-> {
                int before=read(record->{ }).records();database=new HealthDefaultsDatabase(file,existing->{require(existing);return cipher;});
                require(read(record->{ }).records()==before);
            });
            probe("absent domain is the native empty namespace",()-> {
                write(join(vi(1,1),blob(3,entry("nil-domain",2,vi(3,42)))));
                write(dictionary("",entry("nil-domain",1,vi(3,41))));require(value("nil-domain").integerValue()==42);
                write(dictionary("",entry("nil-domain",3,vi(3,43))));require(value("nil-domain").integerValue()==43);
                int[] matches={0};read(record->{if(record.entry().key().equals("nil-domain"))matches[0]++;});require(matches[0]==1);
            });
            probe("tampered ciphertext fails read and new write without silent skip",()-> {
                try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
                    db.execSQL("UPDATE defaults_mirror SET sealed=zeroblob(length(sealed)) WHERE object=(SELECT object FROM defaults_mirror LIMIT 1)");
                }
                int before;
                try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);var rows=db.rawQuery("SELECT COUNT(*) FROM defaults_mirror",null)) { require(rows.moveToFirst());before=rows.getInt(0); }
                rejects(()->read(record->{ }));rejects(()->write(dictionary("d",entry("corrupt",0))));
                try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);var rows=db.rawQuery("SELECT COUNT(*) FROM defaults_mirror",null)) { require(rows.moveToFirst());require(rows.getInt(0)==before); }
            });
            result.putInt("passed",passed);result.putString("stream","Health defaults Android SQLite: "+passed+" probes PASS; synthetic private DB only; no native receipt/anchors.\n");
            resultCode=Activity.RESULT_OK;
        } catch(Throwable failure) {
            result.putInt("passed",passed);result.putString("stream","Health defaults SQLite FAILED after "+passed+" probes: "+failure.getClass().getSimpleName()+"\n");
        } finally {
            // finish() can terminate the instrumented process immediately; cleanup must precede it.
            if(file!=null && file.exists() && !SQLiteDatabase.deleteDatabase(file)) {
                resultCode=Activity.RESULT_CANCELED;result.putString("stream","Health defaults probe cleanup FAILED\n");
            }
        }
        finish(resultCode,result);
    }
    private void probe(String name,Probe probe) throws Exception {
        probe.run();passed++;Bundle progress=new Bundle();progress.putString("stream","PASS "+passed+": "+name+"\n");sendStatus(0,progress);
    }
    private HealthDefaultsDatabase.Result write(byte[]... dictionaries) throws Exception { return write(()->true,dictionaries); }
    private HealthDefaultsDatabase.Result write(Callable<Boolean> eligible,byte[]... dictionaries) throws Exception {
        try(var change=change(dictionaries)) { return database.mirror(pair,local,peer,identity,"23S303",change,eligible); }
    }
    private HealthDefaultsDatabase.Summary read(HealthDefaultsDatabase.Visitor visitor) throws Exception { return database.visit(pair,local,peer,identity,"23S303",visitor); }
    private NativeHealthDefaults.Kind kind(String key) throws Exception {
        NativeHealthDefaults.Kind[] kind={null};read(record->{if(record.entry().key().equals(key))kind[0]=record.entry().kind();});return kind[0];
    }
    // Test snapshots copy typed values inside the owned callback rather than leaking a closed Entry.
    private record Value(Double doubleValue,Long integerValue,String stringValue,byte[] bytesValue) { }
    private Value value(String key) throws Exception { Value value=valueOrNull(key);require(value!=null);return value; }
    private Value valueOrNull(String key) throws Exception {
        Value[] value={null};read(record->{if(record.entry().key().equals(key)) { var e=record.entry();value[0]=new Value(e.doubleValue(),e.integerValue(),e.stringValue(),e.bytesValue()); }});return value[0];
    }
    private static void rejects(Probe probe) throws Exception {
        boolean rejected=false;try { probe.run(); }catch(Exception expected) { rejected=true; }require(rejected);
    }
    private static void require(boolean condition) { if(!condition)throw new AssertionError("Probe assertion failed"); }
    private static NativeHealthChangesCodec.Node change(byte[]... dictionaries) {
        var out=new ByteArrayOutputStream();out.writeBytes(join(vi(1,16),vi(8,1),vi(7,0)));
        for(byte[] dictionary:dictionaries)out.writeBytes(blob(4,dictionary));return NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,out.toByteArray());
    }
    private static byte[] dictionary(String domain,byte[]... entries) {
        var out=new ByteArrayOutputStream();out.writeBytes(join(vi(1,1),string(2,domain)));
        for(byte[] entry:entries)out.writeBytes(blob(3,entry));return out.toByteArray();
    }
    private static byte[] entry(String key,double date,byte[]... values) { return join(string(1,key),dbl(2,date),join(values)); }
    private static byte[] vi(int field,long value) {
        var out=new ByteArrayOutputStream();out.write(field<<3);
        do { int b=(int)value&127;value>>>=7;out.write(value==0 ? b : b|128); }while(value!=0);return out.toByteArray();
    }
    private static byte[] blob(int field,byte[] value) {
        byte[] size=vi(1,value.length);return join(new byte[]{(byte)((field<<3)|2)},Arrays.copyOfRange(size,1,size.length),value);
    }
    private static byte[] string(int field,String value) { return blob(field,value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] dbl(int field,double value) { return join(new byte[]{(byte)((field<<3)|1)},ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()); }
    private static byte[] join(byte[]... parts) { var out=new ByteArrayOutputStream();for(byte[] part:parts)out.writeBytes(part);return out.toByteArray(); }
}
