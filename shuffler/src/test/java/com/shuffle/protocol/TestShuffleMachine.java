package com.shuffle.protocol;

import com.shuffle.bitcoin.CryptographyError;
import com.shuffle.bitcoin.SigningKey;
import com.shuffle.bitcoin.Transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration tests for the protocol.
 *
 * Created by Daniel Krawisz on 12/10/15.
 */
public class TestShuffleMachine {
    private static Logger log= LogManager.getLogger(TestShuffleMachine.class);
    // A blame matrix that matches any matrix given to it.
    // Used for ensuring a test can't fail no matter what value
    // simulated adversaries return, since we only care about testing the response of the
    // honest players.
    public static class BlameMatrixPatternAny extends BlameMatrix {

        @Override
        public boolean match(BlameMatrix bm) {
            return true;
        }

        @Override
        public String toString() {
            return "Any";
        }
    }

    public static class BlameEvidencePatternAny extends BlameMatrix.BlameEvidence {
        @Override
        public boolean match(BlameMatrix.BlameEvidence e) {
            return true;
        }

        @Override
        public String toString() {
            return "Any";
        }
    }

    public static class ReturnStatePatternOr extends ReturnState {
        ReturnState a;
        ReturnState b;

        public ReturnStatePatternOr(boolean success, SessionIdentifier session, Phase phase, Throwable error, BlameMatrix blame) {
            super(success, session, phase, error, blame);
        }

        @Override
        public boolean match(ReturnState x) {
            return a.match(x) || b.match(x);
        }
    }

    public class MutateTransactionSignature implements Simulator.MessageReplacement {

        @Override
        public Packet replace(Packet packet) throws FormatException {
            if (packet.phase == Phase.VerificationAndSubmission) {
                if (packet.message instanceof MockMessage) {
                    MockMessage mockMessage = (MockMessage)packet.message;

                    MockMessage.Atom atom = mockMessage.atoms.peek();
                    if (atom.sig instanceof MockSignature) {
                        MockSignature mockSig = (MockSignature)atom.sig;
                        if (mockSig.t instanceof MockCoin.MockTransaction) {

                            MockCoin.MockTransaction mt = (MockCoin.MockTransaction) mockSig.t;
                            MockCoin.MockTransaction nmt = mt.copy();
                            nmt.z = 2;
                            mockSig.t = nmt;
                        }
                    }
                }
            }

            return packet;
        }
    }

    private class Equivocation {
        final int equivocator;
        final int[] equivocation;


        private Equivocation(int equivocator, int[] equivocation) {
            this.equivocator = equivocator;
            this.equivocation = equivocation;
        }
    }

    BlameMatrixPatternAny anyMatrix = new BlameMatrixPatternAny();
    BlameEvidencePatternAny anyReason = new BlameEvidencePatternAny();

    public class TestCase {

        String description = null;
        int id;
        SessionIdentifier session;
        long amount;
        Map<SigningKey, ReturnState> expected = new HashMap<>();
        Map<SigningKey, ReturnState> results = new HashMap<>();

        TestCase(SessionIdentifier session, long amount, String desc, int id) {
            this.session = session;
            this.description = desc;
            this.amount = amount;
            this.id = id;
        }

        TestCase put(SigningKey key, ReturnState ex, ReturnState r) {
            results.put(key, r);
            expected.put(key, ex);
            return this;
        }

        public void putSuccessfulPlayer(SigningKey key, ReturnState r) {
            put(key, new ReturnState(true, session, Phase.Completed, null, null), r);
        }

        public void check() {

            if(amount == 0){
                Assert.fail();
            }

            // Check that the map of error states returned matches that which was expected.
            for (Map.Entry<SigningKey, ReturnState> ex : expected.entrySet()) {
                SigningKey key = ex.getKey();
                ReturnState result = results.get(key);
                ReturnState expected = ex.getValue();

                Assert.assertNotNull(result);

                Assert.assertTrue(expected.match(result));

                results.remove(key);
            }

            // I don't know why there would be any left, but just to make sure!
            Assert.assertTrue(results.isEmpty());
        }
    }

    TestCase successfulExpectation(TestCase test, Map<SigningKey, ReturnState> results) {
        for (SigningKey key : results.keySet()) {
            test.putSuccessfulPlayer(key, results.get(key));
        }
        return test;
    }

