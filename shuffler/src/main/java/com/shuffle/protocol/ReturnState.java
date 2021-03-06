package com.shuffle.protocol;

import com.shuffle.protocol.blame.Matrix;

/**
 * An error state that the protocol returns if the it fails.
 *
 * Created by Daniel Krawisz on 12/6/15.
 */
public class ReturnState {
    boolean success;
    SessionIdentifier session;
    Phase phase;
    Throwable error = null;
    Matrix blame = null;

    public ReturnState(boolean success, SessionIdentifier session, Phase phase, Throwable error, Matrix blame) {
        this.success = success;
        this.session = session;
        this.phase = phase;
        this.error = error;
        this.blame = blame;
    }

    protected ReturnState() {}

    // Whether two return states are equivalent.
    public boolean match(ReturnState m) {
        return success == m.success && session == m.session &&
                (phase == null || phase == m.phase) &&
                (error == null && m.error == null ||
                error != null && m.error != null && error.getClass().equals(m.error.getClass()))
                && (blame == null && m.blame == null || blame != null && blame.match(m.blame));
    }

    public String toString() {
        String session;
        if (this.session != null) {
            session = " " + this.session.toString();
        } else {
            session = "";
        }

        if (success) {
            return "Successful run" + session;
        }

        String str = "Unsuccessful run" + session;

        if (error != null) {
            str += "; threw " + error.toString();
        }

        if (phase != null) {
            str += " failed in phase " + phase.toString();
        }

        if (blame != null) {
            str += "; blame = " + blame.toString();
        }

        return str;
    }
}
