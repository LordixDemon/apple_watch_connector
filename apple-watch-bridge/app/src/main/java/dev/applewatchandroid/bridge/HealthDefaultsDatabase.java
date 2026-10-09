package dev.applewatchandroid.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Private typed defaults mirror. Real value merge, separate from native receive state and Android settings. */
final class HealthDefaultsDatabase {
    private static final Object LOCK=new Object();
    private static final int MAX_ROWS=4096,MAX_BYTES=16*1024*1024;
    private final File file;
    @FunctionalInterface interface KeyProvider { HealthObservationCipher keys(boolean existing) throws Exception; }
    private final KeyProvider keyProvider;
    record Result(int replaced,int retained,Summary summary) { }
    record Summary(int records,int doubles,int integers,int strings,int bytes,int tombstones) { }
    @FunctionalInterface interface Visitor { void visit(HealthDefaultsRecord record) throws Exception; }
    HealthDefaultsDatabase(Context context) { this(context.getDatabasePath("health-defaults-mirror-v1.db"),HealthDefaultsDatabase::keys); }
    // Package-private injection lets a separate instrumentation APK exercise real SQLite on its own file.
    HealthDefaultsDatabase(File file,KeyProvider keyProvider) {
        if(file==null || keyProvider==null)throw new IllegalArgumentException("Missing Health defaults database");
        this.file=file;this.keyProvider=keyProvider;
    }

