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

package bisq.core.disputes;

import bisq.core.disputes.messages.MediationCommunicationMessage;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.Contract;

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.proto.network.NetworkPayload;
import bisq.common.storage.Storage;
import bisq.common.util.Utilities;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Date;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
@EqualsAndHashCode
@Getter
public final class Mediation implements NetworkPayload {
    private final String tradeId;
    private final String id;
    private final int traderId;
    private final boolean mediationOpenerIsBuyer;
    private final boolean mediationOpenerIsMaker;
    // PubKeyRing of trader who opened the mediation
    private final PubKeyRing traderPubKeyRing;
    private final long tradeDate;
    private final Contract contract;
    @Nullable
    private final byte[] contractHash;
    @Nullable
    private final byte[] depositTxSerialized;
// TODO: Change to alternative since there is no automatic payout with Mediation
//    @Nullable
//    private final byte[] payoutTxSerialized;
    @Nullable
    private final String depositTxId;
// TODO: Change to alternative since there is no automatic payout with Mediation
//    @Nullable
//    private final String payoutTxId;
    private final String contractAsJson;
    @Nullable
    private final String makerContractSignature;
    @Nullable
    private final String takerContractSignature;
    private final PubKeyRing mediatorPubKeyRing;
    private final boolean isSupportTicket;
    private final ObservableList<MediationCommunicationMessage> mediationCommunicationMessages = FXCollections.observableArrayList();
    private BooleanProperty isClosedProperty = new SimpleBooleanProperty();
    // mediationResultProperty.get is Nullable!
    private ObjectProperty<MediationResult> mediationResultProperty = new SimpleObjectProperty<>();
    @Nullable
// TODO: Change for mediation since there is no payout
//    private String mediationPayoutTxId;

    private long openingDate;

