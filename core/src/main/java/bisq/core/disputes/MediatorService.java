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

import bisq.core.app.BisqEnvironment;
import bisq.core.filter.FilterManager;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.storage.HashMapChangedListener;

import bisq.common.app.DevEnv;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.Utilities;

import javax.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to store mediators profile and load map of mediators
 */
public class MediatorService {
    private static final Logger log = LoggerFactory.getLogger(MediatorService.class);

    private final P2PService p2PService;
    private final FilterManager filterManager;

    interface MediatorMapResultHandler {
        void handleResult(Map<String, Mediator> mediatorsMap);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MediatorService(P2PService p2PService, FilterManager filterManager) {
        this.p2PService = p2PService;
        this.filterManager = filterManager;
    }

    public void addHashSetChangedListener(HashMapChangedListener hashMapChangedListener) {
        p2PService.addHashSetChangedListener(hashMapChangedListener);
    }

    public void addMediator(Mediator mediator, final ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.debug("addMediator mediator.hashCode() " + mediator.hashCode());
        if (!BisqEnvironment.getBaseCurrencyNetwork().isMainnet() ||
                !Utilities.encodeToHex(mediator.getRegistrationPubKey()).equals(DevEnv.DEV_PRIVILEGE_PUB_KEY)) {
            boolean result = p2PService.addProtectedStorageEntry(mediator, true);
            if (result) {
                log.trace("Add mediator to network was successful. Mediator.hashCode() = " + mediator.hashCode());
                resultHandler.handleResult();
            } else {
                errorMessageHandler.handleErrorMessage("Add mediator failed");
            }
        } else {
            log.error("Attempt to publish dev mediator on mainnet.");
            errorMessageHandler.handleErrorMessage("Add mediator failed. Attempt to publish dev mediator on mainnet.");
        }
    }

    public void removeMediator(Mediator mediator, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.debug("removeMediator mediator.hashCode() " + mediator.hashCode());
        if (p2PService.removeData(mediator, true)) {
            log.trace("Remove mediator from network was successful. Mediator.hashCode() = " + mediator.hashCode());
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Remove mediator failed");
        }
    }

    P2PService getP2PService() {
        return p2PService;
    }

	// Get the list of Mediators
    public Map<NodeAddress, Mediator> getMediators() {
// For now, not worrying about banned mediators
/*
        final List<String> bannedMediators = filterManager.getFilter() != null ? filterManager.getFilter().getMediators() : null;
        if (bannedMediators != null)
            log.warn("bannedMediators=" + bannedMediators);
*/
        Set<Mediator> mediatorSet = p2PService.getDataMap().values().stream()
                .filter(data -> data.getProtectedStoragePayload() instanceof Mediator)
                .map(data -> (Mediator) data.getProtectedStoragePayload())
/*
                .filter(a -> bannedMediators == null ||
                        !bannedMediators.contains(a.getNodeAddress().getHostName()))
*/
                .collect(Collectors.toSet());

        Map<NodeAddress, Mediator> map = new HashMap<>();
        for (Mediator mediator : mediatorSet) {
            NodeAddress mediatorNodeAddress = mediator.getNodeAddress();
            if (!map.containsKey(mediatorNodeAddress))
                map.put(mediatorNodeAddress, mediator);
            else
                log.warn("mediatorAddress already exist in mediator map. Seems an mediator object is already registered with the same address.");
        }
        return map;
    }
}