    /** Only a live eligible speculative change may update the mirror; no sequence/anchors/reply are committed. */
    Result mirror(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity,String build,
                  NativeHealthChangesCodec.Node change,Callable<Boolean> stillEligible) throws Exception {
        validateScope(pair,local,peer,identity);
        var plan=NativeHealthReceivePolicy.prepare(change,build,null,entity->null);
        if(!Boolean.TRUE.equals(change.bool(8)) || plan.outcome()!=NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION
                || plan.entity()!=NativeHealthReceivePolicy.Entity.DEFAULTS && plan.entity()!=NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS)
            throw new IllegalArgumentException("Health defaults mirror requires eligible speculative values");
        synchronized(LOCK) {
            requireEligible(stillEligible);
            boolean existing=Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            if(existing && !Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new GeneralSecurityException("Invalid Health defaults database path");
            var cipher=keyProvider.keys(existing);String scope=scope(cipher,pair,local,peer,identity);
            File parent=file.getParentFile();
            if(parent==null || !parent.exists() && !parent.mkdirs())throw new IllegalStateException("Health database directory unavailable");
            try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
                db.disableWriteAheadLogging();db.execSQL("PRAGMA synchronous=FULL");
                boolean initialize=db.getVersion()==0;
                if(initialize) {
                    try(var tables=db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null)) {
                        if(!tables.moveToFirst() || tables.getLong(0)!=0)throw new IllegalStateException("Unrecognized Health defaults database schema");
                    }
                } else if(db.getVersion()!=1)throw new IllegalStateException("Unsupported Health defaults database version");
                db.beginTransaction();int replaced=0,retained=0;Summary summary;
                try {
                    if(initialize) {
                        db.execSQL("CREATE TABLE defaults_mirror(scope TEXT NOT NULL,entity TEXT NOT NULL,object TEXT NOT NULL,variant TEXT NOT NULL,sealed BLOB NOT NULL,PRIMARY KEY(scope,entity,object)) WITHOUT ROWID");
                        db.setVersion(1);
                    }
                    // Corrupt old rows cannot be converted into a successful empty lookup.
                    visit(db,cipher,scope,build,record->{ });
                    int work=0,bytes=0;
                    for(int i=0;i<change.byteCount(4);i++) {
                        byte[] original=change.bytesAt(4,i);
                        try(var dictionary=NativeHealthDefaults.decode(plan.entity(),build,original)) {
                            for(var entry:dictionary.entries()) {
                                if(++work>MAX_ROWS)throw new IllegalArgumentException("Health defaults batch rows budget");
                                byte[] body=HealthDefaultsRecord.encode(dictionary,entry),key=null,domain=null,sealed=null;
                                try {
                                    bytes=Math.addExact(bytes,body.length+29);
                                    if(bytes>MAX_BYTES)throw new IllegalArgumentException("Health defaults batch bytes budget");
                                    key=HealthDefaultsRecord.key(dictionary.domain(),entry.key());
                                    String entity=plan.entity().name();domain=(scope+"\n"+entity).getBytes(StandardCharsets.US_ASCII);
                                    String object=cipher.token(domain,key),variant=cipher.token(domain,body);
                                    Double date=storedDate(db,cipher,scope,entity,object,build);
                                    if(!NativeHealthDefaults.replaces(date,entry.date())) { retained++;continue; }
                                    sealed=cipher.seal(body,HealthDefaultsRecord.aad(scope,entity,object,variant));
                                    ContentValues values=new ContentValues();values.put("scope",scope);values.put("entity",entity);
                                    values.put("object",object);values.put("variant",variant);values.put("sealed",sealed);
                                    db.insertWithOnConflict("defaults_mirror",null,values,SQLiteDatabase.CONFLICT_REPLACE);
                                    // Android's insertWithOnConflict may return -1 on failure rather than throw.
                                    Double written=storedDate(db,cipher,scope,entity,object,build);
                                    if(written==null || Double.compare(written,entry.date())!=0)
                                        throw new IllegalStateException("Health defaults write verification failed");
                                    replaced++;
                                } finally { wipe(body);wipe(key);wipe(domain);wipe(sealed); }
                            }
                        } finally { wipe(original); }
                    }
                    summary=visit(db,cipher,scope,build,record->{ });
                    requireEligible(stillEligible);db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                return new Result(replaced,retained,summary);
            }
        }
    }
    Summary visit(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity,String build,Visitor visitor) throws Exception {
        validateScope(pair,local,peer,identity);
        if(!NativeHealthTypeCatalog.BUILD.equals(build) || visitor==null)throw new IllegalArgumentException("Invalid Health defaults read context");
        synchronized(LOCK) {
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new GeneralSecurityException("Existing Health defaults database unavailable");
            var cipher=keyProvider.keys(true);String scope=scope(cipher,pair,local,peer,identity);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                if(db.getVersion()!=1)throw new IllegalStateException("Unsupported Health defaults database version");
                return visit(db,cipher,scope,build,visitor);
            }
        }
    }
    private static Summary visit(SQLiteDatabase db,HealthObservationCipher cipher,String scope,String build,Visitor visitor) throws Exception {
        int[] counts=new int[6];long bytes=0;
        try(var all=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(sealed)),0) FROM defaults_mirror",null)) {
            if(!all.moveToFirst() || all.getLong(0)>MAX_ROWS || all.getLong(1)>MAX_BYTES)
                throw new IllegalStateException("Health defaults database budget");
        }
        try(var rows=db.rawQuery("SELECT entity,object,variant,sealed,length(sealed) FROM defaults_mirror WHERE scope=? ORDER BY entity,object",new String[]{scope})) {
            while(rows.moveToNext()) {
                if(++counts[0]>MAX_ROWS || rows.getLong(4)<29 || rows.getLong(4)>NativeHealthSyncCodec.MAX_BYTES+37
                        || (bytes+=rows.getLong(4))>MAX_BYTES)throw new GeneralSecurityException("Health defaults read budget");
                String entity=rows.getString(0),object=rows.getString(1),variant=rows.getString(2);
                byte[] sealed=rows.getBlob(3),plain=null;
                try {
                    plain=authenticate(cipher,scope,entity,object,variant,sealed);
                    try(var record=HealthDefaultsRecord.decode(entity(entity),build,plain)) {
                        verifyObject(cipher,scope,entity,object,record);
                        counts[1+record.entry().kind().ordinal()]++;
                        visitor.visit(record);
                    }
                } finally { wipe(sealed);wipe(plain); }
            }
        }
        return new Summary(counts[0],counts[1],counts[2],counts[3],counts[4],counts[5]);
    }
    private static Double storedDate(SQLiteDatabase db,HealthObservationCipher cipher,String scope,String entity,String object,String build) throws Exception {
        try(var row=db.rawQuery("SELECT variant,sealed,length(sealed) FROM defaults_mirror WHERE scope=? AND entity=? AND object=?",new String[]{scope,entity,object})) {
            if(!row.moveToFirst())return null;
            if(row.getLong(2)<29 || row.getLong(2)>NativeHealthSyncCodec.MAX_BYTES+37)throw new GeneralSecurityException("Invalid Health defaults ciphertext size");
            String variant=row.getString(0);byte[] sealed=row.getBlob(1),plain=null;
            try {
                plain=authenticate(cipher,scope,entity,object,variant,sealed);
                try(var record=HealthDefaultsRecord.decode(entity(entity),build,plain)) {
                    verifyObject(cipher,scope,entity,object,record);return record.entry().date();
                }
            } finally { wipe(sealed);wipe(plain); }
        }
    }
    private static byte[] authenticate(HealthObservationCipher cipher,String scope,String entity,String object,String variant,byte[] sealed) throws Exception {
        byte[] plain=cipher.open(sealed,HealthDefaultsRecord.aad(scope,entity,object,variant));
        byte[] domain=(scope+"\n"+entity).getBytes(StandardCharsets.US_ASCII);
        try {
            if(!variant.equals(cipher.token(domain,plain)))throw new GeneralSecurityException("Health defaults variant mismatch");
            byte[] result=plain;plain=null;return result;
        } finally { wipe(plain);wipe(domain); }
    }
    private static void verifyObject(HealthObservationCipher cipher,String scope,String entity,String object,HealthDefaultsRecord record) throws Exception {
        byte[] domain=(scope+"\n"+entity).getBytes(StandardCharsets.US_ASCII),key=record.key();
        try { if(!object.equals(cipher.token(domain,key)))throw new GeneralSecurityException("Health defaults object mismatch"); }
        finally { wipe(domain);wipe(key); }
    }
    private static NativeHealthReceivePolicy.Entity entity(String name) {
        if("DEFAULTS".equals(name))return NativeHealthReceivePolicy.Entity.DEFAULTS;
        if("PROTECTED_DEFAULTS".equals(name))return NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS;
        throw new IllegalArgumentException("Unsupported Health defaults entity");
    }
    private static void requireEligible(Callable<Boolean> stillEligible) throws Exception {
        if(stillEligible==null || !Boolean.TRUE.equals(stillEligible.call()))throw new IllegalStateException("Health defaults live context ended");
    }
    private static void validateScope(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity) {
        if(pair==null || local==null || peer==null || local.equals(peer) || identity==null || identity.version()!=17
                || identity.persistent()==null || identity.health()==null)throw new IllegalArgumentException("Invalid Health defaults scope");
    }
    private static String scope(HealthObservationCipher cipher,UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity) throws Exception {
        var bytes=ByteBuffer.allocate(84).putInt(identity.version());
        for(UUID id:new UUID[]{pair,local,peer,identity.persistent(),identity.health()})
            bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        try { return cipher.token("defaults-mirror-scope-v1".getBytes(StandardCharsets.US_ASCII),bytes.array()); }
        finally { wipe(bytes.array()); }
    }
    private static HealthObservationCipher keys(boolean existing) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String aes="apple-watch-bridge.health-defaults-mirror.aes.v1",hmac="apple-watch-bridge.health-defaults-mirror.index.v1";
        var encryption=store.getKey(aes,null);var index=store.getKey(hmac,null);
        if(existing && (!(encryption instanceof SecretKey) || !(index instanceof SecretKey)))
            throw new GeneralSecurityException("Existing Health defaults key unavailable; regeneration refused");
        if(encryption==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(aes,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());encryption=generator.generateKey();
        }
        if(index==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(hmac,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build());index=generator.generateKey();
        }
        if(!(encryption instanceof SecretKey enc) || !(index instanceof SecretKey idx))throw new GeneralSecurityException("Invalid Health defaults key type");
        return new HealthObservationCipher(enc,idx);
    }
    private static void wipe(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
}
