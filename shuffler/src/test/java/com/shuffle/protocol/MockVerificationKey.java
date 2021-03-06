package com.shuffle.protocol;

import com.shuffle.bitcoin.Address;
import com.shuffle.bitcoin.Signature;
import com.shuffle.bitcoin.Transaction;
import com.shuffle.bitcoin.VerificationKey;

/**
 * TODO
 *
 * Created by Daniel Krawisz on 12/7/15.
 */
public class MockVerificationKey implements VerificationKey {
    final int index;

    public MockVerificationKey(int index) {
        this.index = index;
    }

    // These functions are not implemented yet.
    @Override
    public boolean verify(Transaction t, Signature sig) throws InvalidImplementationError {
        if (!(sig instanceof MockSignature)) {
            throw new InvalidImplementationError();
        }

        MockSignature mock = (MockSignature)sig;

        return mock.t != null && mock.t.equals(t) && mock.key.equals(this);
    }

    @Override
    public boolean verify(Packet packet, Signature sig) {
        if (!(sig instanceof MockSignature)) {
            throw new InvalidImplementationError();
        }

        MockSignature mock = (MockSignature)sig;

        return mock.packet != null && mock.packet.equals(packet) && mock.key.equals(this);
    }

    @Override
    public boolean equals(Object vk) {
        if(!(vk instanceof MockVerificationKey)) {
            return false;
        }

        return index == ((MockVerificationKey)vk).index;
    }

    @Override
    public Address address() {
        return new MockAddress(index);
    }

    public String toString() {
        return "vk[" + index + "]";
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public int compareTo(Object o) {
        if(!(o instanceof MockVerificationKey)) {
            return -1;
        }

        MockVerificationKey key = ((MockVerificationKey)o);

        if (index == key.index) {
            return 0;
        }

        if (index < key.index) {
            return -1;
        }

        return 1;
    }
}
