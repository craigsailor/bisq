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

import bisq.common.proto.ProtoUtil;
import bisq.common.proto.network.NetworkPayload;
import bisq.common.util.Utilities;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import org.bitcoinj.core.Coin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Date;
import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@EqualsAndHashCode
@Getter
@Slf4j
public final class MediationResult implements NetworkPayload {

    public enum Winner {
        BUYER,
        SELLER
    }

    public enum Reason {
        OTHER,
        BUG,
        USABILITY,
        SCAM,
        PROTOCOL_VIOLATION,
        NO_REPLY,
        BANK_PROBLEMS
    }

    private final String tradeId;
    private final int traderId;
    @Setter
    @Nullable
    private Winner winner;
    private int reasonOrdinal = Reason.OTHER.ordinal();
    private final BooleanProperty tamperProofEvidenceProperty = new SimpleBooleanProperty();
    private final BooleanProperty idVerificationProperty = new SimpleBooleanProperty();
    private final BooleanProperty screenCastProperty = new SimpleBooleanProperty();
    private final StringProperty summaryNotesProperty = new SimpleStringProperty("");
    @Setter
    @Nullable
    private MediationCommunicationMessage mediationCommunicationMessage;
    @Setter
    @Nullable
    private byte[] mediatorSignature;
    private long buyerPayoutAmount;
    private long sellerPayoutAmount;
    @Setter
    @Nullable
    private byte[] mediatorPubKey;
    private long closeDate;
    @Setter
    private boolean isLoserPublisher;

    public MediationResult(String tradeId, int traderId) {
        this.tradeId = tradeId;
        this.traderId = traderId;
    }

