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

package bisq.desktop.main.account.mediatorregistration;

import bisq.desktop.common.model.ActivatableViewModel;

//import bisq.core.arbitration.Arbitrator;
//import bisq.core.arbitration.ArbitratorManager;
import bisq.core.disputes.Mediator;
import bisq.core.disputes.MediatorManager;
import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.locale.LanguageUtil;
import bisq.core.user.User;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;

import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;

import com.google.inject.Inject;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.Date;

class MediatorRegistrationViewModel extends ActivatableViewModel {
    private final MediatorManager mediatorManager;
    private final User user;
    private final P2PService p2PService;
    private final BtcWalletService walletService;
    private final KeyRing keyRing;

    final BooleanProperty registrationEditDisabled = new SimpleBooleanProperty(true);
    final BooleanProperty revokeButtonDisabled = new SimpleBooleanProperty(true);
    final ObjectProperty<Mediator> myMediatorProperty = new SimpleObjectProperty<>();

    final ObservableList<String> languageCodes = FXCollections.observableArrayList(LanguageUtil.getDefaultLanguageLocaleAsCode());
    final ObservableList<String> allLanguageCodes = FXCollections.observableArrayList(LanguageUtil.getAllLanguageCodes());
    private boolean allDataValid;
    private final MapChangeListener<NodeAddress, Mediator> mediatorMapChangeListener;
    private ECKey registrationKey;
    final StringProperty registrationPubKeyAsHex = new SimpleStringProperty();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MediatorRegistrationViewModel(MediatorManager mediatorManager,
                                           User user,
                                           P2PService p2PService,
                                           BtcWalletService walletService,
                                           KeyRing keyRing) {
        this.mediatorManager = mediatorManager;
        this.user = user;
        this.p2PService = p2PService;
        this.walletService = walletService;
        this.keyRing = keyRing;

        mediatorMapChangeListener = new MapChangeListener<NodeAddress, Mediator>() {
            @Override
            public void onChanged(Change<? extends NodeAddress, ? extends Mediator> change) {
                Mediator myRegisteredMediator = user.getRegisteredMediator();
                myMediatorProperty.set(myRegisteredMediator);

                // We don't reset the languages in case of revocation, as its likely that the mediator will use the same again when he re-activate
                // registration later
                if (myRegisteredMediator != null)
                    languageCodes.setAll(myRegisteredMediator.getLanguageCodes());

                updateDisableStates();
            }
        };
    }

    @Override
    protected void activate() {
        mediatorManager.getMediatorsObservableMap().addListener(mediatorMapChangeListener);
        Mediator myRegisteredMediator = user.getRegisteredMediator();
        myMediatorProperty.set(myRegisteredMediator);
        updateDisableStates();
    }

    @Override
    protected void deactivate() {
        mediatorManager.getMediatorsObservableMap().removeListener(mediatorMapChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onAddLanguage(String code) {
        if (code != null && !languageCodes.contains(code))
            languageCodes.add(code);

        updateDisableStates();
    }

    void onRemoveLanguage(String code) {
        if (code != null && languageCodes.contains(code))
            languageCodes.remove(code);

        updateDisableStates();
    }

    boolean setPrivKeyAndCheckPubKey(String privKeyString) {
        ECKey registrationKey = mediatorManager.getRegistrationKey(privKeyString);
        if (registrationKey != null) {
            String _registrationPubKeyAsHex = Utils.HEX.encode(registrationKey.getPubKey());
            boolean isKeyValid = mediatorManager.isPublicKeyInList(_registrationPubKeyAsHex);
            if (isKeyValid) {
                this.registrationKey = registrationKey;
                registrationPubKeyAsHex.set(_registrationPubKeyAsHex);
            }
            updateDisableStates();
            return isKeyValid;
        } else {
            updateDisableStates();
            return false;
        }
    }

    void onRegister(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        updateDisableStates();
        if (allDataValid) {
            AddressEntry mediatorDepositAddressEntry = walletService.getMediatorAddressEntry();
            String registrationSignature = mediatorManager.signStorageSignaturePubKey(registrationKey);
            // TODO not impl in UI
            String emailAddress = null;
            @SuppressWarnings("ConstantConditions")

            Mediator mediator = new Mediator(
                    p2PService.getAddress(),
                    keyRing.getPubKeyRing(),
                    new ArrayList<>(languageCodes),
                    new Date().getTime(),
                    registrationKey.getPubKey(),
                    registrationSignature,
                    emailAddress,
                    null,
                    null
            );
// From Arbitrator code
/*
            Mediator mediator = new Mediator(
                    p2PService.getAddress(),
                    mediatorDepositAddressEntry.getPubKey(),
                    mediatorDepositAddressEntry.getAddressString(),
                    keyRing.getPubKeyRing(),
                    new ArrayList<>(languageCodes),
                    new Date().getTime(),
                    registrationKey.getPubKey(),
                    registrationSignature,
                    emailAddress,
                    null,
                    null
            );
*/

            mediatorManager.addMediator(mediator,
                    () -> {
                        updateDisableStates();
                        resultHandler.handleResult();
                    },
                    (errorMessage) -> {
                        updateDisableStates();
                        errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        }
    }


    void onRevoke(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        mediatorManager.removeMediator(
                () -> {
                    updateDisableStates();
                    resultHandler.handleResult();
                },
                (errorMessage) -> {
                    updateDisableStates();
                    errorMessageHandler.handleErrorMessage(errorMessage);
                });
    }

    private void updateDisableStates() {
        allDataValid = languageCodes != null && languageCodes.size() > 0 && registrationKey != null && registrationPubKeyAsHex.get() != null;
        registrationEditDisabled.set(!allDataValid || myMediatorProperty.get() != null);
        revokeButtonDisabled.set(!allDataValid || myMediatorProperty.get() == null);
    }

    boolean isBootstrapped() {
        return p2PService.isBootstrapped();
    }
}
