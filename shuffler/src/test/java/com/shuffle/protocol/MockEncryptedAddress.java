package com.shuffle.protocol;

import com.shuffle.bitcoin.Address;
import com.shuffle.bitcoin.EncryptionKey;

/**
 * Created by Daniel Krawisz on 12/19/15.
 */
public class MockEncryptedAddress implements Address {
    public Address encrypted;
    public EncryptionKey key;

    public MockEncryptedAddress(Address encrypted, EncryptionKey key) {
        this.encrypted = encrypted;
        this.key = key;
    }

    @Override
    public String toString() {
        return "encrypted[" + encrypted.toString() + ", " + key.toString() + "]";
    }
}
