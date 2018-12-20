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

package bisq.core.disputes.messages;

import bisq.core.disputes.Mediation;
import bisq.core.proto.CoreProtoResolver;

import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;

import io.bisq.generated.protobuffer.PB;

import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode(callSuper = true)
public final class PeerOpenedMediationMessage extends MediationMessage {
    private final Mediation mediation;
    private final NodeAddress senderNodeAddress;

    public PeerOpenedMediationMessage(Mediation mediation,
                                    NodeAddress senderNodeAddress,
                                    String uid) {
        this(mediation,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PeerOpenedMediationMessage(Mediation mediation,
                                     NodeAddress senderNodeAddress,
                                     String uid,
                                     int messageVersion) {
        super(messageVersion, uid);
        this.mediation = mediation;
        this.senderNodeAddress = senderNodeAddress;
    }

    @Override
    public PB.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setPeerOpenedMediationMessage(PB.PeerOpenedMediationMessage.newBuilder()
                        .setUid(uid)
                        .setMediation(mediation.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage()))
                .build();
    }

    public static PeerOpenedMediationMessage fromProto(PB.PeerOpenedMediationMessage proto, CoreProtoResolver coreProtoResolver, int messageVersion) {
        return new PeerOpenedMediationMessage(Mediation.fromProto(proto.getMediation(), coreProtoResolver),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion);
    }

    @Override
    public String getTradeId() {
        return mediation.getTradeId();
    }

    @Override
    public String toString() {
        return "PeerOpenedMediationMessage{" +
                "\n     mediation=" + mediation +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     PeerOpenedMediationMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                "\n} " + super.toString();
    }
}