    // Create a test case representing a successful run.
    public TestCase SuccessfulRun(int caseNo, int numPlayer, Simulator sim) {
        SessionIdentifier session = new MockSessionIdentifier("success" + caseNo);
        MockCoin coin = new MockCoin();
        long amount = 17;

        return successfulExpectation(
                new TestCase(session, amount, "successful run with " + numPlayer + " players.", caseNo),
                sim.successfulRun(session, numPlayer, amount, coin)
        );
    }
    
    public TestCase InsufficientFunds(
            int caseNo,
            int numPlayers,
            int[] deadbeats,
            int[] poor,
            int[] spenders,
            Simulator sim) {

        SessionIdentifier session = new MockSessionIdentifier("fund" + caseNo);
        MockCoin coin = new MockCoin();
        long amount = 17;
        TestCase test = new TestCase(session, amount, "Insufficient funds test case.", caseNo);

        Map<SigningKey, ReturnState> results =
                sim.insufficientFundsRun(session, numPlayers, deadbeats, poor, spenders, amount, coin);

        // If no offenders were defined, then this should be a successful run.
        if (deadbeats.length == 0 && poor.length == 0 && spenders.length == 0) {
            return successfulExpectation(test, results);
        }

        // The set of offending transactions.
        Map<SigningKey, Transaction> offenders = new HashMap<>();
        Set<SigningKey> deadbeatPlayers = new HashSet<>();

        // Get transactions by key.
        for (SigningKey key : results.keySet()) {

            Transaction t = coin.getConflictingTransaction(key.VerificationKey().address(), 17);

            if (t != null) {
                offenders.put(key, t);
            } else if (coin.valueHeld(key.VerificationKey().address()) == 0) {
                deadbeatPlayers.add(key);
            }
        }

        for (SigningKey i : results.keySet()) {
            BlameMatrix bm = null;
            Phase phase;

            if (offenders.containsKey(i) || deadbeatPlayers.contains(i)) {
                bm = anyMatrix;
            } else {

                bm = new BlameMatrix();

                for (SigningKey j : results.keySet()) {
                    for (SigningKey k : results.keySet()) {
                        if (offenders.containsKey(j) || deadbeatPlayers.contains(j)) {
                            // We don't care who the malicious players accuse.
                            bm.put(j.VerificationKey(), k.VerificationKey(), anyReason);
                        } else if(deadbeatPlayers.contains(k)) {
                            bm.put(j.VerificationKey(), k.VerificationKey(),
                                    new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.NoFundsAtAll, true));
                        } else if(offenders.containsKey(k)) {
                            bm.put(j.VerificationKey(), k.VerificationKey(),
                                    new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.InsufficientFunds, true, offenders.get(k)));
                        }
                    }
                }
            }