    public MediationResult(String tradeId,
                         int traderId,
                         @Nullable Winner winner,
                         int reasonOrdinal,
                         boolean tamperProofEvidence,
                         boolean idVerification,
                         boolean screenCast,
                         String summaryNotes,
                         @Nullable MediationCommunicationMessage mediationCommunicationMessage,
                         @Nullable byte[] mediatorSignature,
                         long buyerPayoutAmount,
                         long sellerPayoutAmount,
                         @Nullable byte[] mediatorPubKey,
                         long closeDate,
                         boolean isLoserPublisher) {
        this.tradeId = tradeId;
        this.traderId = traderId;
        this.winner = winner;
        this.reasonOrdinal = reasonOrdinal;
        this.tamperProofEvidenceProperty.set(tamperProofEvidence);
        this.idVerificationProperty.set(idVerification);
        this.screenCastProperty.set(screenCast);
        this.summaryNotesProperty.set(summaryNotes);
        this.mediationCommunicationMessage = mediationCommunicationMessage;
        this.mediatorSignature = mediatorSignature;
        this.buyerPayoutAmount = buyerPayoutAmount;
        this.sellerPayoutAmount = sellerPayoutAmount;
        this.mediatorPubKey = mediatorPubKey;
        this.closeDate = closeDate;
        this.isLoserPublisher = isLoserPublisher;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static MediationResult fromProto(PB.MediationResult proto) {
        return new MediationResult(proto.getTradeId(),
                proto.getTraderId(),
                ProtoUtil.enumFromProto(MediationResult.Winner.class, proto.getWinner().name()),
                proto.getReasonOrdinal(),
                proto.getTamperProofEvidence(),
                proto.getIdVerification(),
                proto.getScreenCast(),
                proto.getSummaryNotes(),
                proto.getMediationCommunicationMessage() == null ? null : MediationCommunicationMessage.fromPayloadProto(proto.getMediationCommunicationMessage()),
                proto.getMediatorSignature().toByteArray(),
                proto.getBuyerPayoutAmount(),
                proto.getSellerPayoutAmount(),
                proto.getMediatorPubKey().toByteArray(),
                proto.getCloseDate(),
                proto.getIsLoserPublisher());
    }

    @Override
    public PB.MediationResult toProtoMessage() {
        final PB.MediationResult.Builder builder = PB.MediationResult.newBuilder()
                .setTradeId(tradeId)
                .setTraderId(traderId)
                .setReasonOrdinal(reasonOrdinal)
                .setTamperProofEvidence(tamperProofEvidenceProperty.get())
                .setIdVerification(idVerificationProperty.get())
                .setScreenCast(screenCastProperty.get())
                .setSummaryNotes(summaryNotesProperty.get())
                .setBuyerPayoutAmount(buyerPayoutAmount)
                .setSellerPayoutAmount(sellerPayoutAmount)
                .setCloseDate(closeDate)
                .setIsLoserPublisher(isLoserPublisher);

        Optional.ofNullable(mediatorSignature).ifPresent(mediatorSignature -> builder.setMediatorSignature(ByteString.copyFrom(mediatorSignature)));
        Optional.ofNullable(mediatorPubKey).ifPresent(mediatorPubKey -> builder.setMediatorPubKey(ByteString.copyFrom(mediatorPubKey)));
        Optional.ofNullable(winner).ifPresent(result -> builder.setWinner(PB.MediationResult.Winner.valueOf(winner.name())));
        Optional.ofNullable(mediationCommunicationMessage).ifPresent(mediationCommunicationMessage ->
                builder.setMediationCommunicationMessage(mediationCommunicationMessage.toProtoNetworkEnvelope().getMediationCommunicationMessage()));

        return builder.build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BooleanProperty tamperProofEvidenceProperty() {
        return tamperProofEvidenceProperty;
    }

    public BooleanProperty idVerificationProperty() {
        return idVerificationProperty;
    }

    public BooleanProperty screenCastProperty() {
        return screenCastProperty;
    }

    public void setReason(Reason reason) {
        this.reasonOrdinal = reason.ordinal();
    }

    public Reason getReason() {
        if (reasonOrdinal < Reason.values().length)
            return Reason.values()[reasonOrdinal];
        else
            return Reason.OTHER;
    }

    public void setSummaryNotes(String summaryNotes) {
        this.summaryNotesProperty.set(summaryNotes);
    }

    public StringProperty summaryNotesProperty() {
        return summaryNotesProperty;
    }

    public void setBuyerPayoutAmount(Coin buyerPayoutAmount) {
        this.buyerPayoutAmount = buyerPayoutAmount.value;
    }

    public Coin getBuyerPayoutAmount() {
        return Coin.valueOf(buyerPayoutAmount);
    }

    public void setSellerPayoutAmount(Coin sellerPayoutAmount) {
        this.sellerPayoutAmount = sellerPayoutAmount.value;
    }

    public Coin getSellerPayoutAmount() {
        return Coin.valueOf(sellerPayoutAmount);
    }

    public void setCloseDate(Date closeDate) {
        this.closeDate = closeDate.getTime();
    }

    public Date getCloseDate() {
        return new Date(closeDate);
    }

    @Override
    public String toString() {
        return "MediationResult{" +
                "\n     tradeId='" + tradeId + '\'' +
                ",\n     traderId=" + traderId +
                ",\n     winner=" + winner +
                ",\n     reasonOrdinal=" + reasonOrdinal +
                ",\n     tamperProofEvidenceProperty=" + tamperProofEvidenceProperty +
                ",\n     idVerificationProperty=" + idVerificationProperty +
                ",\n     screenCastProperty=" + screenCastProperty +
                ",\n     summaryNotesProperty=" + summaryNotesProperty +
                ",\n     mediationCommunicationMessage=" + mediationCommunicationMessage +
                ",\n     mediatorSignature=" + Utilities.bytesAsHexString(mediatorSignature) +
                ",\n     buyerPayoutAmount=" + buyerPayoutAmount +
                ",\n     sellerPayoutAmount=" + sellerPayoutAmount +
                ",\n     mediatorPubKey=" + Utilities.bytesAsHexString(mediatorPubKey) +
                ",\n     closeDate=" + closeDate +
                ",\n     isLoserPublisher=" + isLoserPublisher +
                "\n}";
    }
}
