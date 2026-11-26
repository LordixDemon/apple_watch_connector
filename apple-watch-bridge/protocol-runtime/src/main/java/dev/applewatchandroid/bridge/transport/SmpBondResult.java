package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothBondSecretRecord;

import java.util.Arrays;

/**
 * Defensive, zeroizable result of a completed BLE SMP pairing transaction.
 *
 * <p>All secret byte arrays are copied defensively on construction and accessor read, and zeroized
 * on {@link #close()}. {@link #toString()} never exposes key, randomizer, or address bytes.</p>
 */
public final class SmpBondResult implements AutoCloseable {
    private static final int KEY_LENGTH = 16;
    private static final int RAND_LENGTH = 8;
    private static final int ADDRESS_LENGTH = 6;

    private final boolean secureConnections;
    private final int encryptionKeySize;
    private final int ediv;
    private final byte[] rand;
    private final byte[] longTermKey;
    private final byte[] peerIrk;
    private final int peerIdentityAddressType;
    private final byte[] peerIdentityAddress;
    private final byte[] bondSecretRecord;
    private final boolean bondPersisted;
    private boolean closed;

    public SmpBondResult(
            boolean secureConnections,
            int encryptionKeySize,
            int ediv,
            byte[] rand,
            byte[] longTermKey,
            byte[] peerIrk,
            int peerIdentityAddressType,
            byte[] peerIdentityAddress,
            byte[] bondSecretRecord,
            boolean bondPersisted) {
        if (encryptionKeySize < 7 || encryptionKeySize > 16) {
            throw new IllegalArgumentException("Encryption key size must be 7..16");
        }
        if ((ediv & ~0xFFFF) != 0) {
            throw new IllegalArgumentException("EDIV must fit uint16");
        }
        if (rand == null) {
            this.rand = new byte[RAND_LENGTH];
        } else if (rand.length != RAND_LENGTH) {
            throw new IllegalArgumentException("SMP Rand must be 8 bytes");
        } else {
            this.rand = rand.clone();
        }
        if (longTermKey != null && longTermKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("SMP LTK must be 16 bytes");
        }
        if (peerIrk != null && peerIrk.length != KEY_LENGTH) {
            throw new IllegalArgumentException("SMP peer IRK must be 16 bytes");
        }
        if (peerIdentityAddress != null && peerIdentityAddress.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException("SMP peer identity address must be 6 bytes");
        }
        if (bondSecretRecord != null) {
            BluetoothBondSecretRecord.validateSerialized(bondSecretRecord);
        }

        this.secureConnections = secureConnections;
        this.encryptionKeySize = encryptionKeySize;
        this.ediv = ediv;
        this.longTermKey = longTermKey == null ? null : longTermKey.clone();
        this.peerIrk = peerIrk == null ? null : peerIrk.clone();
        this.peerIdentityAddressType = peerIdentityAddressType;
        this.peerIdentityAddress = peerIdentityAddress == null ? null : peerIdentityAddress.clone();
        this.bondSecretRecord = bondSecretRecord == null ? null : bondSecretRecord.clone();
        this.bondPersisted = bondPersisted;
    }

    public boolean secureConnections() {
        return secureConnections;
    }

    public int encryptionKeySize() {
        return encryptionKeySize;
    }

    public int ediv() {
        return ediv;
    }

    public byte[] rand() {
        requireOpen();
        return rand.clone();
    }

    public byte[] longTermKey() {
        requireOpen();
        return longTermKey == null ? null : longTermKey.clone();
    }

    public byte[] peerIrk() {
        requireOpen();
        return peerIrk == null ? null : peerIrk.clone();
    }

    public int peerIdentityAddressType() {
        return peerIdentityAddressType;
    }

    public byte[] peerIdentityAddress() {
        requireOpen();
        return peerIdentityAddress == null ? null : peerIdentityAddress.clone();
    }

    public byte[] bondSecretRecord() {
        requireOpen();
        return bondSecretRecord == null ? null : bondSecretRecord.clone();
    }

    public boolean bondPersisted() {
        return bondPersisted;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        wipe(rand);
        wipe(longTermKey);
        wipe(peerIrk);
        wipe(peerIdentityAddress);
        wipe(bondSecretRecord);
    }

    @Override
    public String toString() {
        return "SmpBondResult[sc=" + secureConnections
                + ", keySize=" + encryptionKeySize
                + ", hasEdiv=" + (ediv != 0)
                + ", hasPeerIdentity=" + (peerIrk != null && peerIdentityAddress != null)
                + ", hasBondRecord=" + (bondSecretRecord != null)
                + ", bondPersisted=" + bondPersisted
                + ", closed=" + closed + "]";
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SmpBondResult has been closed");
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
