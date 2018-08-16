/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.node.parser;

import bisq.core.dao.state.blockchain.OpReturnType;
import bisq.core.dao.state.blockchain.TempTx;
import bisq.core.dao.state.blockchain.TempTxOutput;
import bisq.core.dao.state.blockchain.TxOutput;

import java.util.HashSet;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

/**
 * This model holds shared data during parsing of a tx. It is used by the various
 * controllers to store and retrieve particular state required for validation.
 * Access to the model happens only from one thread.
 */
@Getter
@Setter
class ParsingModel {
    /**
     * The different possible states for an input used at the vote reveal tx.
     */
    enum VoteRevealInputState {
        UNKNOWN, VALID, INVALID
    }
    private TempTx tx;
    private long availableInputValue = 0;
    private long burntBondValue = 0;
    @Nullable
    private TempTxOutput issuanceCandidate;
    @Nullable
    private TempTxOutput blindVoteLockStakeOutput;
    @Nullable
    private TempTxOutput voteRevealUnlockStakeOutput;
    @Nullable
    private TempTxOutput lockupOutput;
    private boolean bsqOutputFound;

    // We use here TxOutput as we do not alter it but take it from the BsqState
    @Nullable
    private TxOutput spentLockupTxOutput;
    private int unlockBlockHeight;

    //TODO ???
    // We use here TxOutput as we do not alter it but take it from the BsqState
    @Nullable
    private Set<TxOutput> spentUnlockConnectedTxOutputs = new HashSet<>();

    // That will be set preliminary at first parsing the last output. Not guaranteed
    // that it is a valid BSQ tx at that moment.

    @Nullable
    private OpReturnType opReturnTypeCandidate;

    private VoteRevealInputState voteRevealInputState = VoteRevealInputState.UNKNOWN;

    // At end of parsing when we do the full validation we set the type here
    @Nullable
    private OpReturnType verifiedOpReturnType;

    ParsingModel(TempTx tx) {
        this.tx = tx;
    }

    public void addToInputValue(long value) {
        this.availableInputValue += value;
    }

    public void subtractFromInputValue(long value) {
        this.availableInputValue -= value;
    }

    public boolean isInputValuePositive() {
        return availableInputValue > 0;
    }

    public void burnBond(long value) {
        subtractFromInputValue(value);
        burntBondValue += value;
    }
}
