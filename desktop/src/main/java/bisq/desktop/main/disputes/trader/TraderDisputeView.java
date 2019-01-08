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

package bisq.desktop.main.disputes.trader;

import bisq.desktop.common.view.ActivatableView;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.AutoTooltipButton;
import bisq.desktop.components.AutoTooltipLabel;
import bisq.desktop.components.AutoTooltipTableColumn;
import bisq.desktop.components.BisqTextArea;
import bisq.desktop.components.BusyAnimation;
import bisq.desktop.components.HyperlinkWithIcon;
import bisq.desktop.components.InputTextField;
import bisq.desktop.components.TableGroupHeadline;
import bisq.desktop.main.disputes.mediator.MediatorDisputeView;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.main.overlays.windows.ContractWindow;
import bisq.desktop.main.overlays.windows.DisputeSummaryWindow;
import bisq.desktop.main.overlays.windows.SendPrivateNotificationWindow;
import bisq.desktop.main.overlays.windows.TradeDetailsWindow;
import bisq.desktop.util.GUIUtil;

import bisq.core.alert.PrivateNotificationManager;
import bisq.core.app.AppOptionKeys;
import bisq.core.disputes.Attachment;
import bisq.core.disputes.Mediation;
import bisq.core.disputes.MediationManager;
import bisq.core.disputes.messages.MediationCommunicationMessage;
import bisq.core.locale.Res;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.util.BSFormatter;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.network.Connection;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.util.Utilities;

import com.google.inject.name.Named;

import javax.inject.Inject;

import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;

import javafx.stage.FileChooser;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.text.TextAlignment;

import javafx.geometry.Insets;

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;

import javafx.event.EventHandler;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import javafx.util.Callback;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.net.MalformedURLException;
import java.net.URL;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

// will be probably only used for arbitration communication, will be renamed and the icon changed
@FxmlView
public class TraderDisputeView extends ActivatableView<VBox, Void> {

    private final MediationManager mediationManager;
    protected final KeyRing keyRing;
    private final TradeManager tradeManager;
    protected final BSFormatter formatter;
    private final DisputeSummaryWindow mediationSummaryWindow;
    private final PrivateNotificationManager privateNotificationManager;
    private final ContractWindow contractWindow;
    private final TradeDetailsWindow tradeDetailsWindow;
    private final P2PService p2PService;

    private final List<Attachment> tempAttachments = new ArrayList<>();
    private final boolean useDevPrivilegeKeys;

    private TableView<Mediation> tableView;
    private SortedList<Mediation> sortedList;