    transient private Storage<MediationList> storage;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Mediation(Storage<MediationList> storage,
                   String tradeId,
                   int traderId,
                   boolean mediationOpenerIsBuyer,
                   boolean mediationOpenerIsMaker,
                   PubKeyRing traderPubKeyRing,
                   long tradeDate,
                   Contract contract,
                   @Nullable byte[] contractHash,
                   @Nullable byte[] depositTxSerialized,
// TODO: Change to alternative since there is no automatic payout with Mediation
//                   @Nullable byte[] payoutTxSerialized,
                   @Nullable String depositTxId,
// TODO: Change to alternative since there is no automatic payout with Mediation
//                   @Nullable String payoutTxId,
                   String contractAsJson,
                   @Nullable String makerContractSignature,
                   @Nullable String takerContractSignature,
                   @Nullable PubKeyRing mediatorPubKeyRing,
                   boolean isSupportTicket) {
        this(tradeId,
             traderId,
             mediationOpenerIsBuyer,
             mediationOpenerIsMaker,
             traderPubKeyRing,
             tradeDate,
             contract,
             contractHash,
             depositTxSerialized,
// TODO: Change to alternative since there is no automatic payout with Mediation
//             payoutTxSerialized,
             depositTxId,
// TODO: Change to alternative since there is no automatic payout with Mediation
//             payoutTxId,
             contractAsJson,
             makerContractSignature,
             takerContractSignature,
             mediatorPubKeyRing,
             isSupportTicket);
        this.storage = storage;
        openingDate = new Date().getTime();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Mediation(String tradeId,
                   int traderId,
                   boolean mediationOpenerIsBuyer,
                   boolean mediationOpenerIsMaker,
                   PubKeyRing traderPubKeyRing,
                   long tradeDate,
                   Contract contract,
                   @Nullable byte[] contractHash,
                   @Nullable byte[] depositTxSerialized,
// TODO: Change to alternative since there is no automatic payout with Mediation
//                   @Nullable byte[] payoutTxSerialized,
                   @Nullable String depositTxId,
// TODO: Change to alternative since there is no automatic payout with Mediation
//                   @Nullable String payoutTxId,
                   String contractAsJson,
                   @Nullable String makerContractSignature,
                   @Nullable String takerContractSignature,
                   PubKeyRing mediatorPubKeyRing,
                   boolean isSupportTicket) {
        this.tradeId = tradeId;
        this.traderId = traderId;
        this.mediationOpenerIsBuyer = mediationOpenerIsBuyer;
        this.mediationOpenerIsMaker = mediationOpenerIsMaker;
        this.traderPubKeyRing = traderPubKeyRing;
        this.tradeDate = tradeDate;
        this.contract = contract;
        this.contractHash = contractHash;
        this.depositTxSerialized = depositTxSerialized;
// TODO: Change to alternative since there is no automatic payout with Mediation
//        this.payoutTxSerialized = payoutTxSerialized;
        this.depositTxId = depositTxId;
// TODO: Change to alternative since there is no automatic payout with Mediation
//        this.payoutTxId = payoutTxId;
        this.contractAsJson = contractAsJson;
        this.makerContractSignature = makerContractSignature;
        this.takerContractSignature = takerContractSignature;
        this.mediatorPubKeyRing = mediatorPubKeyRing;
        this.isSupportTicket = isSupportTicket;

        id = tradeId + "_" + traderId;
    }

    @Override
    public PB.Mediation toProtoMessage() {
        PB.Mediation.Builder builder = PB.Mediation.newBuilder()
                .setTradeId(tradeId)
                .setTraderId(traderId)
                .setMediationOpenerIsBuyer(mediationOpenerIsBuyer)
                .setMediationOpenerIsMaker(mediationOpenerIsMaker)
                .setTraderPubKeyRing(traderPubKeyRing.toProtoMessage())
                .setTradeDate(tradeDate)
                .setContract(contract.toProtoMessage())
                .setContractAsJson(contractAsJson)
                .setMediatorPubKeyRing(mediatorPubKeyRing.toProtoMessage())
                .setIsSupportTicket(isSupportTicket)
                .addAllMediationCommunicationMessages(mediationCommunicationMessages.stream()
                        .map(msg -> msg.toProtoNetworkEnvelope().getMediationCommunicationMessage())
                        .collect(Collectors.toList()))
                .setIsClosed(isClosedProperty.get())
                .setOpeningDate(openingDate)
                .setId(id);

        Optional.ofNullable(contractHash).ifPresent(e -> builder.setContractHash(ByteString.copyFrom(e)));
        Optional.ofNullable(depositTxSerialized).ifPresent(e -> builder.setDepositTxSerialized(ByteString.copyFrom(e)));
// TODO: Change to alternative since there is no automatic payout with Mediation
//        Optional.ofNullable(payoutTxSerialized).ifPresent(e -> builder.setPayoutTxSerialized(ByteString.copyFrom(e)));
        Optional.ofNullable(depositTxId).ifPresent(builder::setDepositTxId);
// TODO: Change to alternative since there is no automatic payout with Mediation
//        Optional.ofNullable(payoutTxId).ifPresent(builder::setPayoutTxId);
//        Optional.ofNullable(mediationPayoutTxId).ifPresent(builder::setMediationPayoutTxId);
        Optional.ofNullable(makerContractSignature).ifPresent(builder::setMakerContractSignature);
        Optional.ofNullable(takerContractSignature).ifPresent(builder::setTakerContractSignature);
        Optional.ofNullable(mediationResultProperty.get()).ifPresent(result -> builder.setMediationResult(mediationResultProperty.get().toProtoMessage()));
        return builder.build();
    }

    public static Mediation fromProto(PB.Mediation proto, CoreProtoResolver coreProtoResolver) {
        final Mediation mediation = new Mediation(proto.getTradeId(),
                proto.getTraderId(),
                proto.getMediationOpenerIsBuyer(),
                proto.getMediationOpenerIsMaker(),
                PubKeyRing.fromProto(proto.getTraderPubKeyRing()),
                proto.getTradeDate(),
                Contract.fromProto(proto.getContract(), coreProtoResolver),
                ProtoUtil.byteArrayOrNullFromProto(proto.getContractHash()),
                ProtoUtil.byteArrayOrNullFromProto(proto.getDepositTxSerialized()),
// TODO: Change to alternative since there is no automatic payout with Mediation
//                ProtoUtil.byteArrayOrNullFromProto(proto.getPayoutTxSerialized()),
                ProtoUtil.stringOrNullFromProto(proto.getDepositTxId()),
// TODO: Change to alternative since there is no automatic payout with Mediation
//                ProtoUtil.stringOrNullFromProto(proto.getPayoutTxId()),
                proto.getContractAsJson(),
                ProtoUtil.stringOrNullFromProto(proto.getMakerContractSignature()),
                ProtoUtil.stringOrNullFromProto(proto.getTakerContractSignature()),
                PubKeyRing.fromProto(proto.getMediatorPubKeyRing()),
                proto.getIsSupportTicket());

        mediation.mediationCommunicationMessages.addAll(proto.getMediationCommunicationMessagesList().stream()
                .map(MediationCommunicationMessage::fromPayloadProto)
                .collect(Collectors.toList()));

        mediation.openingDate = proto.getOpeningDate();
        mediation.isClosedProperty.set(proto.getIsClosed());
        if (proto.hasMediationResult())
            mediation.mediationResultProperty.set(MediationResult.fromProto(proto.getMediationResult()));
// TODO: Replace with alternative since there isn't a payout at the end of Mediation
//        mediation.mediationPayoutTxId = ProtoUtil.stringOrNullFromProto(proto.getMediationPayoutTxId());
        return mediation;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addMediationCommunicationMessage(MediationCommunicationMessage mediationCommunicationMessage) {
        if (!mediationCommunicationMessages.contains(mediationCommunicationMessage)) {
            mediationCommunicationMessages.add(mediationCommunicationMessage);
            storage.queueUpForSave();
        } else {
            log.error("mediationDirectMessage already exists");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    // In case we get the object via the network storage is not set as its transient, so we need to set it.
    public void setStorage(Storage<MediationList> storage) {
        this.storage = storage;
    }

    public void setIsClosed(boolean isClosed) {
        boolean changed = this.isClosedProperty.get() != isClosed;
        this.isClosedProperty.set(isClosed);
        if (changed)
            storage.queueUpForSave();
    }

    public void setMediationResult(MediationResult mediationResult) {
        boolean changed = mediationResultProperty.get() == null || !mediationResultProperty.get().equals(mediationResult);
        mediationResultProperty.set(mediationResult);
        if (changed)
            storage.queueUpForSave();
    }

// TODO: Change for mediation since there is no payout
/*
    @SuppressWarnings("NullableProblems")
    public void setMediationPayoutTxId(String mediationPayoutTxId) {
        boolean changed = this.mediationPayoutTxId == null || !this.mediationPayoutTxId.equals(mediationPayoutTxId);
        this.mediationPayoutTxId = mediationPayoutTxId;
        if (changed)
            storage.queueUpForSave();
    }
*/


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getShortTradeId() {
        return Utilities.getShortId(tradeId);
    }

    public ReadOnlyBooleanProperty isClosedProperty() {
        return isClosedProperty;
    }

    public ReadOnlyObjectProperty<MediationResult> mediationResultProperty() {
        return mediationResultProperty;
    }

    public Date getTradeDate() {
        return new Date(tradeDate);
    }

    public Date getOpeningDate() {
        return new Date(openingDate);
    }

    public boolean isClosed() {
        return isClosedProperty.get();
    }

    @Override
    public String toString() {
        return "Mediation{" +
                "tradeId='" + tradeId + '\'' +
                ", id='" + id + '\'' +
                ", traderId=" + traderId +
                ", mediationOpenerIsBuyer=" + mediationOpenerIsBuyer +
                ", mediationOpenerIsMaker=" + mediationOpenerIsMaker +
                ", openingDate=" + openingDate +
                ", traderPubKeyRing=" + traderPubKeyRing +
                ", tradeDate=" + tradeDate +
                ", contract=" + contract +
                ", contractHash=" + Utilities.bytesAsHexString(contractHash) +
// TODO: Change to alternative since there is no automatic payout with Mediation
//                ", depositTxSerialized=" + Utilities.bytesAsHexString(depositTxSerialized) +
                ", payoutTxSerialized not displayed for privacy reasons..." +
                ", depositTxId='" + depositTxId + '\'' +
// TODO: Change to alternative since there is no automatic payout with Mediation
//                ", payoutTxId='" + payoutTxId + '\'' +
                ", contractAsJson='" + contractAsJson + '\'' +
                ", makerContractSignature='" + makerContractSignature + '\'' +
                ", takerContractSignature='" + takerContractSignature + '\'' +
                ", mediatorPubKeyRing=" + mediatorPubKeyRing +
                ", isSupportTicket=" + isSupportTicket +
                ", mediationCommunicationMessages=" + mediationCommunicationMessages +
                ", isClosed=" + isClosedProperty.get() +
                ", mediationResult=" + mediationResultProperty.get() +
// TODO: Change for mediation since there is no payout
//                ", mediationPayoutTxId='" + mediationPayoutTxId + '\'' +
                ", isClosedProperty=" + isClosedProperty +
                ", mediationResultProperty=" + mediationResultProperty +
                '}';
    }
}
