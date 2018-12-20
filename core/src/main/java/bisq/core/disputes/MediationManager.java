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

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.proto.persistable.PersistenceProtoResolver;
import bisq.common.storage.Storage;
import bisq.common.util.Tuple2;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.disputes.messages.MediationCommunicationMessage;
import bisq.core.disputes.messages.MediationMessage;
import bisq.core.disputes.messages.MediationResultMessage;
import bisq.core.disputes.messages.OpenNewMediationMessage;
import bisq.core.disputes.messages.PeerOpenedMediationMessage;
import bisq.core.locale.Res;
import bisq.core.offer.OpenOfferManager;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendMailboxMessageListener;
import com.google.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javax.annotation.Nullable;
import javax.inject.Named;
import lombok.Getter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MediationManager implements PersistedDataHost {
  private static final Logger log = LoggerFactory.getLogger(MediationManager.class);

  private final TradeWalletService tradeWalletService;
  private final BtcWalletService walletService;
  private final WalletsSetup walletsSetup;
  private final TradeManager tradeManager;
  private final ClosedTradableManager closedTradableManager;
  private final OpenOfferManager openOfferManager;
  private final P2PService p2PService;
  private final KeyRing keyRing;
  private final Storage<MediationList> mediationStorage;
  private MediationList mediations;
  private final String mediationInfo;
  private final CopyOnWriteArraySet<DecryptedMessageWithPubKey> decryptedMailboxMessageWithPubKeys =
      new CopyOnWriteArraySet<>();
  private final CopyOnWriteArraySet<DecryptedMessageWithPubKey> decryptedDirectMessageWithPubKeys =
      new CopyOnWriteArraySet<>();
  private final Map<String, Mediation> openMediations;
  private final Map<String, Mediation> closedMediations;
  private final Map<String, Timer> delayMsgMap = new HashMap<>();

  private final Map<String, Subscription> mediationIsClosedSubscriptionsMap = new HashMap<>();
  @Getter private final IntegerProperty numOpenMediations = new SimpleIntegerProperty();

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Constructor
  ///////////////////////////////////////////////////////////////////////////////////////////

  @Inject
  public MediationManager(
      P2PService p2PService,
      TradeWalletService tradeWalletService,
      BtcWalletService walletService,
      WalletsSetup walletsSetup,
      TradeManager tradeManager,
      ClosedTradableManager closedTradableManager,
      OpenOfferManager openOfferManager,
      KeyRing keyRing,
      PersistenceProtoResolver persistenceProtoResolver,
      @Named(Storage.STORAGE_DIR) File storageDir) {
    this.p2PService = p2PService;
    this.tradeWalletService = tradeWalletService;
    this.walletService = walletService;
    this.walletsSetup = walletsSetup;
    this.tradeManager = tradeManager;
    this.closedTradableManager = closedTradableManager;
    this.openOfferManager = openOfferManager;
    this.keyRing = keyRing;

    mediationStorage = new Storage<>(storageDir, persistenceProtoResolver);

    openMediations = new HashMap<>();
    closedMediations = new HashMap<>();

    mediationInfo = Res.get("support.initialInfo");

    // We get first the message handler called then the onBootstrapped
    p2PService.addDecryptedDirectMessageListener(
        (decryptedMessageWithPubKey, senderAddress) -> {
          decryptedDirectMessageWithPubKeys.add(decryptedMessageWithPubKey);
          tryApplyMessages();
        });
    p2PService.addDecryptedMailboxListener(
        (decryptedMessageWithPubKey, senderAddress) -> {
          decryptedMailboxMessageWithPubKeys.add(decryptedMessageWithPubKey);
          tryApplyMessages();
        });
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // API
  ///////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public void readPersisted() {
    mediations = new MediationList(mediationStorage);
    mediations.readPersisted();
    mediations.stream().forEach(mediation -> mediation.setStorage(mediationStorage));
  }

  public void onAllServicesInitialized() {
    p2PService.addP2PServiceListener(
        new BootstrapListener() {
          @Override
          public void onUpdatedDataReceived() {
            tryApplyMessages();
          }
        });

    walletsSetup
        .downloadPercentageProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              if (walletsSetup.isDownloadComplete()) tryApplyMessages();
            });

    walletsSetup
        .numPeersProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              if (walletsSetup.hasSufficientPeersForBroadcast()) tryApplyMessages();
            });

    tryApplyMessages();

    cleanupMediations();

    mediations
        .getList()
        .addListener(
            (ListChangeListener<Mediation>)
                change -> {
                  change.next();
                  onMediationsChangeListener(change.getAddedSubList(), change.getRemoved());
                });
    onMediationsChangeListener(mediations.getList(), null);
  }

  private void onMediationsChangeListener(
      List<? extends Mediation> addedList, @Nullable List<? extends Mediation> removedList) {
    if (removedList != null) {
      removedList.forEach(
          mediation -> {
            String id = mediation.getId();
            if (mediationIsClosedSubscriptionsMap.containsKey(id)) {
              mediationIsClosedSubscriptionsMap.get(id).unsubscribe();
              mediationIsClosedSubscriptionsMap.remove(id);
            }
          });
    }
    addedList.forEach(
        mediation -> {
          String id = mediation.getId();
          Subscription mediationStateSubscription =
              EasyBind.subscribe(
                  mediation.isClosedProperty(),
                  isClosed -> {
                    // We get the event before the list gets updated, so we execute on next frame
                    UserThread.execute(
                        () -> {
                          int openMediations =
                              mediations
                                  .getList()
                                  .stream()
                                  .filter(e -> !e.isClosed())
                                  .collect(Collectors.toList())
                                  .size();
                          numOpenMediations.set(openMediations);
                        });
                  });
          mediationIsClosedSubscriptionsMap.put(id, mediationStateSubscription);
        });
  }

  public void cleanupMediations() {
    mediations
        .stream()
        .forEach(
            mediation -> {
              mediation.setStorage(mediationStorage);
              if (mediation.isClosed()) closedMediations.put(mediation.getTradeId(), mediation);
              else openMediations.put(mediation.getTradeId(), mediation);
            });

    // If we have duplicate mediations we close the second one (might happen if both traders opened
    // a mediation and mediator
    // was offline, so could not forward msg to other peer, then the mediator might have 4
    // mediations open for 1 trade)
    openMediations.forEach(
        (key, openMediation) -> {
          if (closedMediations.containsKey(key)) {
            final Mediation closedMediation = closedMediations.get(key);
            // We need to check if is from the same peer, we don't want to close the peers mediation
            if (closedMediation.getTraderId() == openMediation.getTraderId()) {
              openMediation.setIsClosed(true);
              // TODO: figure out what to do when mediation is over
              //                    tradeManager.closeMediationTrade(openMediation.getTradeId());
            }
          }
        });
  }

  private void tryApplyMessages() {
    if (isReadyForTxBroadcast()) applyMessages();
  }

  private boolean isReadyForTxBroadcast() {
    return p2PService.isBootstrapped()
        && walletsSetup.isDownloadComplete()
        && walletsSetup.hasSufficientPeersForBroadcast();
  }

  private void applyMessages() {
    decryptedDirectMessageWithPubKeys.forEach(
        decryptedMessageWithPubKey -> {
          NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
          if (networkEnvelope instanceof MediationMessage) {
            dispatchMessage((MediationMessage) networkEnvelope);
          } else if (networkEnvelope instanceof AckMessage) {
            processAckMessage((AckMessage) networkEnvelope, null);
          }
        });
    decryptedDirectMessageWithPubKeys.clear();

    decryptedMailboxMessageWithPubKeys.forEach(
        decryptedMessageWithPubKey -> {
          NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
          log.debug("decryptedMessageWithPubKey.message " + networkEnvelope);
          if (networkEnvelope instanceof MediationMessage) {
            dispatchMessage((MediationMessage) networkEnvelope);
            p2PService.removeEntryFromMailbox(decryptedMessageWithPubKey);
          } else if (networkEnvelope instanceof AckMessage) {
            processAckMessage((AckMessage) networkEnvelope, decryptedMessageWithPubKey);
          }
        });
    decryptedMailboxMessageWithPubKeys.clear();
  }

  private void processAckMessage(
      AckMessage ackMessage, @Nullable DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
    if (ackMessage.getSourceType() == AckMessageSourceType.DISPUTE_MESSAGE) {
      if (ackMessage.isSuccess()) {
        log.info(
            "Received AckMessage for {} with tradeId {} and uid {}",
            ackMessage.getSourceMsgClassName(),
            ackMessage.getSourceId(),
            ackMessage.getSourceUid());
      } else {
        log.warn(
            "Received AckMessage with error state for {} with tradeId {} and errorMessage={}",
            ackMessage.getSourceMsgClassName(),
            ackMessage.getSourceId(),
            ackMessage.getErrorMessage());
      }

      mediations
          .getList()
          .stream()
          .flatMap(mediation -> mediation.getMediationCommunicationMessages().stream())
          .filter(msg -> msg.getUid().equals(ackMessage.getSourceUid()))
          .forEach(
              msg -> {
                if (ackMessage.isSuccess()) msg.setAcknowledged(true);
                else msg.setAckError(ackMessage.getErrorMessage());
              });
      mediations.persist();

      if (decryptedMessageWithPubKey != null)
        p2PService.removeEntryFromMailbox(decryptedMessageWithPubKey);
    }
  }

  private void dispatchMessage(MediationMessage message) {
    log.info(
        "Received {} with tradeId {} and uid {}",
        message.getClass().getSimpleName(),
        message.getTradeId(),
        message.getUid());

    if (message instanceof OpenNewMediationMessage)
      onOpenNewMediationMessage((OpenNewMediationMessage) message);
    else if (message instanceof PeerOpenedMediationMessage)
      onPeerOpenedMediationMessage((PeerOpenedMediationMessage) message);
    else if (message instanceof MediationCommunicationMessage)
      onMediationDirectMessage((MediationCommunicationMessage) message);
    else if (message instanceof MediationResultMessage)
      onMediationResultMessage((MediationResultMessage) message);
    else log.warn("Unsupported message at dispatchMessage.\nmessage=" + message);
  }

  public void sendOpenNewMediationMessage(
      Mediation mediation, boolean reOpen, ResultHandler resultHandler, FaultHandler faultHandler) {
    if (!mediations.contains(mediation)) {
      final Optional<Mediation> storedMediationOptional =
          findMediation(mediation.getTradeId(), mediation.getTraderId());
      if (!storedMediationOptional.isPresent() || reOpen) {
        String sysMsg =
            mediation.isSupportTicket()
                ? Res.get("support.youOpenedTicket")
                : Res.get("support.youOpenedMediation", mediationInfo);

        MediationCommunicationMessage mediationCommunicationMessage =
            new MediationCommunicationMessage(
                mediation.getTradeId(),
                keyRing.getPubKeyRing().hashCode(),
                false,
                Res.get("support.systemMsg", sysMsg),
                p2PService.getAddress());
        mediationCommunicationMessage.setSystemMessage(true);
        mediation.addMediationCommunicationMessage(mediationCommunicationMessage);
        if (!reOpen) {
          mediations.add(mediation);
        }

        NodeAddress peersNodeAddress = mediation.getContract().getMediatorNodeAddress();
        OpenNewMediationMessage openNewMediationMessage =
            new OpenNewMediationMessage(
                mediation, p2PService.getAddress(), UUID.randomUUID().toString());
        log.info(
            "Send {} to peer {}. tradeId={}, openNewMediationMessage.uid={}, "
                + "mediationCommunicationMessage.uid={}",
            openNewMediationMessage.getClass().getSimpleName(),
            peersNodeAddress,
            openNewMediationMessage.getTradeId(),
            openNewMediationMessage.getUid(),
            mediationCommunicationMessage.getUid());
        p2PService.sendEncryptedMailboxMessage(
            peersNodeAddress,
            mediation.getMediatorPubKeyRing(),
            openNewMediationMessage,
            new SendMailboxMessageListener() {
              @Override
              public void onArrived() {
                log.info(
                    "{} arrived at peer {}. tradeId={}, openNewMediationMessage.uid={}, "
                        + "mediationCommunicationMessage.uid={}",
                    openNewMediationMessage.getClass().getSimpleName(),
                    peersNodeAddress,
                    openNewMediationMessage.getTradeId(),
                    openNewMediationMessage.getUid(),
                    mediationCommunicationMessage.getUid());

                // We use the mediationCommunicationMessage wrapped inside the
                // openNewMediationMessage for
                // the state, as that is displayed to the user and we only persist that msg
                mediationCommunicationMessage.setArrived(true);
                mediations.persist();
                resultHandler.handleResult();
              }

              @Override
              public void onStoredInMailbox() {
                log.info(
                    "{} stored in mailbox for peer {}. tradeId={}, openNewMediationMessage.uid={}, "
                        + "mediationCommunicationMessage.uid={}",
                    openNewMediationMessage.getClass().getSimpleName(),
                    peersNodeAddress,
                    openNewMediationMessage.getTradeId(),
                    openNewMediationMessage.getUid(),
                    mediationCommunicationMessage.getUid());

                // We use the mediationCommunicationMessage wrapped inside the
                // openNewMediationMessage for
                // the state, as that is displayed to the user and we only persist that msg
                mediationCommunicationMessage.setStoredInMailbox(true);
                mediations.persist();
                resultHandler.handleResult();
              }

              @Override
              public void onFault(String errorMessage) {
                log.error(
                    "{} failed: Peer {}. tradeId={}, openNewMediationMessage.uid={}, "
                        + "mediationCommunicationMessage.uid={}, errorMessage={}",
                    openNewMediationMessage.getClass().getSimpleName(),
                    peersNodeAddress,
                    openNewMediationMessage.getTradeId(),
                    openNewMediationMessage.getUid(),
                    mediationCommunicationMessage.getUid(),
                    errorMessage);

                // We use the mediationCommunicationMessage wrapped inside the
                // openNewMediationMessage for
                // the state, as that is displayed to the user and we only persist that msg
                mediationCommunicationMessage.setSendMessageError(errorMessage);
                mediations.persist();
                faultHandler.handleFault(
                    "Sending mediation message failed: " + errorMessage,
                    new MessageDeliveryFailedException());
              }
            });
      } else {
        final String msg =
            "We got a mediation already open for that trade and trading peer.\n"
                + "TradeId = "
                + mediation.getTradeId();
        log.warn(msg);
        faultHandler.handleFault(msg, new MediationAlreadyOpenException());
      }
    } else {
      final String msg =
          "We got a mediation msg what we have already stored. TradeId = " + mediation.getTradeId();
      log.warn(msg);
      faultHandler.handleFault(msg, new MediationAlreadyOpenException());
    }
  }

  // mediator sends that to trading peer when he received openMediation request
  private String sendPeerOpenedMediationMessage(
      Mediation mediationFromOpener, Contract contractFromOpener, PubKeyRing pubKeyRing) {
    Mediation mediation =
        new Mediation(
            mediationStorage,
            mediationFromOpener.getTradeId(),
            pubKeyRing.hashCode(),
            !mediationFromOpener.isMediationOpenerIsBuyer(),
            !mediationFromOpener.isMediationOpenerIsMaker(),
            pubKeyRing,
            mediationFromOpener.getTradeDate().getTime(),
            contractFromOpener,
            mediationFromOpener.getContractHash(),
            mediationFromOpener.getDepositTxSerialized(),
            // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
            //                mediationFromOpener.getPayoutTxSerialized(),
            mediationFromOpener.getDepositTxId(),
            // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
            //                mediationFromOpener.getPayoutTxId(),
            mediationFromOpener.getContractAsJson(),
            mediationFromOpener.getMakerContractSignature(),
            mediationFromOpener.getTakerContractSignature(),
            mediationFromOpener.getMediatorPubKeyRing(),
            mediationFromOpener.isSupportTicket());
    final Optional<Mediation> storedMediationOptional =
        findMediation(mediation.getTradeId(), mediation.getTraderId());
    if (!storedMediationOptional.isPresent()) {
      String sysMsg =
          mediation.isSupportTicket()
              ? Res.get("support.peerOpenedTicket")
              : Res.get("support.peerOpenedMediation", mediationInfo);
      MediationCommunicationMessage mediationCommunicationMessage =
          new MediationCommunicationMessage(
              mediation.getTradeId(),
              keyRing.getPubKeyRing().hashCode(),
              false,
              Res.get("support.systemMsg", sysMsg),
              p2PService.getAddress());
      mediationCommunicationMessage.setSystemMessage(true);
      mediation.addMediationCommunicationMessage(mediationCommunicationMessage);
      mediations.add(mediation);

      // we mirrored mediation already!
      Contract contract = mediation.getContract();
      PubKeyRing peersPubKeyRing =
          mediation.isMediationOpenerIsBuyer()
              ? contract.getBuyerPubKeyRing()
              : contract.getSellerPubKeyRing();
      NodeAddress peersNodeAddress =
          mediation.isMediationOpenerIsBuyer()
              ? contract.getBuyerNodeAddress()
              : contract.getSellerNodeAddress();
      PeerOpenedMediationMessage peerOpenedMediationMessage =
          new PeerOpenedMediationMessage(
              mediation, p2PService.getAddress(), UUID.randomUUID().toString());
      log.info(
          "Send {} to peer {}. tradeId={}, peerOpenedMediationMessage.uid={}, "
              + "mediationCommunicationMessage.uid={}",
          peerOpenedMediationMessage.getClass().getSimpleName(),
          peersNodeAddress,
          peerOpenedMediationMessage.getTradeId(),
          peerOpenedMediationMessage.getUid(),
          mediationCommunicationMessage.getUid());
      p2PService.sendEncryptedMailboxMessage(
          peersNodeAddress,
          peersPubKeyRing,
          peerOpenedMediationMessage,
          new SendMailboxMessageListener() {
            @Override
            public void onArrived() {
              log.info(
                  "{} arrived at peer {}. tradeId={}, peerOpenedMediationMessage.uid={}, "
                      + "mediationCommunicationMessage.uid={}",
                  peerOpenedMediationMessage.getClass().getSimpleName(),
                  peersNodeAddress,
                  peerOpenedMediationMessage.getTradeId(),
                  peerOpenedMediationMessage.getUid(),
                  mediationCommunicationMessage.getUid());

              // We use the mediationCommunicationMessage wrapped inside the
              // peerOpenedMediationMessage for
              // the state, as that is displayed to the user and we only persist that msg
              mediationCommunicationMessage.setArrived(true);
              mediations.persist();
            }

            @Override
            public void onStoredInMailbox() {
              log.info(
                  "{} stored in mailbox for peer {}. tradeId={}, peerOpenedMediationMessage.uid={}, "
                      + "mediationCommunicationMessage.uid={}",
                  peerOpenedMediationMessage.getClass().getSimpleName(),
                  peersNodeAddress,
                  peerOpenedMediationMessage.getTradeId(),
                  peerOpenedMediationMessage.getUid(),
                  mediationCommunicationMessage.getUid());

              // We use the mediationCommunicationMessage wrapped inside the
              // peerOpenedMediationMessage for
              // the state, as that is displayed to the user and we only persist that msg
              mediationCommunicationMessage.setStoredInMailbox(true);
              mediations.persist();
            }

            @Override
            public void onFault(String errorMessage) {
              log.error(
                  "{} failed: Peer {}. tradeId={}, peerOpenedMediationMessage.uid={}, "
                      + "mediationCommunicationMessage.uid={}, errorMessage={}",
                  peerOpenedMediationMessage.getClass().getSimpleName(),
                  peersNodeAddress,
                  peerOpenedMediationMessage.getTradeId(),
                  peerOpenedMediationMessage.getUid(),
                  mediationCommunicationMessage.getUid(),
                  errorMessage);

              // We use the mediationCommunicationMessage wrapped inside the
              // peerOpenedMediationMessage for
              // the state, as that is displayed to the user and we only persist that msg
              mediationCommunicationMessage.setSendMessageError(errorMessage);
              mediations.persist();
            }
          });
      return null;
    } else {
      String msg =
          "We got a mediation already open for that trade and trading peer.\n"
              + "TradeId = "
              + mediation.getTradeId();
      log.warn(msg);
      return msg;
    }
  }

  // traders send msg to the mediator or mediator to 1 trader (trader to trader is not allowed)
  public MediationCommunicationMessage sendMediationDirectMessage(
      Mediation mediation, String text, ArrayList<Attachment> attachments) {
    MediationCommunicationMessage message =
        new MediationCommunicationMessage(
            mediation.getTradeId(),
            mediation.getTraderPubKeyRing().hashCode(),
            isTrader(mediation),
            text,
            p2PService.getAddress());

    message.addAllAttachments(attachments);
    Tuple2<NodeAddress, PubKeyRing> tuple = getNodeAddressPubKeyRingTuple(mediation);
    NodeAddress peersNodeAddress = tuple.first;
    PubKeyRing receiverPubKeyRing = tuple.second;

    if (isTrader(mediation) || (isMediator(mediation) && !message.isSystemMessage()))
      mediation.addMediationCommunicationMessage(message);

    if (receiverPubKeyRing != null) {
      log.info(
          "Send {} to peer {}. tradeId={}, uid={}",
          message.getClass().getSimpleName(),
          peersNodeAddress,
          message.getTradeId(),
          message.getUid());

      p2PService.sendEncryptedMailboxMessage(
          peersNodeAddress,
          receiverPubKeyRing,
          message,
          new SendMailboxMessageListener() {
            @Override
            public void onArrived() {
              log.info(
                  "{} arrived at peer {}. tradeId={}, uid={}",
                  message.getClass().getSimpleName(),
                  peersNodeAddress,
                  message.getTradeId(),
                  message.getUid());
              message.setArrived(true);
              mediations.persist();
            }

            @Override
            public void onStoredInMailbox() {
              log.info(
                  "{} stored in mailbox for peer {}. tradeId={}, uid={}",
                  message.getClass().getSimpleName(),
                  peersNodeAddress,
                  message.getTradeId(),
                  message.getUid());
              message.setStoredInMailbox(true);
              mediations.persist();
            }

            @Override
            public void onFault(String errorMessage) {
              log.error(
                  "{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                  message.getClass().getSimpleName(),
                  peersNodeAddress,
                  message.getTradeId(),
                  message.getUid(),
                  errorMessage);
              message.setSendMessageError(errorMessage);
              mediations.persist();
            }
          });
    }

    return message;
  }

  // mediator send result to traders
  public void sendMediationResultMessage(
      MediationResult mediationResult, Mediation mediation, String text) {
    MediationCommunicationMessage mediationCommunicationMessage =
        new MediationCommunicationMessage(
            mediation.getTradeId(),
            mediation.getTraderPubKeyRing().hashCode(),
            false,
            text,
            p2PService.getAddress());

    mediation.addMediationCommunicationMessage(mediationCommunicationMessage);
    mediationResult.setMediationCommunicationMessage(mediationCommunicationMessage);

    NodeAddress peersNodeAddress;
    Contract contract = mediation.getContract();
    if (contract.getBuyerPubKeyRing().equals(mediation.getTraderPubKeyRing()))
      peersNodeAddress = contract.getBuyerNodeAddress();
    else peersNodeAddress = contract.getSellerNodeAddress();
    MediationResultMessage mediationResultMessage =
        new MediationResultMessage(
            mediationResult, p2PService.getAddress(), UUID.randomUUID().toString());
    log.info(
        "Send {} to peer {}. tradeId={}, mediationResultMessage.uid={}, mediationCommunicationMessage.uid={}",
        mediationResultMessage.getClass().getSimpleName(),
        peersNodeAddress,
        mediationResultMessage.getTradeId(),
        mediationResultMessage.getUid(),
        mediationCommunicationMessage.getUid());
    p2PService.sendEncryptedMailboxMessage(
        peersNodeAddress,
        mediation.getTraderPubKeyRing(),
        mediationResultMessage,
        new SendMailboxMessageListener() {
          @Override
          public void onArrived() {
            log.info(
                "{} arrived at peer {}. tradeId={}, mediationResultMessage.uid={}, "
                    + "mediationCommunicationMessage.uid={}",
                mediationResultMessage.getClass().getSimpleName(),
                peersNodeAddress,
                mediationResultMessage.getTradeId(),
                mediationResultMessage.getUid(),
                mediationCommunicationMessage.getUid());

            // We use the mediationCommunicationMessage wrapped inside the mediationResultMessage
            // for
            // the state, as that is displayed to the user and we only persist that msg
            mediationCommunicationMessage.setArrived(true);
            mediations.persist();
          }

          @Override
          public void onStoredInMailbox() {
            log.info(
                "{} stored in mailbox for peer {}. tradeId={}, mediationResultMessage.uid={}, "
                    + "mediationCommunicationMessage.uid={}",
                mediationResultMessage.getClass().getSimpleName(),
                peersNodeAddress,
                mediationResultMessage.getTradeId(),
                mediationResultMessage.getUid(),
                mediationCommunicationMessage.getUid());

            // We use the mediationCommunicationMessage wrapped inside the mediationResultMessage
            // for
            // the state, as that is displayed to the user and we only persist that msg
            mediationCommunicationMessage.setStoredInMailbox(true);
            mediations.persist();
          }

          @Override
          public void onFault(String errorMessage) {
            log.error(
                "{} failed: Peer {}. tradeId={}, mediationResultMessage.uid={}, "
                    + "mediationCommunicationMessage.uid={}, errorMessage={}",
                mediationResultMessage.getClass().getSimpleName(),
                peersNodeAddress,
                mediationResultMessage.getTradeId(),
                mediationResultMessage.getUid(),
                mediationCommunicationMessage.getUid(),
                errorMessage);

            // We use the mediationCommunicationMessage wrapped inside the mediationResultMessage
            // for
            // the state, as that is displayed to the user and we only persist that msg
            mediationCommunicationMessage.setSendMessageError(errorMessage);
            mediations.persist();
          }
        });
  }

  /*
  // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
      // winner (or buyer in case of 50/50) sends tx to other peer
      private void sendPeerPublishedPayoutTxMessage(Transaction transaction, Mediation mediation, Contract contract) {
          PubKeyRing peersPubKeyRing = mediation.isMediationOpenerIsBuyer() ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing();
          NodeAddress peersNodeAddress = mediation.isMediationOpenerIsBuyer() ? contract.getSellerNodeAddress() : contract.getBuyerNodeAddress();
          log.trace("sendPeerPublishedPayoutTxMessage to peerAddress " + peersNodeAddress);
          final PeerPublishedMediationPayoutTxMessage message = new PeerPublishedMediationPayoutTxMessage(transaction.bitcoinSerialize(),
                  mediation.getTradeId(),
                  p2PService.getAddress(),
                  UUID.randomUUID().toString());
          log.info("Send {} to peer {}. tradeId={}, uid={}",
                  message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
          p2PService.sendEncryptedMailboxMessage(peersNodeAddress,
                  peersPubKeyRing,
                  message,
                  new SendMailboxMessageListener() {
                      @Override
                      public void onArrived() {
                          log.info("{} arrived at peer {}. tradeId={}, uid={}",
                                  message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                      }

                      @Override
                      public void onStoredInMailbox() {
                          log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}",
                                  message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                      }

                      @Override
                      public void onFault(String errorMessage) {
                          log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                                  message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
                      }
                  }
          );
      }
  */

  private void sendAckMessage(
      MediationMessage mediationMessage,
      PubKeyRing peersPubKeyRing,
      boolean result,
      @Nullable String errorMessage) {
    String tradeId = mediationMessage.getTradeId();
    String uid = mediationMessage.getUid();
    AckMessage ackMessage =
        new AckMessage(
            p2PService.getNetworkNode().getNodeAddress(),
            AckMessageSourceType.DISPUTE_MESSAGE,
            mediationMessage.getClass().getSimpleName(),
            uid,
            tradeId,
            result,
            errorMessage);
    final NodeAddress peersNodeAddress = mediationMessage.getSenderNodeAddress();
    log.info(
        "Send AckMessage for {} to peer {}. tradeId={}, uid={}",
        ackMessage.getSourceMsgClassName(),
        peersNodeAddress,
        tradeId,
        uid);
    p2PService.sendEncryptedMailboxMessage(
        peersNodeAddress,
        peersPubKeyRing,
        ackMessage,
        new SendMailboxMessageListener() {
          @Override
          public void onArrived() {
            log.info(
                "AckMessage for {} arrived at peer {}. tradeId={}, uid={}",
                ackMessage.getSourceMsgClassName(),
                peersNodeAddress,
                tradeId,
                uid);
          }

          @Override
          public void onStoredInMailbox() {
            log.info(
                "AckMessage for {} stored in mailbox for peer {}. tradeId={}, uid={}",
                ackMessage.getSourceMsgClassName(),
                peersNodeAddress,
                tradeId,
                uid);
          }

          @Override
          public void onFault(String errorMessage) {
            log.error(
                "AckMessage for {} failed. Peer {}. tradeId={}, uid={}, errorMessage={}",
                ackMessage.getSourceMsgClassName(),
                peersNodeAddress,
                tradeId,
                uid,
                errorMessage);
          }
        });
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming message
  ///////////////////////////////////////////////////////////////////////////////////////////

  // mediator receives that from trader who opens mediation
  private void onOpenNewMediationMessage(OpenNewMediationMessage openNewMediationMessage) {
    String errorMessage;
    Mediation mediation = openNewMediationMessage.getMediation();
    Contract contractFromOpener = mediation.getContract();
    PubKeyRing peersPubKeyRing =
        mediation.isMediationOpenerIsBuyer()
            ? contractFromOpener.getSellerPubKeyRing()
            : contractFromOpener.getBuyerPubKeyRing();
    if (isMediator(mediation)) {
      if (!mediations.contains(mediation)) {
        final Optional<Mediation> storedMediationOptional =
            findMediation(mediation.getTradeId(), mediation.getTraderId());
        if (!storedMediationOptional.isPresent()) {
          mediation.setStorage(mediationStorage);
          mediations.add(mediation);
          errorMessage =
              sendPeerOpenedMediationMessage(mediation, contractFromOpener, peersPubKeyRing);
        } else {
          errorMessage =
              "We got a mediation already open for that trade and trading peer.\n"
                  + "TradeId = "
                  + mediation.getTradeId();
          log.warn(errorMessage);
        }
      } else {
        errorMessage =
            "We got a mediation msg what we have already stored. TradeId = "
                + mediation.getTradeId();
        log.warn(errorMessage);
      }
    } else {
      errorMessage = "Trader received openNewMediationMessage. That must never happen.";
      log.error(errorMessage);
    }

    // We use the MediationCommunicationMessage not the openNewMediationMessage for the ACK
    ObservableList<MediationCommunicationMessage> messages =
        openNewMediationMessage.getMediation().getMediationCommunicationMessages();
    if (!messages.isEmpty()) {
      MediationCommunicationMessage msg = messages.get(0);
      PubKeyRing sendersPubKeyRing =
          mediation.isMediationOpenerIsBuyer()
              ? contractFromOpener.getBuyerPubKeyRing()
              : contractFromOpener.getSellerPubKeyRing();
      sendAckMessage(msg, sendersPubKeyRing, errorMessage == null, errorMessage);
    }
  }

  // Mediation requester receives mediation message from requester
  private void onPeerOpenedMediationMessage(PeerOpenedMediationMessage peerOpenedMediationMessage) {
    String errorMessage;
    Mediation mediation = peerOpenedMediationMessage.getMediation();
    if (!isMediator(mediation)) {
      if (!mediations.contains(mediation)) {
        final Optional<Mediation> storedMediationOptional =
            findMediation(mediation.getTradeId(), mediation.getTraderId());
        if (!storedMediationOptional.isPresent()) {
          mediation.setStorage(mediationStorage);
          mediations.add(mediation);
          Optional<Trade> tradeOptional = tradeManager.getTradeById(mediation.getTradeId());
          tradeOptional.ifPresent(
              trade -> trade.setMediationState(Trade.MediationState.MEDIATION_STARTED_BY_PEER));
          errorMessage = null;
        } else {
          errorMessage =
              "We got a mediation already open for that trade and trading peer.\n"
                  + "TradeId = "
                  + mediation.getTradeId();
          log.warn(errorMessage);
        }
      } else {
        errorMessage =
            "We got a mediation msg what we have already stored. TradeId = "
                + mediation.getTradeId();
        log.warn(errorMessage);
      }
    } else {
      errorMessage = "Mediator received peerOpenedMediationMessage. That must never happen.";
      log.error(errorMessage);
    }

    // We use the MediationCommunicationMessage not the peerOpenedMediationMessage for the ACK
    ObservableList<MediationCommunicationMessage> messages =
        peerOpenedMediationMessage.getMediation().getMediationCommunicationMessages();
    if (!messages.isEmpty()) {
      MediationCommunicationMessage msg = messages.get(0);
      sendAckMessage(msg, mediation.getMediatorPubKeyRing(), errorMessage == null, errorMessage);
    }

    sendAckMessage(
        peerOpenedMediationMessage,
        mediation.getMediatorPubKeyRing(),
        errorMessage == null,
        errorMessage);
  }

  // A trader can receive a msg from the mediator or the mediator from a trader. Trader to trader is
  // not allowed.
  private void onMediationDirectMessage(
      MediationCommunicationMessage mediationCommunicationMessage) {
    final String tradeId = mediationCommunicationMessage.getTradeId();
    final String uid = mediationCommunicationMessage.getUid();
    Optional<Mediation> mediationOptional =
        findMediation(tradeId, mediationCommunicationMessage.getTraderId());
    if (!mediationOptional.isPresent()) {
      log.debug(
          "We got a mediationCommunicationMessage but we don't have a matching mediation. TradeId = "
              + tradeId);
      if (!delayMsgMap.containsKey(uid)) {
        Timer timer =
            UserThread.runAfter(() -> onMediationDirectMessage(mediationCommunicationMessage), 1);
        delayMsgMap.put(uid, timer);
      } else {
        String msg =
            "We got a mediationCommunicationMessage after we already repeated to apply the message after a delay. That should never happen. TradeId = "
                + tradeId;
        log.warn(msg);
      }
      return;
    }

    cleanupRetryMap(uid);
    Mediation mediation = mediationOptional.get();
    Tuple2<NodeAddress, PubKeyRing> tuple = getNodeAddressPubKeyRingTuple(mediation);
    PubKeyRing receiverPubKeyRing = tuple.second;

    if (!mediation.getMediationCommunicationMessages().contains(mediationCommunicationMessage))
      mediation.addMediationCommunicationMessage(mediationCommunicationMessage);
    else
      log.warn(
          "We got a mediationCommunicationMessage what we have already stored. TradeId = "
              + tradeId);

    // We never get a errorMessage in that method (only if we cannot resolve the receiverPubKeyRing
    // but then we
    // cannot send it anyway)
    if (receiverPubKeyRing != null)
      sendAckMessage(mediationCommunicationMessage, receiverPubKeyRing, true, null);
  }

  // We get that message at both peers. The mediation object is in context of the trader
  private void onMediationResultMessage(MediationResultMessage mediationResultMessage) {
    String errorMessage = null;
    boolean success = false;
    PubKeyRing mediatorPubKeyRing = null;
    MediationResult mediationResult = mediationResultMessage.getMediationResult();

    // Was an Arbitration problem but not with Mediation
    /*
            if (isMediator(mediationResult)) {
                log.error("Mediator received mediationResultMessage. That must never happen.");
                return;
            }
    */

    final String tradeId = mediationResult.getTradeId();
    Optional<Mediation> mediationOptional = findMediation(tradeId, mediationResult.getTraderId());
    final String uid = mediationResultMessage.getUid();
    if (!mediationOptional.isPresent()) {
      log.debug(
          "We got a mediation result msg but we don't have a matching mediation. "
              + "That might happen when we get the mediationResultMessage before the mediation was created. "
              + "We try again after 2 sec. to apply the mediationResultMessage. TradeId = "
              + tradeId);
      if (!delayMsgMap.containsKey(uid)) {
        // We delay2 sec. to be sure the comm. msg gets added first
        Timer timer =
            UserThread.runAfter(() -> onMediationResultMessage(mediationResultMessage), 2);
        delayMsgMap.put(uid, timer);
      } else {
        log.warn(
            "We got a mediation result msg after we already repeated to apply the message after a delay. "
                + "That should never happen. TradeId = "
                + tradeId);
      }
      return;
    }

    // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
    /*
            try {
                cleanupRetryMap(uid);
                Mediation mediation = mediationOptional.get();
                mediatorPubKeyRing = mediation.getMediatorPubKeyRing();
                MediationCommunicationMessage mediationCommunicationMessage = mediationResult.getMediationCommunicationMessage();
                if (!mediation.getMediationCommunicationMessages().contains(mediationCommunicationMessage))
                    mediation.addMediationCommunicationMessage(mediationCommunicationMessage);
                else if (mediationCommunicationMessage != null)
                    log.warn("We got a mediation mail msg what we have already stored. TradeId = " + mediationCommunicationMessage.getTradeId());

                mediation.setIsClosed(true);

                if (mediation.mediationResultProperty().get() != null)
                    log.warn("We got already a mediation result. That should only happen if a mediation needs to be closed " +
                            "again because the first close did not succeed. TradeId = " + tradeId);

                mediation.setMediationResult(mediationResult);

                // We need to avoid publishing the tx from both traders as it would create problems with zero confirmation withdrawals
                // There would be different transactions if both sign and publish (signers: once buyer+arb, once seller+arb)
                // The tx publisher is the winner or in case both get 50% the buyer, as the buyer has more inventive to publish the tx as he receives
                // more BTC as he has deposited
                final Contract contract = mediation.getContract();

                boolean isBuyer = keyRing.getPubKeyRing().equals(contract.getBuyerPubKeyRing());
                MediationResult.Winner publisher = mediationResult.getWinner();

                // Sometimes the user who receives the trade amount is never online, so we might want to
                // let the loser publish the tx. When the winner comes online he gets his funds as it was published by the other peer.
                // Default isLoserPublisher is set to false
                if (mediationResult.isLoserPublisher()) {
                    // we invert the logic
                    if (publisher == MediationResult.Winner.BUYER)
                        publisher = MediationResult.Winner.SELLER;
                    else if (publisher == MediationResult.Winner.SELLER)
                        publisher = MediationResult.Winner.BUYER;
                }

                if ((isBuyer && publisher == MediationResult.Winner.BUYER)
                        || (!isBuyer && publisher == MediationResult.Winner.SELLER)) {

                    final Optional<Trade> tradeOptional = tradeManager.getTradeById(tradeId);
                    Transaction payoutTx = null;
                    if (tradeOptional.isPresent()) {
                        payoutTx = tradeOptional.get().getPayoutTx();
                } else {
                        final Optional<Tradable> tradableOptional = closedTradableManager.getTradableById(tradeId);
                    if (tradableOptional.isPresent() && tradableOptional.get() instanceof Trade) {
                        payoutTx = ((Trade) tradableOptional.get()).getPayoutTx();
                    }
                }

                if (payoutTx == null) {
                   if (mediation.getDepositTxSerialized() != null) {
                      byte[] multiSigPubKey = isBuyer ? contract.getBuyerMultiSigPubKey() : contract.getSellerMultiSigPubKey();
                      DeterministicKey multiSigKeyPair = walletService.getMultiSigKeyPair(mediation.getTradeId(), multiSigPubKey);
                      Transaction signedMediationdPayoutTx = tradeWalletService.traderSignAndFinalizeMediationdPayoutTx(
                                    mediation.getDepositTxSerialized(),
                                    mediationResult.getMediatorSignature(),
                                    mediationResult.getBuyerPayoutAmount(),
                                    mediationResult.getSellerPayoutAmount(),
                                    contract.getBuyerPayoutAddressString(),
                                    contract.getSellerPayoutAddressString(),
                                    multiSigKeyPair,
                                    contract.getBuyerMultiSigPubKey(),
                                    contract.getSellerMultiSigPubKey(),
                                    mediationResult.getMediatorPubKey()
                      );
                      Transaction committedMediationdPayoutTx = tradeWalletService.addTxToWallet(signedMediationdPayoutTx);
                      tradeWalletService.broadcastTx(committedMediationdPayoutTx, new TxBroadcaster.Callback() {
                          @Override
                          public void onSuccess(Transaction transaction) {
                             // after successful publish we send peer the tx

                             mediation.setMediationPayoutTxId(transaction.getHashAsString());
    // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
    //                       sendPeerPublishedPayoutTxMessage(transaction, mediation, contract);

                             // set state after payout as we call swapTradeEntryToAvailableEntry
                             if (tradeManager.getTradeById(mediation.getTradeId()).isPresent())
                                  tradeManager.closeMediationdTrade(mediation.getTradeId());
                             else {
                                  Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(mediation.getTradeId());
                                  openOfferOptional.ifPresent(openOffer -> openOfferManager.closeOpenOffer(openOffer.getOffer()));
                             }
                          }

                          @Override
                          public void onFailure(TxBroadcastException exception) {
                                    log.error(exception.getMessage());
                          }
                      }, 15);

                      success = true;
                   } else {
                      errorMessage = "DepositTx is null. TradeId = " + tradeId;
                      log.warn(errorMessage);
                      success = false;
                   }
                } else {
                   log.warn("We got already a payout tx. That might be the case if the other peer did not get the " +
                            "payout tx and opened a mediation. TradeId = " + tradeId);
                   mediation.setMediationPayoutTxId(payoutTx.getHashAsString());
    // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
    //             sendPeerPublishedPayoutTxMessage(payoutTx, mediation, contract);

                   success = true;
                }

              } else {
                 log.trace("We don't publish the tx as we are not the winning party.");
                 // Clean up tangling trades
                 if (mediation.mediationResultProperty().get() != null && mediation.isClosed() &&
                            tradeManager.getTradeById(mediation.getTradeId()).isPresent()) {
                        tradeManager.closeMediationdTrade(mediation.getTradeId());
                 }

                 success = true;
              }
            } catch (AddressFormatException | WalletException | TransactionVerificationException e) {
                e.printStackTrace();
                errorMessage = "Error at traderSignAndFinalizeMediationdPayoutTx " + e.toString();
                log.error(errorMessage);
                success = false;
                throw new RuntimeException(errorMessage);
            } finally {
                if (mediatorPubKeyRing != null) {
                    // We use the mediationCommunicationMessage as we only persist those not the mediationResultMessage.
                    // If we would use the mediationResultMessage we could not lookup for the msg when we receive the AckMessage.
                    MediationCommunicationMessage mediationCommunicationMessage = mediationResultMessage.getMediationResult().getMediationCommunicationMessage();
                    sendAckMessage(mediationCommunicationMessage, mediatorPubKeyRing, success, errorMessage);
                }
            }
    */
  }

  // TODO: No auto payout for mediation. Need to add trigger for donation payment instead
  /*
      // Losing trader or in case of 50/50 the seller gets the tx sent from the winner or buyer
      private void onMediationdPayoutTxMessage(PeerPublishedMediationPayoutTxMessage peerPublishedMediationPayoutTxMessage) {
          final String uid = peerPublishedMediationPayoutTxMessage.getUid();
          final String tradeId = peerPublishedMediationPayoutTxMessage.getTradeId();
          Optional<Mediation> mediationOptional = findOwnMediation(tradeId);
          if (!mediationOptional.isPresent()) {
              log.debug("We got a peerPublishedPayoutTxMessage but we don't have a matching mediation. TradeId = " + tradeId);
              if (!delayMsgMap.containsKey(uid)) {
                  // We delay 3 sec. to be sure the close msg gets added first
                  Timer timer = UserThread.runAfter(() -> onMediationdPayoutTxMessage(peerPublishedMediationPayoutTxMessage), 3);
                  delayMsgMap.put(uid, timer);
              } else {
                  log.warn("We got a peerPublishedPayoutTxMessage after we already repeated to apply the message after a delay. " +
                          "That should never happen. TradeId = " + tradeId);
              }
              return;
          }

          Mediation mediation = mediationOptional.get();
          final Contract contract = mediation.getContract();
          PubKeyRing ownPubKeyRing = keyRing.getPubKeyRing();
          boolean isBuyer = ownPubKeyRing.equals(contract.getBuyerPubKeyRing());
          PubKeyRing peersPubKeyRing = isBuyer ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing();

          cleanupRetryMap(uid);
          Transaction walletTx = tradeWalletService.addTxToWallet(peerPublishedMediationPayoutTxMessage.getTransaction());
          mediation.setMediationPayoutTxId(walletTx.getHashAsString());
          BtcWalletService.printTx("Mediationd payoutTx received from peer", walletTx);

          // We can only send the ack msg if we have the peersPubKeyRing which requires the mediation
          sendAckMessage(peerPublishedMediationPayoutTxMessage, peersPubKeyRing, true, null);
      }
  */

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Getters
  ///////////////////////////////////////////////////////////////////////////////////////////

  public Storage<MediationList> getMediationStorage() {
    return mediationStorage;
  }

  public ObservableList<Mediation> getMediationsAsObservableList() {
    return mediations.getList();
  }

  public boolean isTrader(Mediation mediation) {
    return keyRing.getPubKeyRing().equals(mediation.getTraderPubKeyRing());
  }

  private boolean isMediator(Mediation mediation) {
    return keyRing.getPubKeyRing().equals(mediation.getMediatorPubKeyRing());
  }

  // Not needed since changing from Arbitration to Mediation
  /*
      private boolean isMediator(MediationResult mediationResult) {
          return Arrays.equals(mediationResult.getMediatorPubKey(),
                  walletService.getMediatorAddressEntry().getPubKey());
      }
  */

  public String getNrOfMediations(boolean isBuyer, Contract contract) {
    return String.valueOf(
        getMediationsAsObservableList()
            .stream()
            .filter(
                e -> {
                  Contract contract1 = e.getContract();
                  if (contract1 == null) return false;

                  if (isBuyer) {
                    NodeAddress buyerNodeAddress = contract1.getBuyerNodeAddress();
                    return buyerNodeAddress != null
                        && buyerNodeAddress.equals(contract.getBuyerNodeAddress());
                  } else {
                    NodeAddress sellerNodeAddress = contract1.getSellerNodeAddress();
                    return sellerNodeAddress != null
                        && sellerNodeAddress.equals(contract.getSellerNodeAddress());
                  }
                })
            .collect(Collectors.toSet())
            .size());
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Utils
  ///////////////////////////////////////////////////////////////////////////////////////////

  private Tuple2<NodeAddress, PubKeyRing> getNodeAddressPubKeyRingTuple(Mediation mediation) {
    PubKeyRing receiverPubKeyRing = null;
    NodeAddress peerNodeAddress = null;
    if (isTrader(mediation)) {
      receiverPubKeyRing = mediation.getMediatorPubKeyRing();
      peerNodeAddress = mediation.getContract().getMediatorNodeAddress();
    } else if (isMediator(mediation)) {
      receiverPubKeyRing = mediation.getTraderPubKeyRing();
      Contract contract = mediation.getContract();
      if (contract.getBuyerPubKeyRing().equals(receiverPubKeyRing))
        peerNodeAddress = contract.getBuyerNodeAddress();
      else peerNodeAddress = contract.getSellerNodeAddress();
    } else {
      log.error("That must not happen. Trader cannot communicate to other trader.");
    }
    return new Tuple2<>(peerNodeAddress, receiverPubKeyRing);
  }

  private Optional<Mediation> findMediation(String tradeId, int traderId) {
    return mediations
        .stream()
        .filter(e -> e.getTradeId().equals(tradeId) && e.getTraderId() == traderId)
        .findAny();
  }

  public Optional<Mediation> findOwnMediation(String tradeId) {
    return getMediationStream(tradeId).findAny();
  }

  private Stream<Mediation> getMediationStream(String tradeId) {
    return mediations.stream().filter(e -> e.getTradeId().equals(tradeId));
  }

  private void cleanupRetryMap(String uid) {
    if (delayMsgMap.containsKey(uid)) {
      Timer timer = delayMsgMap.remove(uid);
      if (timer != null) timer.stop();
    }
  }
}