    private Mediation selectedMediation;
    private ListView<MediationCommunicationMessage> messageListView;
    private TextArea inputTextArea;
    private AnchorPane messagesAnchorPane;
    private VBox messagesInputBox;
    private BusyAnimation sendMsgBusyAnimation;
    private Label sendMsgInfoLabel;
    private ChangeListener<Boolean> storedInMailboxPropertyListener, arrivedPropertyListener;
    private ChangeListener<String> sendMessageErrorPropertyListener;
    @Nullable
    private MediationCommunicationMessage mediationCommunicationMessage;
    private ListChangeListener<MediationCommunicationMessage> mediationDirectMessageListListener;
    private ChangeListener<Boolean> selectedMediationClosedPropertyListener;
    private Subscription selectedMediationSubscription;
    private TableGroupHeadline tableGroupHeadline;
    private ObservableList<MediationCommunicationMessage> mediationCommunicationMessages;
    private Button sendButton;
    private Subscription inputTextAreaTextSubscription;
    private EventHandler<KeyEvent> keyEventEventHandler;
    private Scene scene;
    protected FilteredList<Mediation> filteredList;
    private InputTextField filterTextField;
    private ChangeListener<String> filterTextFieldListener;
    protected HBox filterBox;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TraderDisputeView(MediationManager mediationManager,
                             KeyRing keyRing,
                             TradeManager tradeManager,
                             BSFormatter formatter,
                             DisputeSummaryWindow mediationSummaryWindow,
                             PrivateNotificationManager privateNotificationManager,
                             ContractWindow contractWindow,
                             TradeDetailsWindow tradeDetailsWindow,
                             P2PService p2PService,
                             @Named(AppOptionKeys.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        this.mediationManager = mediationManager;
        this.keyRing = keyRing;
        this.tradeManager = tradeManager;
        this.formatter = formatter;
        this.mediationSummaryWindow = mediationSummaryWindow;
        this.privateNotificationManager = privateNotificationManager;
        this.contractWindow = contractWindow;
        this.tradeDetailsWindow = tradeDetailsWindow;
        this.p2PService = p2PService;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
    }

    @Override
    public void initialize() {
        Label label = new AutoTooltipLabel(Res.get("support.filter"));
        HBox.setMargin(label, new Insets(5, 0, 0, 0));
        filterTextField = new InputTextField();
        filterTextField.setText("open");
        filterTextFieldListener = (observable, oldValue, newValue) -> applyFilteredListPredicate(filterTextField.getText());

        filterBox = new HBox();
        filterBox.setSpacing(5);
        filterBox.getChildren().addAll(label, filterTextField);
        VBox.setVgrow(filterBox, Priority.NEVER);
        filterBox.setVisible(false);
        filterBox.setManaged(false);

        tableView = new TableView<>();
        VBox.setVgrow(tableView, Priority.SOMETIMES);
        tableView.setMinHeight(150);

        root.getChildren().addAll(filterBox, tableView);

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new AutoTooltipLabel(Res.get("support.noTickets"));
        placeholder.setWrapText(true);
        tableView.setPlaceholder(placeholder);
        tableView.getSelectionModel().clearSelection();

        tableView.getColumns().add(getSelectColumn());

        TableColumn<Mediation, Mediation> contractColumn = getContractColumn();
        tableView.getColumns().add(contractColumn);

        TableColumn<Mediation, Mediation> dateColumn = getDateColumn();
        tableView.getColumns().add(dateColumn);

        TableColumn<Mediation, Mediation> tradeIdColumn = getTradeIdColumn();
        tableView.getColumns().add(tradeIdColumn);

        TableColumn<Mediation, Mediation> buyerOnionAddressColumn = getBuyerOnionAddressColumn();
        tableView.getColumns().add(buyerOnionAddressColumn);

        TableColumn<Mediation, Mediation> sellerOnionAddressColumn = getSellerOnionAddressColumn();
        tableView.getColumns().add(sellerOnionAddressColumn);


        TableColumn<Mediation, Mediation> marketColumn = getMarketColumn();
        tableView.getColumns().add(marketColumn);

        TableColumn<Mediation, Mediation> roleColumn = getRoleColumn();
        tableView.getColumns().add(roleColumn);

        TableColumn<Mediation, Mediation> stateColumn = getStateColumn();
        tableView.getColumns().add(stateColumn);

        tradeIdColumn.setComparator(Comparator.comparing(Mediation::getTradeId));
        dateColumn.setComparator(Comparator.comparing(Mediation::getOpeningDate));
        buyerOnionAddressColumn.setComparator(Comparator.comparing(this::getBuyerOnionAddressColumnLabel));
        sellerOnionAddressColumn.setComparator(Comparator.comparing(this::getSellerOnionAddressColumnLabel));
        marketColumn.setComparator((o1, o2) -> formatter.getCurrencyPair(o1.getContract().getOfferPayload().getCurrencyCode()).compareTo(o2.getContract().getOfferPayload().getCurrencyCode()));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);

        /*inputTextAreaListener = (observable, oldValue, newValue) ->
                sendButton.setDisable(newValue.length() == 0
                        && tempAttachments.size() == 0 &&
                        selectedMediation.mediationResultProperty().get() == null);*/

        selectedMediationClosedPropertyListener = (observable, oldValue, newValue) -> {
            messagesInputBox.setVisible(!newValue);
            messagesInputBox.setManaged(!newValue);
            AnchorPane.setBottomAnchor(messageListView, newValue ? 0d : 120d);
        };

        mediationDirectMessageListListener = c -> scrollToBottom();

        keyEventEventHandler = event -> {
            if (Utilities.isAltOrCtrlPressed(KeyCode.L, event)) {
                Map<String, List<Mediation>> map = new HashMap<>();
                mediationManager.getMediationsAsObservableList().forEach(mediation -> {
                    String tradeId = mediation.getTradeId();
                    List<Mediation> list;
                    if (!map.containsKey(tradeId))
                        map.put(tradeId, new ArrayList<>());

                    list = map.get(tradeId);
                    list.add(mediation);
                });
                List<List<Mediation>> mediationGroups = new ArrayList<>();
                map.forEach((key, value) -> mediationGroups.add(value));
                mediationGroups.sort((o1, o2) -> !o1.isEmpty() && !o2.isEmpty() ? o1.get(0).getOpeningDate().compareTo(o2.get(0).getOpeningDate()) : 0);
                StringBuilder stringBuilder = new StringBuilder();

                // We don't translate that as it is not intended for the public
                stringBuilder.append("Summary of all mediations (No. of mediations: ").append(mediationGroups.size()).append(")\n\n");
                mediationGroups.forEach(mediationGroup -> {
                    Mediation mediation0 = mediationGroup.get(0);
                    stringBuilder.append("##########################################################################################/\n")
                            .append("## Trade ID: ")
                            .append(mediation0.getTradeId())
                            .append("\n")
                            .append("## Date: ")
                            .append(formatter.formatDateTime(mediation0.getOpeningDate()))
                            .append("\n")
                            .append("## Is support ticket: ")
                            .append(mediation0.isSupportTicket())
                            .append("\n");
                    if (mediation0.mediationResultProperty().get() != null && mediation0.mediationResultProperty().get().getReason() != null) {
                        stringBuilder.append("## Reason: ")
                                .append(mediation0.mediationResultProperty().get().getReason())
                                .append("\n");
                    }
                    stringBuilder.append("##########################################################################################/\n")
                            .append("\n");
                    mediationGroup.forEach(mediation -> {
                        stringBuilder
                                .append("*******************************************************************************************\n")
                                .append("** Trader's ID: ")
                                .append(mediation.getTraderId())
                                .append("\n*******************************************************************************************\n")
                                .append("\n");
                        mediation.getMediationCommunicationMessages().forEach(m -> {
                            String role = m.isSenderIsTrader() ? ">> Trader's msg: " : "<< Mediator's msg: ";
                            stringBuilder.append(role)
                                    .append(m.getMessage())
                                    .append("\n");
                        });
                        stringBuilder.append("\n");
                    });
                    stringBuilder.append("\n");
                });
                String message = stringBuilder.toString();
                // We don't translate that as it is not intended for the public
                new Popup<>().headLine("All mediations (" + mediationGroups.size() + ")")
                        .information(message)
                        .width(1000)
                        .actionButtonText("Copy")
                        .onAction(() -> Utilities.copyToClipboard(message))
                        .show();
            } else if (Utilities.isAltOrCtrlPressed(KeyCode.U, event)) {
                // Hidden shortcut to re-open a mediation. Allow it also for traders not only arbitrator.
                if (selectedMediation != null) {
                    if (selectedMediationClosedPropertyListener != null)
                        selectedMediation.isClosedProperty().removeListener(selectedMediationClosedPropertyListener);
                    selectedMediation.setIsClosed(false);
                }
            } else if (Utilities.isAltOrCtrlPressed(KeyCode.R, event)) {
                if (selectedMediation != null) {
                    PubKeyRing pubKeyRing = selectedMediation.getTraderPubKeyRing();
                    NodeAddress nodeAddress;
                    if (pubKeyRing.equals(selectedMediation.getContract().getBuyerPubKeyRing()))
                        nodeAddress = selectedMediation.getContract().getBuyerNodeAddress();
                    else
                        nodeAddress = selectedMediation.getContract().getSellerNodeAddress();

                    new SendPrivateNotificationWindow(pubKeyRing, nodeAddress, useDevPrivilegeKeys)
                            .onAddAlertMessage(privateNotificationManager::sendPrivateNotificationMessageIfKeyIsValid)
                            .show();
                }
            } else if (Utilities.isAltOrCtrlPressed(KeyCode.ENTER, event)) {
                if (selectedMediation != null && messagesInputBox.isVisible() && inputTextArea.isFocused())
                    onTrySendMessage();
            }
        };
    }