            test.put(i,
                    new ReturnState(false, session, Phase.Blame, null, bm),
                    results.get(i));
        }
        
        return test;
    }

    public TestCase DoubleSpend(int caseNo, int[] views, int[] doubleSpenders, Simulator sim) {

        SessionIdentifier session = new MockSessionIdentifier("spend" + caseNo);
        long amount = 17;
        TestCase test = new TestCase(session, amount, "Double spending test case.", caseNo);

        Set<Simulator.MockCoin> coinNets = new HashSet<>();
        Map<Integer, Simulator.MockCoin> coinNetMap = new HashMap<>();
        List<Simulator.MockCoin> coinNetList = new LinkedList<>();

        for (int i = 0; i < views.length; i ++) {
            if (!coinNetMap.containsKey(views[i])) {
                Simulator.MockCoin coin = new MockCoin();
                coinNetMap.put(views[i], coin);
                coinNets.add(coin);
            }
            coinNetList.add(coinNetMap.get(views[i]));
        }

        LinkedHashMap<SigningKey, ReturnState> results =
                sim.doubleSpendingRun(session, coinNets, coinNetList, doubleSpenders, amount);

        // The set of offending transactions.
        Map<SigningKey, Transaction> offenders = new HashMap<>();
        Map<SigningKey, Simulator.MockCoin> playerToCoin = new HashMap<>();

        {int i = 0;
        for (SigningKey key : results.keySet()) {

            Simulator.MockCoin coin = coinNetMap.get(views[i]);
            playerToCoin.put(key, coin);

            Transaction t = coin.getConflictingTransaction(key.VerificationKey().address(), 17);

            if (t != null) {
                offenders.put(key, t);
            }

            i++;
        }}

        for (SigningKey i : results.keySet()) {
            BlameMatrix bm = null;
            Phase phase;

            if (offenders.containsKey(i)) {
                bm = anyMatrix;
                phase = null;
            } else {
                bm = new BlameMatrix();
                phase = Phase.Blame;
                for (SigningKey j : results.keySet()) {
                    for (SigningKey k : results.keySet()) {

                        if (offenders.containsKey(j)) {
                            // We don't care who the malicious players accuse.
                            bm.put(j.VerificationKey(), k.VerificationKey(), anyReason);
                        } else if(offenders.containsKey(k)) {
                            if (playerToCoin.get(j) == playerToCoin.get(k)) {
                                // Only include the transaction if the player has the same view
                                // of the network as the double spender.
                                bm.put(j.VerificationKey(), k.VerificationKey(),
                                        new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.DoubleSpend, true, offenders.get(k)));
                            }
                        }
                    }
                }
            }

            test.put(i,
                    new ReturnState(false, session, phase, null, bm),
                    results.get(i));
        }

        return test;
    }

    // Run a test case for equivocation during phase 1.
    public TestCase EquivocateAnnouncement(
            int caseNo,
            int numPlayers,
            Equivocation[] equivocators,
            Simulator sim
    ) {
        long amount = 17;
        SessionIdentifier session = new MockSessionIdentifier("eqv" + caseNo);
        Simulator.InitialState init = sim.initialize(session, amount).defaultCoin(new MockCoin());

        int eq = 0;
        for (int i = 1; i <= numPlayers; i ++) {
            init.player().initialFunds(20);

            while(eq < equivocators.length && equivocators[eq].equivocator < i) {
                eq++;
            }

            if (eq < equivocators.length && equivocators[eq].equivocator == i) {
                init.equivocateAnnouncement(equivocators[eq].equivocation);
            }
        }

        log.info("About to run an equivocation test.");

        TestCase test = new TestCase(session, amount, "Announcement phase equivocation spending test case.", caseNo);
        LinkedHashMap<SigningKey, ReturnState> results = init.run();
        Set<SigningKey> malicious = new HashSet<>();

        eq = 0;
        int index = 0;
        for (SigningKey key : results.keySet()) {
            while(eq < equivocators.length && equivocators[eq].equivocator < index) {
                eq++;
            }

            if (eq < equivocators.length && equivocators[eq].equivocator == index) {
                malicious.add(key);
            }

            index++;
        }

        for(SigningKey i : results.keySet()) {

            BlameMatrix bm = null;

            if (malicious.contains(i)) {
                bm = anyMatrix;
            } else {
                bm = new BlameMatrix();

                for (SigningKey j : results.keySet()) {
                    for (SigningKey k : results.keySet()) {
                        if (malicious.contains(k)) {
                            bm.put(j.VerificationKey(), k.VerificationKey(),
                                    new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.EquivocationFailure, true));
                        }
                    }
                }
            }

            test.put(i,
                    new ReturnState(false, session, null, null, bm),
                    results.get(i));

            index ++;
        }

        return test;
    }

    // Run a test case for equivocation during phase 3.
    public TestCase EquivocateOutput(int caseNo, int numPlayers, int[] equivocation, Simulator sim) {
        long amount = 17;
        SessionIdentifier session = new MockSessionIdentifier("eqv" + caseNo);
        Simulator.InitialState init = sim.initialize(session, amount).defaultCoin(new MockCoin());

        // Only the last player can equivocate.
        for (int i = 1; i < numPlayers; i ++) {
            init.player().initialFunds(20);
        }

        init.player().initialFunds(20).equivocateBroadcast(equivocation);

        TestCase test = new TestCase(session, amount, "Broadcast phase equivocation test case.", caseNo);
        LinkedHashMap<SigningKey, ReturnState> results = init.run();
        SigningKey malicious = null;

        // Find the malicious last player.
        int index = 0;
        for (SigningKey i : results.keySet()) {

            index++;
            if (index == numPlayers) {
                malicious = i;
            }
        }

        assert malicious != null;

        for (SigningKey i : results.keySet()) {
            BlameMatrix bm = null;

            index++;
            if(index == numPlayers) {
                bm = anyMatrix;
            } else {
                bm = new BlameMatrix();

                for (SigningKey j : results.keySet()) {
                    bm.put(j.VerificationKey(), malicious.VerificationKey(),
                                    new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.EquivocationFailure, true));
                }
            }

            test.put(i,
                    new ReturnState(false, session, null, null, bm),
                    results.get(i));

            index ++;
        }

        return test;
    }

    public TestCase DifferentTransactionSignature(int caseNo, int numPlayers, int[] weirdos, Simulator sim) {
        Set<Integer> class2 = new HashSet<>();

        for (int i = 0; i < weirdos.length; i++) {
            class2.add(weirdos[i]);
        }

        MockCoin coin1 = new MockCoin().setZ(1);
        MockCoin coin2 = new MockCoin().setZ(2);

        long amount = 17;
        SessionIdentifier session = new MockSessionIdentifier("sig" + caseNo);
        Simulator.InitialState init = sim.initialize(session, amount);

        for (int i = 1; i <= numPlayers; i ++) {
            if (class2.contains(i)) {
                init.player().initialFunds(20).coin(coin2);
            } else {
                init.player().initialFunds(20).coin(coin1);
            }
        }

        TestCase test = new TestCase(session, amount, "Broadcast phase equivocation test case.", caseNo);
        LinkedHashMap<SigningKey, ReturnState> results = init.run();

        // Results should be that every player blames all players in the other class.
        Set<SigningKey> keyClass2 = new HashSet<>();
        int index = 1;
        for (SigningKey key : results.keySet()) {
            if (class2.contains(index)) {
                keyClass2.add(key);
            }
            index ++;
        }

        for (Map.Entry<SigningKey, ReturnState> result : results.entrySet()) {
            SigningKey i = result.getKey();
            ReturnState returnState = result.getValue();

            BlameMatrix bm = new BlameMatrix();

            for (SigningKey j : results.keySet()) {
                for(SigningKey k : results.keySet()) {
                    if (keyClass2.contains(j) != keyClass2.contains(k)) {
                        bm.put(j.VerificationKey(),k.VerificationKey(),
                                new BlameMatrix.BlameEvidence(BlameMatrix.BlameReason.InvalidSignature, true));
                    }
                }
            }

            test.put(i, returnState, new ReturnState(false, session, Phase.VerificationAndSubmission, null, bm));
        }

        return test;
    }

    @Test
    // Tests for successful runs of the protocol.
    public void testSuccess() {
        MockCrypto crypto = new MockCrypto(45);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);

        // Tests for successful runs.
        int caseNo = 0;
        int minPlayers = 2; // 2;
        int maxPlayers = 12; //12;
        for (int numPlayer = minPlayers; numPlayer <= maxPlayers; numPlayer++) {
            try {
                SuccessfulRun(caseNo, numPlayer, sim).check();
                caseNo++;
            } catch (CryptographyError e) {
                Assert.fail("could not create test case " + caseNo);
            }
        }
    }

    @Test
    // Tests for players who come in without enough cash.
    public void testInsufficientFunds() {
        MockCrypto crypto = new MockCrypto(2222);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);
        int caseNo = 0;

        // Tests for players who initially have insufficient funds.
        InsufficientFunds(caseNo++, 2, new int[]{1}, new int[]{}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 2, new int[]{}, new int[]{1}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 2, new int[]{}, new int[]{}, new int[]{1}, sim).check();
        InsufficientFunds(caseNo++, 2, new int[]{1, 2}, new int[]{}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 3, new int[]{1}, new int[]{}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 5, new int[]{3}, new int[]{}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 5, new int[]{}, new int[]{4}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 5, new int[]{}, new int[]{}, new int[]{5}, sim).check();
        InsufficientFunds(caseNo++, 10, new int[]{5, 10}, new int[]{}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 10, new int[]{}, new int[]{1, 2}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 10, new int[]{}, new int[]{}, new int[]{3, 5}, sim).check();
        InsufficientFunds(caseNo++, 10, new int[]{5}, new int[]{10}, new int[]{}, sim).check();
        InsufficientFunds(caseNo++, 10, new int[]{}, new int[]{3}, new int[]{9}, sim).check();
        InsufficientFunds(caseNo, 10, new int[]{1}, new int[]{}, new int[]{2}, sim).check();
    }

    @Test
    // Tests for malicious players who send different output vectors to different players.
    public void testEquivocationBroadcast() {
        MockCrypto crypto = new MockCrypto(87);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);
        int caseNo = 0;

        // A player sends different output vectors to different players.
        EquivocateOutput(caseNo++, 3, new int[]{1}, sim);
        EquivocateOutput(caseNo++, 3, new int[]{2}, sim);
        EquivocateOutput(caseNo++, 4, new int[]{1}, sim);
        EquivocateOutput(caseNo++, 4, new int[]{1, 2}, sim);
        EquivocateOutput(caseNo, 10, new int[]{3, 5, 7}, sim);
    }

    @Test
    public void testTransactionDisagreement() {
        MockCrypto crypto = new MockCrypto(99999);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);
        int caseNo = 0;

        // Player generates a different transaction signature to everyone else.
        DifferentTransactionSignature(caseNo++, 2,  new int[]{2}, sim).check();
        DifferentTransactionSignature(caseNo++, 5,  new int[]{2}, sim).check();
        DifferentTransactionSignature(caseNo++, 5,  new int[]{2, 3}, sim).check();
        DifferentTransactionSignature(caseNo,   10, new int[]{2, 5, 6, 7}, sim).check();
    }

    @Test
    public void testEquivocationAnnounce() {
        MockCrypto crypto = new MockCrypto(87);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);
        int caseNo = 0;

        // A player sends different encryption keys to different players.
        EquivocateAnnouncement(caseNo++, 3,
                new Equivocation[]{
                        new Equivocation(1, new int[]{2})}, sim).check();
        EquivocateAnnouncement(caseNo++, 10,
                new Equivocation[]{
                        new Equivocation(1, new int[]{4, 5})}, sim).check();
        EquivocateAnnouncement(caseNo++, 10,
                new Equivocation[]{
                        new Equivocation(5, new int[]{1, 10}),
                        new Equivocation(7, new int[]{2, 8})}, sim).check();
        EquivocateAnnouncement(caseNo++, 10,
                new Equivocation[]{
                        new Equivocation(2, new int[]{3}),
                        new Equivocation(4, new int[]{5, 6}),
                        new Equivocation(8, new int[]{9})}, sim).check();
    }

    @Test
    public void testDoubleSpending() {
        MockCrypto crypto = new MockCrypto(2223);
        Simulator sim = new Simulator(new MockMessageFactory(), crypto);
        int caseNo = 0;

        // Tests for players who spend funds while the protocol is going on.
        DoubleSpend(caseNo++, new int[]{0, 0}, new int[]{1}, sim).check();
        DoubleSpend(caseNo++, new int[]{0, 1, 0}, new int[]{1}, sim).check();
        DoubleSpend(caseNo++, new int[]{0, 1, 0, 1, 0, 1, 0, 1, 0, 1}, new int[]{6}, sim).check();
        DoubleSpend(caseNo++, new int[]{0, 1, 0, 1, 0, 1, 0, 1, 0, 1}, new int[]{3, 10}, sim).check();
        DoubleSpend(caseNo++, new int[]{0, 1, 0, 1, 0, 1, 0, 1, 0, 1}, new int[]{1, 7, 8}, sim).check();
        DoubleSpend(caseNo, new int[]{0, 1, 0, 1, 0, 1, 0, 1, 0, 1}, new int[]{4, 6, 7, 8}, sim).check();

    }

    @Test
    // Tests for failures during the shuffle phase.
    public void testShuffleMalice() {

        // A player drops an address during phase 2.
        // A player drops an address and adds another one in phase 2.
        // A player drops an address and adds a duplicate in phase 2.
    }

    @Test
    public void testLies() {

        // A player lies about the equivocation check.
        // A player claims something went wrong in phase 2 when it didn't.
    }

    // Error test cases that I need to make:

    // A player disconnects at the wrong time.
    // Different combinations of these at the same time.
}
