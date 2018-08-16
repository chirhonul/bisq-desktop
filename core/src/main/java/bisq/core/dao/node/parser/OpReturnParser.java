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

import bisq.core.dao.bonding.lockup.LockupType;
import bisq.core.dao.state.blockchain.OpReturnType;
import bisq.core.dao.state.blockchain.TempTx;
import bisq.core.dao.state.blockchain.TempTxOutput;
import bisq.core.dao.state.blockchain.TxOutputType;

import bisq.common.app.DevEnv;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Utils;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Processes OpReturn output if valid and delegates validation to specific validators.
 */
@Slf4j
public class OpReturnParser {
    private final OpReturnProposalParser opReturnProposalParser;
    private final OpReturnCompReqParser opReturnCompReqParser;
    private final OpReturnBlindVoteParser opReturnBlindVoteParser;
    private final OpReturnVoteRevealParser opReturnVoteRevealParser;
    private final OpReturnLockupParser opReturnLockupParser;

    @Inject
    public OpReturnParser(OpReturnProposalParser opReturnProposalParser,
                          OpReturnCompReqParser opReturnCompReqParser,
                          OpReturnBlindVoteParser opReturnBlindVoteParser,
                          OpReturnVoteRevealParser opReturnVoteRevealParser,
                          OpReturnLockupParser opReturnLockupParser) {

        this.opReturnProposalParser = opReturnProposalParser;
        this.opReturnCompReqParser = opReturnCompReqParser;
        this.opReturnBlindVoteParser = opReturnBlindVoteParser;
        this.opReturnVoteRevealParser = opReturnVoteRevealParser;
        this.opReturnLockupParser = opReturnLockupParser;
    }

    // We only check partially the rules here as we do not know the BSQ fee at that moment which is always used when
    // we have OP_RETURN data.
    public void processOpReturnCandidate(TempTxOutput txOutput, ParsingModel parsingModel) {
        // We do not check for pubKeyScript.scriptType.NULL_DATA because that is only set if dumpBlockchainData is true
        final byte[] opReturnData = txOutput.getOpReturnData();
        if (txOutput.getValue() == 0 && opReturnData != null && opReturnData.length >= 1) {
            OpReturnType.getOpReturnType(opReturnData[0])
                    .ifPresent(parsingModel::setOpReturnTypeCandidate);
        }
    }

    public void validate(byte[] opReturnData, TempTxOutput txOutput, TempTx tx, int index, long bsqFee,
                         int blockHeight, ParsingModel parsingModel) {
        if (txOutput.getValue() == 0 &&
                index == tx.getTempTxOutputs().size() - 1 &&
                opReturnData.length >= 1) {
            final Optional<OpReturnType> optionalOpReturnType = OpReturnType.getOpReturnType(opReturnData[0]);
            if (optionalOpReturnType.isPresent()) {
                selectValidator(opReturnData, txOutput, tx, bsqFee, blockHeight, parsingModel, optionalOpReturnType.get());
            } else {
                // TODO add exception or set undefined...
                log.warn("OP_RETURN data does not match our defined types. opReturnData={}",
                        tx, Utils.HEX.encode(opReturnData));
            }
        } else {
            log.warn("OP_RETURN data does not match our rules. opReturnData={}",
                    tx, Utils.HEX.encode(opReturnData));
        }
    }

    private void selectValidator(byte[] opReturnData, TempTxOutput txOutput, TempTx tx, long bsqFee, int blockHeight,
                                 ParsingModel parsingModel, OpReturnType opReturnType) {
        switch (opReturnType) {
            case PROPOSAL:
                processProposal(opReturnData, txOutput, bsqFee, blockHeight, parsingModel);
                break;
            case COMPENSATION_REQUEST:
                processCompensationRequest(opReturnData, txOutput, bsqFee, blockHeight, parsingModel);
                break;
            case BLIND_VOTE:
                processBlindVote(opReturnData, txOutput, bsqFee, blockHeight, parsingModel);
                break;
            case VOTE_REVEAL:
                processVoteReveal(opReturnData, txOutput, blockHeight, parsingModel);
                break;
            case LOCKUP:
                processLockup(opReturnData, txOutput, blockHeight, parsingModel);
                break;
            default:
                // Should never happen as long we keep OpReturnType entries in sync with out switch case.
                final String msg = "Unsupported OpReturnType. tx=" + tx +
                        "; opReturnData=" + Utils.HEX.encode(opReturnData);
                log.error(msg);
                DevEnv.logErrorAndThrowIfDevMode(msg);
                break;
        }
    }

