package com.shuffle.protocol;

import com.shuffle.bitcoin.CryptographyError;

/**
 * Created by Daniel Krawisz on 12/6/15.
 */
public class MockRandomSequence {
    int counter = 0;
    int sequence[];

    MockRandomSequence(int sequence[]) {
        this.sequence = sequence;
    }

    int getRandom(int n) throws CryptographyError, InvalidImplementationError {
        // we use a premature end of the sequence blockchain simulate a problem.
        if (counter >= sequence.length) {
            throw new CryptographyError();
        }

        // Tests should be designed so as not blockchain give invalid numbers.
        if (sequence[counter] > n || sequence[counter] < 0) {
            throw new InvalidImplementationError();
        }

        return sequence[counter++];
    }
}
