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

import bisq.core.proto.CoreProtoResolver;

import bisq.common.proto.ProtoUtil;
import bisq.common.proto.persistable.PersistableEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.Message;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ToString
/**
 * Holds a List of Mediation objects.
 *
 * Calls to the List are delegated because this class intercepts the add/remove calls so changes
 * can be saved to disc.
 */
public final class MediationList implements PersistableEnvelope, PersistedDataHost {
    transient private final Storage<MediationList> storage;
    @Getter
    private final ObservableList<Mediation> list = FXCollections.observableArrayList();

    public MediationList(Storage<MediationList> storage) {
        this.storage = storage;
    }

    @Override
    public void readPersisted() {
        MediationList persisted = storage.initAndGetPersisted(this, 50);
        if (persisted != null)
            list.addAll(persisted.getList());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private MediationList(Storage<MediationList> storage, List<Mediation> list) {
        this.storage = storage;
        this.list.addAll(list);
    }

    @Override
    public Message toProtoMessage() {
        return PB.PersistableEnvelope.newBuilder().setMediationList(PB.MediationList.newBuilder()
                .addAllMediation(ProtoUtil.collectionToProto(list))).build();
    }

    public static MediationList fromProto(PB.MediationList proto,
                                        CoreProtoResolver coreProtoResolver,
                                        Storage<MediationList> storage) {
        log.debug("MediationList fromProto of {} ", proto);

        List<Mediation> list = proto.getMediationList().stream()
                .map(mediationProto -> Mediation.fromProto(mediationProto, coreProtoResolver))
                .collect(Collectors.toList());
        list.forEach(e -> e.setStorage(storage));
        return new MediationList(storage, list);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean add(Mediation mediation) {
        if (!list.contains(mediation)) {
            boolean changed = list.add(mediation);
            if (changed)
                persist();
            return changed;
        } else {
            return false;
        }
    }

    public boolean remove(Object mediation) {
        //noinspection SuspiciousMethodCalls
        boolean changed = list.remove(mediation);
        if (changed)
            persist();
        return changed;
    }

    public void persist() {
        storage.queueUpForSave();
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "SuspiciousMethodCalls"})
    public boolean contains(Object o) {
        return list.contains(o);
    }

    public Stream<Mediation> stream() {
        return list.stream();
    }
}