    private void processProposal(byte[] opReturnData, TempTxOutput txOutput, long bsqFee, int blockHeight, ParsingModel parsingModel) {
        if (opReturnProposalParser.validate(opReturnData, txOutput, bsqFee, blockHeight, parsingModel)) {
            txOutput.setTxOutputType(TxOutputType.PROPOSAL_OP_RETURN_OUTPUT);
            parsingModel.setVerifiedOpReturnType(OpReturnType.PROPOSAL);
        } else {
            log.info("We expected a proposal op_return data but it did not " +
                    "match our rules. txOutput={}; blockHeight={}", txOutput, blockHeight);
            txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
        }
    }

    private void processCompensationRequest(byte[] opReturnData, TempTxOutput txOutput, long bsqFee, int blockHeight, ParsingModel parsingModel) {
        final TempTxOutput issuanceCandidate = parsingModel.getIssuanceCandidate();
        if (opReturnCompReqParser.validate(opReturnData, txOutput, bsqFee, blockHeight, parsingModel)) {
            txOutput.setTxOutputType(TxOutputType.COMP_REQ_OP_RETURN_OUTPUT);
            if (issuanceCandidate != null)
                issuanceCandidate.setTxOutputType(TxOutputType.ISSUANCE_CANDIDATE_OUTPUT);
            parsingModel.setVerifiedOpReturnType(OpReturnType.COMPENSATION_REQUEST);
        } else {
            log.info("We expected a compensation request op_return data but it did not " +
                    "match our rules. txOutput={}; blockHeight={}", txOutput, blockHeight);
            txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);

            // If the opReturn is invalid the issuance candidate cannot become BSQ, so we set it to BTC
            if (issuanceCandidate != null)
                issuanceCandidate.setTxOutputType(TxOutputType.BTC_OUTPUT);
        }
    }

    private void processBlindVote(byte[] opReturnData, TempTxOutput txOutput, long bsqFee, int blockHeight, ParsingModel parsingModel) {
        if (opReturnBlindVoteParser.validate(opReturnData, bsqFee, blockHeight, parsingModel)) {
            txOutput.setTxOutputType(TxOutputType.BLIND_VOTE_OP_RETURN_OUTPUT);
            parsingModel.setVerifiedOpReturnType(OpReturnType.BLIND_VOTE);
        } else {
            log.info("We expected a blind vote op_return data but it did not " +
                    "match our rules. txOutput={}; blockHeight={}", txOutput, blockHeight);

            txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
            TempTxOutput blindVoteLockStakeOutput = parsingModel.getBlindVoteLockStakeOutput();
            if (blindVoteLockStakeOutput != null)
                blindVoteLockStakeOutput.setTxOutputType(TxOutputType.BTC_OUTPUT);
        }
    }

    private void processVoteReveal(byte[] opReturnData, TempTxOutput txOutput, int blockHeight, ParsingModel parsingModel) {
        if (opReturnVoteRevealParser.validate(opReturnData, blockHeight, parsingModel)) {
            txOutput.setTxOutputType(TxOutputType.VOTE_REVEAL_OP_RETURN_OUTPUT);
            parsingModel.setVerifiedOpReturnType(OpReturnType.VOTE_REVEAL);
        } else {
            log.info("We expected a vote reveal op_return data but it did not " +
                    "match our rules. txOutput={}; blockHeight={}", txOutput, blockHeight);

            txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
            TempTxOutput voteRevealUnlockStakeOutput = parsingModel.getVoteRevealUnlockStakeOutput();
            if (voteRevealUnlockStakeOutput != null)
                voteRevealUnlockStakeOutput.setTxOutputType(TxOutputType.BTC_OUTPUT);
        }
    }

    private void processLockup(byte[] opReturnData, TempTxOutput txOutput, int blockHeight, ParsingModel parsingModel) {
        Optional<LockupType> lockupType = LockupType.getLockupType(opReturnData[2]);
        int lockTime = Utilities.byteArrayToInteger(Arrays.copyOfRange(opReturnData, 3, 5));

        if (opReturnLockupParser.validate(opReturnData, lockupType, lockTime, blockHeight, parsingModel)) {
            txOutput.setTxOutputType(TxOutputType.LOCKUP_OP_RETURN_OUTPUT);
            parsingModel.setVerifiedOpReturnType(OpReturnType.LOCKUP);
            parsingModel.getTx().setLockTime(lockTime);
        } else {
            log.info("We expected a lockup op_return data but it did not " +
                    "match our rules. txOutput={}; blockHeight={}", txOutput, blockHeight);
            txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);

            // If the opReturn is invalid the lockup candidate cannot become BSQ, so we set it to BTC
            TempTxOutput lockupCandidate = parsingModel.getLockupOutput();
            if (lockupCandidate != null)
                lockupCandidate.setTxOutputType(TxOutputType.BTC_OUTPUT);
        }
    }
}
