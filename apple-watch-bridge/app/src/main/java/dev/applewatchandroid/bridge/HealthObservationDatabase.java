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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Transactional original observations. No accepted samples, anchor table, ACK or identity adoption. */
final class HealthObservationDatabase {
    private static final int MAX_ROWS=4096,MAX_BYTES=16*1024*1024;
    private static final Object LOCK=new Object();
    private final File file;
    record Result(int inserted,int duplicate,int uniqueQuantity,int uniqueCategory,int sampleVariants,int variants) { }
    record ReadSummary(int records,int quantityDefinitions,int categoryDefinitions,int canonicalUnits,
                       int canonicalValues,int originalValues,int originalUnits,int categoryValues,int unrecognizedSamples) { }
    @FunctionalInterface interface Visitor { void visit(HealthObservationRecord record) throws Exception; }
    private record Row(String kind,String object,String variant,byte[] sealed) { }
    HealthObservationDatabase(Context context) { file=context.getDatabasePath("health-observations-v1.db"); }

    /** Read existing storage only. Nothing leaves the worker except aggregate validation counts. */
    ReadSummary readSummary(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity,String build) throws Exception {
        int[] counts=new int[9];
        visit(pair,local,peer,identity,record-> {
            counts[0]++;
            var definition=record.definition(build);
            if(record.kind().equals("QUANTITY")) {
                if(definition!=null) { counts[1]++;if(definition.canonicalUnit()!=null)counts[3]++; }
                else counts[8]++;
                Double canonical=record.canonicalValue(),original=record.originalValue();
                if(canonical!=null && Double.isFinite(canonical))counts[4]++;
                if(original!=null && Double.isFinite(original))counts[5]++;
                if(record.originalUnit()!=null)counts[6]++;
            } else if(record.kind().equals("CATEGORY")) {
                if(definition!=null)counts[2]++;else counts[8]++;
                if(record.categoryValue()!=null)counts[7]++;
            }
        });
        return new ReadSummary(counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6],counts[7],counts[8]);
    }

    /** Callback record lifetime ends on return. Consumers publish only after this method succeeds. */
    void visit(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity,Visitor visitor) throws Exception {
        validateScope(pair,local,peer,identity);
        if(visitor==null)throw new IllegalArgumentException("Missing Health observation visitor");
        synchronized(LOCK) {
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new GeneralSecurityException("Existing Health observation database unavailable");
            var cipher=keys(true);
            byte[] scopeBytes=scope(pair,local,peer,identity);
            String scope;
            try { scope=cipher.token("scope-v1".getBytes(StandardCharsets.US_ASCII),scopeBytes); }
            finally { wipe(scopeBytes); }
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                if(db.getVersion()!=1)throw new IllegalStateException("Unsupported Health observation database version");
                // LOCK prevents app writers while this bounded cursor owns its read snapshot.
                int rows=0,bytes=0;
                try(var query=db.rawQuery("SELECT kind,object,variant,sealed,length(sealed) FROM observations WHERE scope=? ORDER BY kind,object,variant",new String[]{scope})) {
                    while(query.moveToNext()) {
                        if(++rows>MAX_ROWS)throw new IllegalStateException("Health observation read rows budget");
                        long size=query.getLong(4);
                        if(size<29 || size>NativeHealthSyncCodec.MAX_BYTES+37)
                            throw new GeneralSecurityException("Invalid Health observation ciphertext size");
                        String kind=query.getString(0),object=query.getString(1),variant=query.getString(2);
                        byte[] sealed=query.getBlob(3),plain=null,domain=null,key=null;
                        try {
                            bytes=Math.addExact(bytes,sealed.length);
                            if(bytes>MAX_BYTES)throw new IllegalStateException("Health observation read bytes budget");
                            plain=cipher.open(sealed,HealthObservationCipher.aad(scope,kind,object,variant));
                            domain=(scope+"\n"+kind).getBytes(StandardCharsets.US_ASCII);
                            if(!variant.equals(cipher.token(domain,plain)))
                                throw new GeneralSecurityException("Health observation variant mismatch");
                            try(var record=HealthObservationRecord.decode(kind,plain)) {
                                key=record.objectKey();
                                if(!object.equals(cipher.token(domain,key)))
                                    throw new GeneralSecurityException("Health observation object mismatch");
                                visitor.visit(record);
                            }
                        } finally { wipe(sealed);wipe(plain);wipe(domain);wipe(key); }
                    }
                }
            }
        }
    }

    /** Every row binds the authenticated sender and original native envelope identity, even on replay. */
    Result observe(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity,
                   NativeHealthChangesCodec.Node change) throws Exception {
        validateScope(pair,local,peer,identity);
        var schema=NativeHealthObjectCodec.schemaFor(change);
        if(schema==null || change.unknownFields()!=0)throw new IllegalArgumentException("Unsupported Health observation entity");
        synchronized(LOCK) {
            boolean existing=Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            if(existing && !Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new GeneralSecurityException("Invalid Health observation database path");
            HealthObservationCipher cipher=keys(existing);
            byte[] scopeBytes=scope(pair,local,peer,identity);
            String scope;
            try { scope=cipher.token("scope-v1".getBytes(StandardCharsets.US_ASCII),scopeBytes); }
            finally { wipe(scopeBytes); }
            List<Row> rows=new ArrayList<>();int total=0;
            try {
                for(int i=0;i<change.byteCount(4);i++) {
                    byte[] original=change.bytesAt(4,i);
                    try(var object=NativeHealthChangesCodec.decode(schema,original)) {
                        var counts=NativeHealthObjectCodec.counts(object);
                        if(counts.unknownFields()!=0 || counts.opaqueChildren()!=0)
                            throw new IllegalArgumentException("Incomplete Health observation schema");
                        if(schema==NativeHealthChangesCodec.Schema.OBJECT_COLLECTION) {
                            for(int field:new int[]{3,4,9})for(int item=0;item<object.children(field).size();item++) {
                                var sample=object.children(field).get(item).children(1);
                                if(sample.isEmpty())throw new IllegalArgumentException("Missing Health sample");
                                var header=NativeHealthObjectCodec.header(sample.get(0));
                                if(!header.structurallyComplete())throw new IllegalArgumentException("Incomplete Health sample header");
                                add(rows,cipher,scope,field==3 ? "CATEGORY" : field==4 ? "QUANTITY" : "DELETED",
                                        uuid(header.uuid()),original,field,item);
                            }
                        } else {
                            byte[] key;
                            String kind;
                            if(schema==NativeHealthChangesCodec.Schema.SOURCE || schema==NativeHealthChangesCodec.Schema.DEVICE) {
                                key=object.bytes(schema==NativeHealthChangesCodec.Schema.SOURCE ? 5 : 9);
                                if(key==null || key.length!=16) { wipe(key);throw new IllegalArgumentException("Missing Health object UUID"); }
                                kind=schema==NativeHealthChangesCodec.Schema.SOURCE ? "SOURCE" : "DEVICE";
                            } else {
                                key=NativeHealthObjectCodec.defaultsObservationKey(object.int64(1),object.string(2));
                                var identifiers=change.children(9);
                                boolean protectedDefaults=identifiers.isEmpty() ? Integer.valueOf(17).equals(change.int32(1))
                                        : Long.valueOf(17).equals(identifiers.get(0).int64(2));
                                kind=protectedDefaults ? "PROTECTED_DEFAULTS" : "DEFAULTS";
                            }
                            add(rows,cipher,scope,kind,key,original,0,0);
                        }
                    } finally { wipe(original); }
                }
                for(Row row:rows)total=Math.addExact(total,row.sealed.length);
                if(rows.size()>MAX_ROWS || total>MAX_BYTES)throw new IllegalArgumentException("Health observation batch budget");
                return write(cipher,scope,rows,existing);
            } finally { for(Row row:rows)wipe(row.sealed); }
        }
    }
    private static void add(List<Row> rows,HealthObservationCipher cipher,String scope,String kind,
                            byte[] identity,byte[] original,int field,int item) throws Exception {
        byte[] domain=(scope+"\n"+kind).getBytes(StandardCharsets.US_ASCII);
        byte[] body=ByteBuffer.allocate(8+original.length).putInt(field).putInt(item).put(original).array();
        try {
            if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("Health observation batch rows");
            long bytes=body.length+29;
            for(Row row:rows)bytes+=row.sealed.length;
            if(bytes>MAX_BYTES)throw new IllegalArgumentException("Health observation batch bytes");
            String object=cipher.token(domain,identity),variant=cipher.token(domain,body);
            rows.add(new Row(kind,object,variant,cipher.seal(body,HealthObservationCipher.aad(scope,kind,object,variant))));
        } finally { wipe(identity);wipe(domain);wipe(body); }
    }
    private Result write(HealthObservationCipher cipher,String scope,List<Row> rows,boolean existing) throws Exception {
        File parent=file.getParentFile();if(parent==null || !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("Health database directory unavailable");
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            db.disableWriteAheadLogging();db.execSQL("PRAGMA synchronous=FULL");
            boolean initialize=db.getVersion()==0;
            if(initialize) {
                // A crash before the first schema commit may leave an empty SQLite file. Never erase tables.
                try(var tables=db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null)) {
                    tables.moveToFirst();if(tables.getLong(0)!=0)throw new IllegalStateException("Unrecognized Health database schema");
                }
            } else if(db.getVersion()!=1)throw new IllegalStateException("Unsupported Health observation database version");
            db.beginTransaction();
            int inserted=0,duplicate=0;
            try {
                if(initialize) {
                    db.execSQL("CREATE TABLE observations(scope TEXT NOT NULL,kind TEXT NOT NULL,object TEXT NOT NULL,variant TEXT NOT NULL,sealed BLOB NOT NULL,PRIMARY KEY(scope,kind,object,variant)) WITHOUT ROWID");
                    db.setVersion(1);
                }
                for(Row row:rows) {
                    byte[] old=null,plain=null;
                    try(var cursor=db.rawQuery("SELECT sealed FROM observations WHERE scope=? AND kind=? AND object=? AND variant=?",
                            new String[]{scope,row.kind,row.object,row.variant})) {
                        if(cursor.moveToFirst()) {
                            old=cursor.getBlob(0);plain=cipher.open(old,HealthObservationCipher.aad(scope,row.kind,row.object,row.variant));
                            byte[] candidate=cipher.open(row.sealed,HealthObservationCipher.aad(scope,row.kind,row.object,row.variant));
                            try { if(!Arrays.equals(plain,candidate))throw new GeneralSecurityException("Conflicting Health observation index"); }
                            finally { wipe(candidate); }
                            duplicate++;continue;
                        }
                    } finally { wipe(old);wipe(plain); }
                    ContentValues values=new ContentValues();values.put("scope",scope);values.put("kind",row.kind);
                    values.put("object",row.object);values.put("variant",row.variant);values.put("sealed",row.sealed);
                    db.insertOrThrow("observations",null,values);inserted++;
                }
                try(var budget=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(sealed)),0) FROM observations",null)) {
                    budget.moveToFirst();if(budget.getLong(0)>MAX_ROWS || budget.getLong(1)>MAX_BYTES)
                        throw new IllegalStateException("Health observation database budget");
                }
                verifyRows(db,cipher,scope);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            int quantity=0,category=0,variants=0,sampleVariants=0;
            try(var query=db.rawQuery("SELECT kind,COUNT(DISTINCT object),COUNT(*) FROM observations WHERE scope=? GROUP BY kind",new String[]{scope})) {
                while(query.moveToNext()) {
                    String kind=query.getString(0);int count=query.getInt(2);variants+=count;
                    if(kind.equals("QUANTITY")) { quantity=query.getInt(1);sampleVariants+=count; }
                    if(kind.equals("CATEGORY")) { category=query.getInt(1);sampleVariants+=count; }
                }
            }
            return new Result(inserted,duplicate,quantity,category,sampleVariants,variants);
        }
    }
    private static void verifyRows(SQLiteDatabase db,HealthObservationCipher cipher,String scope) throws Exception {
        // Authentication failure rolls back the entire new batch before publishing even aggregate counts.
        try(var query=db.rawQuery("SELECT kind,object,variant,sealed FROM observations WHERE scope=?",new String[]{scope})) {
            while(query.moveToNext()) {
                String kind=query.getString(0),object=query.getString(1),variant=query.getString(2);
                byte[] sealed=query.getBlob(3),plain=null;
                try {
                    plain=cipher.open(sealed,HealthObservationCipher.aad(scope,kind,object,variant));
                    byte[] domain=(scope+"\n"+kind).getBytes(StandardCharsets.US_ASCII);
                    try { if(!variant.equals(cipher.token(domain,plain)))throw new GeneralSecurityException("Health observation variant mismatch"); }
                    finally { wipe(domain); }
                } finally { wipe(sealed);wipe(plain); }
            }
        }
    }
    private static HealthObservationCipher keys(boolean existingDatabase) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String aes="apple-watch-bridge.health-observations.aes.v1",hmac="apple-watch-bridge.health-observations.index.v1";
        var encryption=store.getKey(aes,null);var index=store.getKey(hmac,null);
        if(existingDatabase && (!(encryption instanceof SecretKey) || !(index instanceof SecretKey)))
            throw new GeneralSecurityException("Existing Health database key unavailable; regeneration refused");
        if(encryption==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(aes,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            encryption=generator.generateKey();
        }
        if(index==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(hmac,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build());index=generator.generateKey();
        }
        if(!(encryption instanceof SecretKey enc) || !(index instanceof SecretKey idx))
            throw new GeneralSecurityException("Invalid Health storage key type");
        return new HealthObservationCipher(enc,idx);
    }
    private static byte[] scope(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity) {
        return ByteBuffer.allocate(84).putInt(identity.version()).put(uuid(pair)).put(uuid(local)).put(uuid(peer))
                .put(uuid(identity.persistent())).put(uuid(identity.health())).array();
    }
    private static void validateScope(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity) {
        if(pair==null || local==null || peer==null || identity==null || identity.version()!=17
                || identity.persistent()==null || identity.health()==null || local.equals(peer))
            throw new IllegalArgumentException("Unsupported Health observation scope");
    }
    private static byte[] uuid(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static void wipe(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
}