    @Override
    protected void activate() {
        filterTextField.textProperty().addListener(filterTextFieldListener);
        mediationManager.cleanupMediations();

        filteredList = new FilteredList<>(mediationManager.getMediationsAsObservableList());
        applyFilteredListPredicate(filterTextField.getText());

        sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);

        // sortedList.setComparator((o1, o2) -> o2.getOpeningDate().compareTo(o1.getOpeningDate()));
        selectedMediationSubscription = EasyBind.subscribe(tableView.getSelectionModel().selectedItemProperty(), this::onSelectMediation);

        Mediation selectedItem = tableView.getSelectionModel().getSelectedItem();
        if (selectedItem != null)
            tableView.getSelectionModel().select(selectedItem);

        scrollToBottom();

        scene = root.getScene();
        if (scene != null)
            scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);

        // If doPrint=true we print out a html page which opens tabs with all deposit txs
        // (firefox needs about:config change to allow > 20 tabs)
        // Useful to check if there any funds in not finished trades (no payout tx done).
        // Last check 10.02.2017 found 8 trades and we contacted all traders as far as possible (email if available
        // otherwise in-app private notification)
        boolean doPrint = false;
        //noinspection ConstantConditions
        if (doPrint) {
            try {
                DateFormat formatter = new SimpleDateFormat("dd/MM/yy");
                //noinspection UnusedAssignment
                Date startDate = formatter.parse("10/02/17");
                startDate = new Date(0); // print all from start

                HashMap<String, Mediation> map = new HashMap<>();
                mediationManager.getMediationsAsObservableList().forEach(mediation -> map.put(mediation.getDepositTxId(), mediation));

                final Date finalStartDate = startDate;
                List<Mediation> mediations = new ArrayList<>(map.values());
                mediations.sort(Comparator.comparing(Mediation::getOpeningDate));
                List<List<Mediation>> subLists = Lists.partition(mediations, 1000);
                StringBuilder sb = new StringBuilder();
                // We don't translate that as it is not intended for the public
                subLists.forEach(list -> {
                    StringBuilder sb1 = new StringBuilder("\n<html><head><script type=\"text/javascript\">function load(){\n");
                    StringBuilder sb2 = new StringBuilder("\n}</script></head><body onload=\"load()\">\n");
                    list.forEach(mediation -> {
                        if (mediation.getOpeningDate().after(finalStartDate)) {
                            String txId = mediation.getDepositTxId();
                            sb1.append("window.open(\"https://blockchain.info/tx/").append(txId).append("\", '_blank');\n");

                            sb2.append("Mediation ID: ").append(mediation.getId()).
                                    append(" Tx ID: ").
                                    append("<a href=\"https://blockchain.info/tx/").append(txId).append("\">").
                                    append(txId).append("</a> ").
                                    append("Opening date: ").append(formatter.format(mediation.getOpeningDate())).append("<br/>\n");
                        }
                    });
                    sb2.append("</body></html>");
                    String res = sb1.toString() + sb2.toString();

                    sb.append(res).append("\n\n\n");
                });
                log.info(sb.toString());
            } catch (ParseException ignore) {
            }
        }
        GUIUtil.requestFocus(filterTextField);
    }

    @Override
    protected void deactivate() {
        filterTextField.textProperty().removeListener(filterTextFieldListener);
        sortedList.comparatorProperty().unbind();
        selectedMediationSubscription.unsubscribe();
        removeListenersOnSelectMediation();

        if (scene != null)
            scene.removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
    }

    protected void applyFilteredListPredicate(String filterString) {
        // If in trader view we must not display arbitrators own mediations as trader (must not happen anyway)
        filteredList.setPredicate(mediation -> !mediation.getMediatorPubKeyRing().equals(keyRing.getPubKeyRing()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onOpenContract(Mediation mediation) {
        contractWindow.show(mediation);
    }

    private void onTrySendMessage() {
        if (p2PService.isBootstrapped()) {
            String text = inputTextArea.getText();
            if (!text.isEmpty()) {
                if (text.length() < 5_000) {
                    onSendMessage(text, selectedMediation);
                } else {
                    new Popup<>().information(Res.get("popup.warning.messageTooLong")).show();
                }
            }
        } else {
            new Popup<>().information(Res.get("popup.warning.notFullyConnected")).show();
        }
    }

    private void onSendMessage(String inputText, Mediation mediation) {
        if (mediationCommunicationMessage != null) {
            mediationCommunicationMessage.arrivedProperty().removeListener(arrivedPropertyListener);
            mediationCommunicationMessage.storedInMailboxProperty().removeListener(storedInMailboxPropertyListener);
            mediationCommunicationMessage.sendMessageErrorProperty().removeListener(sendMessageErrorPropertyListener);
        }

        mediationCommunicationMessage = mediationManager.sendMediationDirectMessage(mediation, inputText, new ArrayList<>(tempAttachments));
        tempAttachments.clear();
        scrollToBottom();

        inputTextArea.setDisable(true);
        inputTextArea.clear();

        Timer timer = UserThread.runAfter(() -> {
            sendMsgInfoLabel.setVisible(true);
            sendMsgInfoLabel.setManaged(true);
            sendMsgInfoLabel.setText(Res.get("support.sendingMessage"));

            sendMsgBusyAnimation.play();
        }, 500, TimeUnit.MILLISECONDS);

        arrivedPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                hideSendMsgInfo(timer);
            }
        };
        storedInMailboxPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                sendMsgInfoLabel.setVisible(true);
                sendMsgInfoLabel.setManaged(true);
                sendMsgInfoLabel.setText(Res.get("support.receiverNotOnline"));
                hideSendMsgInfo(timer);
            }
        };
        sendMessageErrorPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                sendMsgInfoLabel.setVisible(true);
                sendMsgInfoLabel.setManaged(true);
                sendMsgInfoLabel.setText(Res.get("support.sendMessageError", newValue));
                hideSendMsgInfo(timer);
            }
        };
        if (mediationCommunicationMessage != null) {
            mediationCommunicationMessage.arrivedProperty().addListener(arrivedPropertyListener);
            mediationCommunicationMessage.storedInMailboxProperty().addListener(storedInMailboxPropertyListener);
            mediationCommunicationMessage.sendMessageErrorProperty().addListener(sendMessageErrorPropertyListener);
        }
    }

    private void hideSendMsgInfo(Timer timer) {
        timer.stop();
        inputTextArea.setDisable(false);

        UserThread.runAfter(() -> {
            sendMsgInfoLabel.setVisible(false);
            sendMsgInfoLabel.setManaged(false);
        }, 5);
        sendMsgBusyAnimation.stop();
    }

    private void onCloseMediation(Mediation mediation) {
        long protocolVersion = mediation.getContract().getOfferPayload().getProtocolVersion();
        if (protocolVersion == Version.TRADE_PROTOCOL_VERSION) {
// TODO: Replace this once the close mediation part is done
/*
            mediationSummaryWindow.onFinalizeMediation(() -> messagesAnchorPane.getChildren().remove(messagesInputBox))
                    .show(mediation);
*/
        } else {
            new Popup<>()
                    .warning(Res.get("support.wrongVersion", protocolVersion))
                    .show();
        }
    }

    private void onRequestUpload() {
        int totalSize = tempAttachments.stream().mapToInt(a -> a.getBytes().length).sum();
        if (tempAttachments.size() < 3) {
            FileChooser fileChooser = new FileChooser();
            int maxMsgSize = Connection.getPermittedMessageSize();
            int maxSizeInKB = maxMsgSize / 1024;
            fileChooser.setTitle(Res.get("support.openFile", maxSizeInKB));
           /* if (Utilities.isUnix())
                fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));*/
            File result = fileChooser.showOpenDialog(root.getScene().getWindow());
            if (result != null) {
                try {
                    URL url = result.toURI().toURL();
                    try (InputStream inputStream = url.openStream()) {
                        byte[] filesAsBytes = ByteStreams.toByteArray(inputStream);
                        int size = filesAsBytes.length;
                        int newSize = totalSize + size;
                        if (newSize > maxMsgSize) {
                            new Popup<>().warning(Res.get("support.attachmentTooLarge", (newSize / 1024), maxSizeInKB)).show();
                        } else if (size > maxMsgSize) {
                            new Popup<>().warning(Res.get("support.maxSize", maxSizeInKB)).show();
                        } else {
                            tempAttachments.add(new Attachment(result.getName(), filesAsBytes));
                            inputTextArea.setText(inputTextArea.getText() + "\n[" + Res.get("support.attachment") + " " + result.getName() + "]");
                        }
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                        log.error(e.getMessage());
                    }
                } catch (MalformedURLException e2) {
                    e2.printStackTrace();
                    log.error(e2.getMessage());
                }
            }
        } else {
            new Popup<>().warning(Res.get("support.tooManyAttachments")).show();
        }
    }

    private void onOpenAttachment(Attachment attachment) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(Res.get("support.save"));
        fileChooser.setInitialFileName(attachment.getFileName());
       /* if (Utilities.isUnix())
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));*/
        File file = fileChooser.showSaveDialog(root.getScene().getWindow());
        if (file != null) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(file.getAbsolutePath())) {
                fileOutputStream.write(attachment.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
            }
        }
    }

    private void removeListenersOnSelectMediation() {
        if (selectedMediation != null) {
            if (selectedMediationClosedPropertyListener != null)
                selectedMediation.isClosedProperty().removeListener(selectedMediationClosedPropertyListener);

            if (mediationCommunicationMessages != null && mediationDirectMessageListListener != null)
                mediationCommunicationMessages.removeListener(mediationDirectMessageListListener);
        }

        if (mediationCommunicationMessage != null) {
            if (arrivedPropertyListener != null)
                mediationCommunicationMessage.arrivedProperty().removeListener(arrivedPropertyListener);
            if (storedInMailboxPropertyListener != null)
                mediationCommunicationMessage.storedInMailboxProperty().removeListener(storedInMailboxPropertyListener);
        }

        if (messageListView != null)
            messageListView.prefWidthProperty().unbind();

        if (tableGroupHeadline != null)
            tableGroupHeadline.prefWidthProperty().unbind();

        if (messagesAnchorPane != null)
            messagesAnchorPane.prefWidthProperty().unbind();

        if (inputTextAreaTextSubscription != null)
            inputTextAreaTextSubscription.unsubscribe();
    }

    private void addListenersOnSelectMediation() {
        if (tableGroupHeadline != null) {
            tableGroupHeadline.prefWidthProperty().bind(root.widthProperty());
            messageListView.prefWidthProperty().bind(root.widthProperty());
            messagesAnchorPane.prefWidthProperty().bind(root.widthProperty());
            mediationCommunicationMessages.addListener(mediationDirectMessageListListener);
            if (selectedMediation != null)
                selectedMediation.isClosedProperty().addListener(selectedMediationClosedPropertyListener);
            inputTextAreaTextSubscription = EasyBind.subscribe(inputTextArea.textProperty(), t -> sendButton.setDisable(t.isEmpty()));
        }
    }

    private void onSelectMediation(Mediation mediation) {
        removeListenersOnSelectMediation();
        if (mediation == null) {
            if (root.getChildren().size() > 2)
                root.getChildren().remove(2);

            selectedMediation = null;
        } else if (selectedMediation != mediation) {
            this.selectedMediation = mediation;

            boolean isTrader = mediationManager.isTrader(selectedMediation);

            tableGroupHeadline = new TableGroupHeadline();
            tableGroupHeadline.setText(Res.get("support.messages"));

            AnchorPane.setTopAnchor(tableGroupHeadline, 10d);
            AnchorPane.setRightAnchor(tableGroupHeadline, 0d);
            AnchorPane.setBottomAnchor(tableGroupHeadline, 0d);
            AnchorPane.setLeftAnchor(tableGroupHeadline, 0d);

            mediationCommunicationMessages = selectedMediation.getMediationCommunicationMessages();
            SortedList<MediationCommunicationMessage> sortedList = new SortedList<>(mediationCommunicationMessages);
            sortedList.setComparator(Comparator.comparing(o -> new Date(o.getDate())));
            messageListView = new ListView<>(sortedList);
            messageListView.setId("message-list-view");

            messageListView.setMinHeight(150);
            AnchorPane.setTopAnchor(messageListView, 30d);
            AnchorPane.setRightAnchor(messageListView, 0d);
            AnchorPane.setLeftAnchor(messageListView, 0d);

            messagesAnchorPane = new AnchorPane();
            VBox.setVgrow(messagesAnchorPane, Priority.ALWAYS);

            inputTextArea = new BisqTextArea();
            inputTextArea.setPrefHeight(70);
            inputTextArea.setWrapText(true);
            if (!(this instanceof MediatorDisputeView))
                inputTextArea.setPromptText(Res.get("support.input.prompt"));

            sendButton = new AutoTooltipButton(Res.get("support.send"));
            sendButton.setDefaultButton(true);
            sendButton.setOnAction(e -> onTrySendMessage());
            inputTextAreaTextSubscription = EasyBind.subscribe(inputTextArea.textProperty(), t -> sendButton.setDisable(t.isEmpty()));

            Button uploadButton = new AutoTooltipButton(Res.get("support.addAttachments"));
            uploadButton.setOnAction(e -> onRequestUpload());

            sendMsgInfoLabel = new AutoTooltipLabel();
            sendMsgInfoLabel.setVisible(false);
            sendMsgInfoLabel.setManaged(false);
            sendMsgInfoLabel.setPadding(new Insets(5, 0, 0, 0));

            sendMsgBusyAnimation = new BusyAnimation(false);

            if (!selectedMediation.isClosed()) {
                HBox buttonBox = new HBox();
                buttonBox.setSpacing(10);
                buttonBox.getChildren().addAll(sendButton, uploadButton, sendMsgBusyAnimation, sendMsgInfoLabel);

                if (!isTrader) {
                    Button closeMediationButton = new AutoTooltipButton(Res.get("support.closeTicket"));
                    closeMediationButton.setOnAction(e -> onCloseMediation(selectedMediation));
                    closeMediationButton.setDefaultButton(true);
                    Pane spacer = new Pane();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    buttonBox.getChildren().addAll(spacer, closeMediationButton);
                }

                messagesInputBox = new VBox();
                messagesInputBox.setSpacing(10);
                messagesInputBox.getChildren().addAll(inputTextArea, buttonBox);
                VBox.setVgrow(buttonBox, Priority.ALWAYS);

                AnchorPane.setRightAnchor(messagesInputBox, 0d);
                AnchorPane.setBottomAnchor(messagesInputBox, 5d);
                AnchorPane.setLeftAnchor(messagesInputBox, 0d);

                AnchorPane.setBottomAnchor(messageListView, 120d);

                messagesAnchorPane.getChildren().addAll(tableGroupHeadline, messageListView, messagesInputBox);
            } else {
                AnchorPane.setBottomAnchor(messageListView, 0d);
                messagesAnchorPane.getChildren().addAll(tableGroupHeadline, messageListView);
            }

            messageListView.setCellFactory(new Callback<>() {
                @Override
                public ListCell<MediationCommunicationMessage> call(ListView<MediationCommunicationMessage> list) {
                    return new ListCell<>() {
                        ChangeListener<Boolean> sendMsgBusyAnimationListener;
                        final Pane bg = new Pane();
                        final ImageView arrow = new ImageView();
                        final Label headerLabel = new AutoTooltipLabel();
                        final Label messageLabel = new AutoTooltipLabel();
                        final Label copyIcon = new Label();
                        final HBox attachmentsBox = new HBox();
                        final AnchorPane messageAnchorPane = new AnchorPane();
                        final Label statusIcon = new Label();
                        final Label statusInfoLabel = new Label();
                        final HBox statusHBox = new HBox();
                        final double arrowWidth = 15d;
                        final double attachmentsBoxHeight = 20d;
                        final double border = 10d;
                        final double bottomBorder = 25d;
                        final double padding = border + 10d;
                        final double msgLabelPaddingRight = padding + 20d;

                        {
                            bg.setMinHeight(30);
                            messageLabel.setWrapText(true);
                            headerLabel.setTextAlignment(TextAlignment.CENTER);
                            attachmentsBox.setSpacing(5);
                            statusIcon.getStyleClass().add("small-text");
                            statusInfoLabel.getStyleClass().add("small-text");
                            statusInfoLabel.setPadding(new Insets(3, 0, 0, 0));
                            copyIcon.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
                            statusHBox.setSpacing(5);
                            statusHBox.getChildren().addAll(statusIcon, statusInfoLabel);
                            messageAnchorPane.getChildren().addAll(bg, arrow, headerLabel, messageLabel, copyIcon, attachmentsBox, statusHBox);
                        }

                        @Override
                        public void updateItem(final MediationCommunicationMessage message, boolean empty) {
                            super.updateItem(message, empty);
                            if (message != null && !empty) {
                                copyIcon.setOnMouseClicked(e -> Utilities.copyToClipboard(messageLabel.getText()));
                                messageLabel.setOnMouseClicked(event -> {
                                    if (2 > event.getClickCount()) {
                                        return;
                                    }
                                    GUIUtil.showSelectableTextModal(headerLabel.getText(), messageLabel.getText());
                                });

                                if (!messageAnchorPane.prefWidthProperty().isBound())
                                    messageAnchorPane.prefWidthProperty()
                                            .bind(messageListView.widthProperty().subtract(padding + GUIUtil.getScrollbarWidth(messageListView)));

                                AnchorPane.setTopAnchor(bg, 15d);
                                AnchorPane.setBottomAnchor(bg, bottomBorder);
                                AnchorPane.setTopAnchor(headerLabel, 0d);
                                AnchorPane.setBottomAnchor(arrow, bottomBorder + 5d);
                                AnchorPane.setTopAnchor(messageLabel, 25d);
                                AnchorPane.setTopAnchor(copyIcon, 25d);
                                AnchorPane.setBottomAnchor(attachmentsBox, bottomBorder + 10);

                                boolean senderIsTrader = message.isSenderIsTrader();
                                boolean isMyMsg = isTrader == senderIsTrader;

                                arrow.setVisible(!message.isSystemMessage());
                                arrow.setManaged(!message.isSystemMessage());
                                statusHBox.setVisible(false);

                                headerLabel.getStyleClass().removeAll("message-header", "my-message-header", "success-text",
                                        "highlight-static");
                                messageLabel.getStyleClass().removeAll("my-message", "message");
                                copyIcon.getStyleClass().removeAll("my-message", "message");

                                if (message.isSystemMessage()) {
                                    headerLabel.getStyleClass().addAll("message-header", "success-text");
                                    bg.setId("message-bubble-green");
                                    messageLabel.getStyleClass().add("my-message");
                                    copyIcon.getStyleClass().add("my-message");
                                    message.addWeakMessageStateListener(() -> updateMsgState(message));
                                    updateMsgState(message);
                                } else if (isMyMsg) {
                                    headerLabel.getStyleClass().add("my-message-header");
                                    bg.setId("message-bubble-blue");
                                    messageLabel.getStyleClass().add("my-message");
                                    copyIcon.getStyleClass().add("my-message");
                                    if (isTrader)
                                        arrow.setId("bubble_arrow_blue_left");
                                    else
                                        arrow.setId("bubble_arrow_blue_right");

                                    if (sendMsgBusyAnimationListener != null)
                                        sendMsgBusyAnimation.isRunningProperty().removeListener(sendMsgBusyAnimationListener);

                                    sendMsgBusyAnimationListener = (observable, oldValue, newValue) -> {
                                        if (!newValue)
                                            updateMsgState(message);
                                    };

                                    sendMsgBusyAnimation.isRunningProperty().addListener(sendMsgBusyAnimationListener);
                                    message.addWeakMessageStateListener(() -> updateMsgState(message));
                                    updateMsgState(message);
                                } else {
                                    headerLabel.getStyleClass().add("message-header");
                                    bg.setId("message-bubble-grey");
                                    messageLabel.getStyleClass().add("message");
                                    copyIcon.getStyleClass().add("message");
                                    if (isTrader)
                                        arrow.setId("bubble_arrow_grey_right");
                                    else
                                        arrow.setId("bubble_arrow_grey_left");
                                }

                                if (message.isSystemMessage()) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding);
                                    AnchorPane.setRightAnchor(headerLabel, padding);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyIcon, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.clearConstraints(statusHBox);
                                    AnchorPane.setLeftAnchor(statusHBox, padding);
                                } else if (senderIsTrader) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border + arrowWidth);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyIcon, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.clearConstraints(statusHBox);
                                    AnchorPane.setRightAnchor(statusHBox, padding);
                                } else {
                                    AnchorPane.setRightAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(bg, border + arrowWidth);
                                    AnchorPane.setRightAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight + arrowWidth);
                                    AnchorPane.setRightAnchor(copyIcon, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.clearConstraints(statusHBox);
                                    AnchorPane.setLeftAnchor(statusHBox, padding);
                                }
                                AnchorPane.setBottomAnchor(statusHBox, 7d);
                                headerLabel.setText(formatter.formatDateTime(new Date(message.getDate())));
                                messageLabel.setText(message.getMessage());
                                attachmentsBox.getChildren().clear();
                                if (message.getAttachments() != null && message.getAttachments().size() > 0) {
                                    AnchorPane.setBottomAnchor(messageLabel, bottomBorder + attachmentsBoxHeight + 10);
                                    attachmentsBox.getChildren().add(new AutoTooltipLabel(Res.get("support.attachments") + " ") {{
                                        setPadding(new Insets(0, 0, 3, 0));
                                        if (isMyMsg)
                                            getStyleClass().add("my-message");
                                        else
                                            getStyleClass().add("message");
                                    }});
                                    message.getAttachments().forEach(attachment -> {
                                        final Label icon = new Label();
                                        setPadding(new Insets(0, 0, 3, 0));
                                        if (isMyMsg)
                                            icon.getStyleClass().add("attachment-icon");
                                        else
                                            icon.getStyleClass().add("attachment-icon-black");

                                        AwesomeDude.setIcon(icon, AwesomeIcon.FILE_TEXT);
                                        icon.setPadding(new Insets(-2, 0, 0, 0));
                                        icon.setTooltip(new Tooltip(attachment.getFileName()));
                                        icon.setOnMouseClicked(event -> onOpenAttachment(attachment));
                                        attachmentsBox.getChildren().add(icon);
                                    });
                                } else {
                                    AnchorPane.setBottomAnchor(messageLabel, bottomBorder + 10);
                                }

                                // Need to set it here otherwise style is not correct
                                AwesomeDude.setIcon(copyIcon, AwesomeIcon.COPY, "16.0");
                                copyIcon.getStyleClass().addAll("icon", "copy-icon-mediations");

                                // TODO There are still some cell rendering issues on updates
                                setGraphic(messageAnchorPane);
                            } else {
                                if (sendMsgBusyAnimation != null && sendMsgBusyAnimationListener != null)
                                    sendMsgBusyAnimation.isRunningProperty().removeListener(sendMsgBusyAnimationListener);

                                messageAnchorPane.prefWidthProperty().unbind();

                                AnchorPane.clearConstraints(bg);
                                AnchorPane.clearConstraints(headerLabel);
                                AnchorPane.clearConstraints(arrow);
                                AnchorPane.clearConstraints(messageLabel);
                                AnchorPane.clearConstraints(copyIcon);
                                AnchorPane.clearConstraints(statusHBox);
                                AnchorPane.clearConstraints(attachmentsBox);

                                copyIcon.setOnMouseClicked(null);
                                messageLabel.setOnMouseClicked(null);
                                setGraphic(null);
                            }
                        }

                        private void updateMsgState(MediationCommunicationMessage message) {
                            boolean visible;
                            AwesomeIcon icon = null;
                            String text = null;
                            statusIcon.setTextFill(Paint.valueOf("#0f87c3"));
                            statusInfoLabel.setTextFill(Paint.valueOf("#0f87c3"));
                            statusHBox.setOpacity(1);
                            log.debug("updateMsgState msg-{}, ack={}, arrived={}", message.getMessage(),
                                    message.acknowledgedProperty().get(), message.arrivedProperty().get());
                            if (message.acknowledgedProperty().get()) {
                                visible = true;
                                icon = AwesomeIcon.OK_SIGN;
                                text = Res.get("support.acknowledged");

                            } else if (message.ackErrorProperty().get() != null) {
                                visible = true;
                                icon = AwesomeIcon.EXCLAMATION_SIGN;
                                text = Res.get("support.error", message.ackErrorProperty().get());
                                statusIcon.setTextFill(Paint.valueOf("#dd0000"));
                                statusInfoLabel.setTextFill(Paint.valueOf("#dd0000"));
                            } else if (message.arrivedProperty().get()) {
                                visible = true;
                                icon = AwesomeIcon.OK;
                                text = Res.get("support.arrived");
                                statusHBox.setOpacity(0.5);
                            } else if (message.storedInMailboxProperty().get()) {
                                visible = true;
                                icon = AwesomeIcon.ENVELOPE;
                                text = Res.get("support.savedInMailbox");
                                statusHBox.setOpacity(0.5);
                            } else {
                                visible = false;
                                log.debug("updateMsgState called but no msg state available. message={}", message);
                            }

                            statusHBox.setVisible(visible);
                            if (visible) {
                                AwesomeDude.setIcon(statusIcon, icon, "14");
                                statusIcon.setTooltip(new Tooltip(text));
                                statusInfoLabel.setText(text);
                            }
                        }
                    };
                }
            });

            if (root.getChildren().size() > 2)
                root.getChildren().remove(2);
            root.getChildren().add(2, messagesAnchorPane);

            scrollToBottom();
        }

        addListenersOnSelectMediation();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Table
    ///////////////////////////////////////////////////////////////////////////////////////////

    private TableColumn<Mediation, Mediation> getSelectColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("shared.select"));
        column.setMinWidth(80);
        column.setMaxWidth(80);
        column.setSortable(false);
        column.getStyleClass().add("first-column");

        column.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        column.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation,
                            Mediation> column) {
                        return new TableCell<>() {

                            Button button;

                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = new AutoTooltipButton(Res.get("shared.select"));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(e -> tableView.getSelectionModel().select(item));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getContractColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("shared.details")) {
            {
                setMinWidth(80);
                setSortable(false);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = new AutoTooltipButton(Res.get("shared.details"));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(e -> onOpenContract(item));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getDateColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("shared.date")) {
            {
                setMinWidth(180);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(formatter.formatDateTime(item.getOpeningDate()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getTradeIdColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("shared.tradeId")) {
            {
                setMinWidth(110);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    field = new HyperlinkWithIcon(item.getShortTradeId());
                                    Optional<Trade> tradeOptional = tradeManager.getTradeById(item.getTradeId());
                                    if (tradeOptional.isPresent()) {
                                        field.setMouseTransparent(false);
                                        field.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                        field.setOnAction(event -> tradeDetailsWindow.show(tradeOptional.get()));
                                    } else {
                                        field.setMouseTransparent(true);
                                    }
                                    setGraphic(field);
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getBuyerOnionAddressColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("support.buyerAddress")) {
            {
                setMinWidth(170);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(getBuyerOnionAddressColumnLabel(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getSellerOnionAddressColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("support.sellerAddress")) {
            {
                setMinWidth(170);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(getSellerOnionAddressColumnLabel(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }


    protected String getBuyerOnionAddressColumnLabel(Mediation item) {
        Contract contract = item.getContract();
        if (contract != null) {
            NodeAddress buyerNodeAddress = contract.getBuyerNodeAddress();
            if (buyerNodeAddress != null)
                return buyerNodeAddress.getHostNameWithoutPostFix() + " (" + mediationManager.getNrOfMediations(true, contract) + ")";
            else
                return Res.get("shared.na");
        } else {
            return Res.get("shared.na");
        }
    }

    protected String getSellerOnionAddressColumnLabel(Mediation item) {
        Contract contract = item.getContract();
        if (contract != null) {
            NodeAddress sellerNodeAddress = contract.getSellerNodeAddress();
            if (sellerNodeAddress != null)
                return sellerNodeAddress.getHostNameWithoutPostFix() + " (" + mediationManager.getNrOfMediations(false, contract) + ")";
            else
                return Res.get("shared.na");
        } else {
            return Res.get("shared.na");
        }
    }

    private TableColumn<Mediation, Mediation> getMarketColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("shared.market")) {
            {
                setMinWidth(130);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(formatter.getCurrencyPair(item.getContract().getOfferPayload().getCurrencyCode()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getRoleColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("support.role")) {
            {
                setMinWidth(130);
            }
        };
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    if (item.isMediationOpenerIsMaker())
                                        setText(item.isMediationOpenerIsBuyer() ? Res.get("support.buyerOfferer") : Res.get("support.sellerOfferer"));
                                    else
                                        setText(item.isMediationOpenerIsBuyer() ? Res.get("support.buyerTaker") : Res.get("support.sellerTaker"));
                                } else {
                                    setText("");
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<Mediation, Mediation> getStateColumn() {
        TableColumn<Mediation, Mediation> column = new AutoTooltipTableColumn<>(Res.get("support.state")) {
            {
                setMinWidth(50);
            }
        };
        column.getStyleClass().add("last-column");
        column.setCellValueFactory((mediation) -> new ReadOnlyObjectWrapper<>(mediation.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<Mediation, Mediation> call(TableColumn<Mediation, Mediation> column) {
                        return new TableCell<>() {


                            ReadOnlyBooleanProperty closedProperty;
                            ChangeListener<Boolean> listener;

                            @Override
                            public void updateItem(final Mediation item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    listener = (observable, oldValue, newValue) -> {
                                        setText(newValue ? Res.get("support.closed") : Res.get("support.open"));
                                        getTableRow().setOpacity(newValue ? 0.4 : 1);
                                    };
                                    closedProperty = item.isClosedProperty();
                                    closedProperty.addListener(listener);
                                    boolean isClosed = item.isClosed();
                                    setText(isClosed ? Res.get("support.closed") : Res.get("support.open"));
                                    getTableRow().setOpacity(isClosed ? 0.4 : 1);
                                } else {
                                    if (closedProperty != null) {
                                        closedProperty.removeListener(listener);
                                        closedProperty = null;
                                    }
                                    setText("");
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private void scrollToBottom() {
        if (messageListView != null)
            UserThread.execute(() -> messageListView.scrollTo(Integer.MAX_VALUE));
    }
}
