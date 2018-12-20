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

import bisq.core.disputes.MediationResult;

import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;

import io.bisq.generated.protobuffer.PB;

import lombok.EqualsAndHashCode;
import lombok.Value;

import static com.google.common.base.Preconditions.checkArgument;

@Value
@EqualsAndHashCode(callSuper = true)
public final class MediationResultMessage extends MediationMessage {
    private final MediationResult mediationResult;
    private final NodeAddress senderNodeAddress;

    public MediationResultMessage(MediationResult mediationResult,
                                NodeAddress senderNodeAddress,
                                String uid) {
        this(mediationResult,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private MediationResultMessage(MediationResult mediationResult,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 int messageVersion) {
        super(messageVersion, uid);
        this.mediationResult = mediationResult;
        this.senderNodeAddress = senderNodeAddress;
    }

    @Override
    public PB.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setMediationResultMessage(PB.MediationResultMessage.newBuilder()
                        .setMediationResult(mediationResult.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setUid(uid))
                .build();
    }

    public static MediationResultMessage fromProto(PB.MediationResultMessage proto, int messageVersion) {
        checkArgument(proto.hasMediationResult(), "MediationResult must be set");
        return new MediationResultMessage(MediationResult.fromProto(proto.getMediationResult()),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion);
    }

    @Override
    public String getTradeId() {
        return mediationResult.getTradeId();
    }

    @Override
    public String toString() {
        return "MediationResultMessage{" +
                "\n     mediationResult=" + mediationResult +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     MediationResultMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                "\n} " + super.toString();
    }
}
