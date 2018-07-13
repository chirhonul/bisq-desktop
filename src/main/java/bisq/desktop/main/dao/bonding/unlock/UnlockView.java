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

package bisq.desktop.main.dao.bonding.unlock;

import bisq.desktop.Navigation;
import bisq.desktop.common.view.ActivatableView;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.AutoTooltipTableColumn;
import bisq.desktop.components.HyperlinkWithIcon;
import bisq.desktop.main.MainView;
import bisq.desktop.main.dao.wallet.BsqBalanceUtil;
import bisq.desktop.main.funds.FundsView;
import bisq.desktop.main.funds.deposit.DepositView;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.util.GUIUtil;
import bisq.desktop.util.validation.BsqValidator;

import bisq.core.btc.wallet.BsqBalanceListener;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.WalletsSetup;
import bisq.core.dao.DaoFacade;
import bisq.core.dao.state.BlockListener;
import bisq.core.dao.state.StateService;
import bisq.core.dao.state.blockchain.Block;
import bisq.core.dao.state.blockchain.TxOutput;
import bisq.core.dao.state.blockchain.TxType;
import bisq.core.locale.Res;
import bisq.core.user.Preferences;
import bisq.core.util.BsqFormatter;

import bisq.network.p2p.P2PService;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;

import de.jensd.fx.fontawesome.AwesomeIcon;

import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;

import javafx.util.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@FxmlView
public class UnlockView extends ActivatableView<GridPane, Void> implements BsqBalanceListener, BlockListener {
    private TableView<LockupTxListItem> tableView;

    private final BsqWalletService bsqWalletService;
    private final BtcWalletService btcWalletService;
    private final BsqFormatter bsqFormatter;
    private final BsqBalanceUtil bsqBalanceUtil;
    private final BsqValidator bsqValidator;
    private final DaoFacade daoFacade;
    private final Preferences preferences;
    private final StateService stateService;
    private final WalletsSetup walletsSetup;
    private final P2PService p2PService;
    private final Navigation navigation;

    private int gridRow = 0;
    private boolean synced;
    private LockupTxListItem selectedItem;

    private final ObservableList<LockupTxListItem> observableList = FXCollections.observableArrayList();
    private final FilteredList<LockupTxListItem> lockupTxs = new FilteredList<>(observableList);

    private ListChangeListener<Transaction> walletBsqTransactionsListener;
    private ChangeListener<Number> walletChainHeightListener;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private UnlockView(BsqWalletService bsqWalletService,
                       BtcWalletService btcWalletService,
                       BsqFormatter bsqFormatter,
                       BsqBalanceUtil bsqBalanceUtil,
                       BsqValidator bsqValidator,
                       DaoFacade daoFacade,
                       Preferences preferences,
                       StateService stateService,
                       WalletsSetup walletsSetup,
                       P2PService p2PService,
                       Navigation navigation) {
        this.bsqWalletService = bsqWalletService;
        this.btcWalletService = btcWalletService;
        this.bsqFormatter = bsqFormatter;
        this.bsqBalanceUtil = bsqBalanceUtil;
        this.bsqValidator = bsqValidator;
        this.daoFacade = daoFacade;
        this.preferences = preferences;
        this.stateService = stateService;
        this.walletsSetup = walletsSetup;
        this.p2PService = p2PService;
        this.navigation = navigation;
    }

    @Override
    public void initialize() {
        gridRow = bsqBalanceUtil.addGroup(root, gridRow);

        tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPrefHeight(300);
        addTxIdColumn();
        addAmountColumn();
        addLockTimeColumn();
        addUnlockColumn();

        lockupTxs.setPredicate(LockupTxListItem::isLockupAndUnspent);
        walletBsqTransactionsListener = change -> updateList();
        walletChainHeightListener = (observable, oldValue, newValue) -> onUpdateAnyChainHeight();

        VBox vBox = new VBox();
        vBox.setSpacing(10);
        GridPane.setRowIndex(vBox, ++gridRow);
        GridPane.setColumnSpan(vBox, 2);
        GridPane.setMargin(vBox, new Insets(40, -10, 5, -10));
        vBox.getChildren().addAll(tableView);
        root.getChildren().add(vBox);

    }

    private void addTxIdColumn() {
        TableColumn<LockupTxListItem, LockupTxListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.txId"));

        column.setCellValueFactory(item -> new ReadOnlyObjectWrapper<>(item.getValue()));
        column.setMinWidth(60);
        column.setCellFactory(
                new Callback<TableColumn<LockupTxListItem, LockupTxListItem>, TableCell<LockupTxListItem,
                        LockupTxListItem>>() {

                    @Override
                    public TableCell<LockupTxListItem, LockupTxListItem> call(TableColumn<LockupTxListItem,
                            LockupTxListItem> column) {
                        return new TableCell<LockupTxListItem, LockupTxListItem>() {
                            private HyperlinkWithIcon hyperlinkWithIcon;

                            @Override
                            public void updateItem(final LockupTxListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String transactionId = item.getTxId();
                                    hyperlinkWithIcon = new HyperlinkWithIcon(transactionId, AwesomeIcon.EXTERNAL_LINK);
                                    hyperlinkWithIcon.setOnAction(event -> openTxInBlockExplorer(item));
                                    hyperlinkWithIcon.setTooltip(new Tooltip(Res.get("tooltip.openBlockchainForTx", transactionId)));
                                    setGraphic(hyperlinkWithIcon);
                                } else {
                                    setGraphic(null);
                                    if (hyperlinkWithIcon != null)
                                        hyperlinkWithIcon.setOnAction(null);
                                }
                            }
                        };
                    }
                });
        tableView.getColumns().add(column);
    }

    private void addAmountColumn() {
        TableColumn<LockupTxListItem, LockupTxListItem> column =
                new AutoTooltipTableColumn<>(Res.get("shared.amountWithCur", "BSQ"));
        column.setMinWidth(120);
        column.setMaxWidth(column.getMinWidth());

        column.setCellValueFactory((item) -> new ReadOnlyObjectWrapper<>(item.getValue()));
        column.setCellFactory(new Callback<TableColumn<LockupTxListItem, LockupTxListItem>,
                TableCell<LockupTxListItem, LockupTxListItem>>() {

            @Override
            public TableCell<LockupTxListItem, LockupTxListItem> call(TableColumn<LockupTxListItem,
                    LockupTxListItem> column) {
                return new TableCell<LockupTxListItem, LockupTxListItem>() {

                    @Override
                    public void updateItem(final LockupTxListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            TxType txType = item.getTxType();
                            setText(item.getConfirmations() > 0 && txType.ordinal() > TxType.INVALID.ordinal() ?
                                    bsqFormatter.formatCoin(item.getAmount()) :
                                    Res.get("shared.na"));
                        } else
                            setText("");
                    }
                };
            }
        });
        tableView.getColumns().add(column);
    }

    private void addLockTimeColumn() {
        TableColumn<LockupTxListItem, LockupTxListItem> column =
                new AutoTooltipTableColumn<>(Res.get("dao.bonding.unlock.time"));
        column.setMinWidth(120);
        column.setMaxWidth(column.getMinWidth());

        column.setCellValueFactory((item) -> new ReadOnlyObjectWrapper<>(item.getValue()));
        column.setCellFactory(new Callback<TableColumn<LockupTxListItem, LockupTxListItem>,
                TableCell<LockupTxListItem, LockupTxListItem>>() {

            @Override
            public TableCell<LockupTxListItem, LockupTxListItem> call(TableColumn<LockupTxListItem,
                    LockupTxListItem> column) {
                return new TableCell<LockupTxListItem, LockupTxListItem>() {

                    @Override
                    public void updateItem(final LockupTxListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            TxType txType = item.getTxType();
                            setText(item.getConfirmations() > 0 && txType.ordinal() > TxType.INVALID.ordinal() ?
                                    Integer.toString(item.getLockTime()) :
                                    Res.get("shared.na"));
                        } else
                            setText("");
                    }
                };
            }
        });
        tableView.getColumns().add(column);
    }

    private void addUnlockColumn() {
        TableColumn<LockupTxListItem, LockupTxListItem> unlockColumn = new TableColumn<>();
        unlockColumn.setMinWidth(130);
        unlockColumn.setMaxWidth(unlockColumn.getMinWidth());

        unlockColumn.setCellValueFactory((item) -> new ReadOnlyObjectWrapper<>(item.getValue()));

        unlockColumn.setCellFactory(new Callback<TableColumn<LockupTxListItem, LockupTxListItem>,
                TableCell<LockupTxListItem, LockupTxListItem>>() {

            @Override
            public TableCell<LockupTxListItem, LockupTxListItem> call(TableColumn<LockupTxListItem,
                    LockupTxListItem> column) {
                return new TableCell<LockupTxListItem, LockupTxListItem>() {
                    Button button;

                    @Override
                    public void updateItem(final LockupTxListItem item, boolean empty) {
                        super.updateItem(item, empty);

                        if (item != null && !empty) {
                            if (button == null) {
                                button = item.getButton();
                                button.setOnAction(e -> {
                                    UnlockView.this.selectedItem = item;
                                    UnlockView.this.onButtonClick();
                                });
                                setGraphic(button);
                            }
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
        unlockColumn.setComparator(Comparator.comparing(LockupTxListItem::getConfirmations));
        tableView.getColumns().add(unlockColumn);
    }

    private void onButtonClick() {
        if (GUIUtil.isReadyForTxBroadcast(p2PService, walletsSetup)) {
            Optional<TxOutput> lockupTxOutput = daoFacade.getLockupTxOutput(selectedItem.getTxId());
            if (!lockupTxOutput.isPresent()) {
                log.warn("Lockup output not found, txId = ", selectedItem.getTxId());
                return;
            }

            Coin unlockAmount = Coin.valueOf(lockupTxOutput.get().getValue());
            Optional<Integer> opLockTime = daoFacade.getLockTime(selectedItem.getTxId());
            int lockTime = opLockTime.isPresent() ? opLockTime.get() : -1;

            try {
                new Popup<>().headLine(Res.get("dao.bonding.unlock.sendTx.headline"))
                        .confirmation(Res.get("dao.bonding.unlock.sendTx.details",
                                bsqFormatter.formatCoinWithCode(unlockAmount),
                                lockTime
                        ))
                        .actionButtonText(Res.get("shared.yes"))
                        .onAction(() -> {
                            daoFacade.publishUnlockTx(selectedItem.getTxId(),
                                    () -> {
                                        new Popup<>().confirmation(Res.get("dao.tx.published.success")).show();
                                    },
                                    errorMessage -> new Popup<>().warning(errorMessage.toString()).show()
                            );
                        })
                        .closeButtonText(Res.get("shared.cancel"))
                        .show();
            } catch (Throwable t) {
                if (t instanceof InsufficientMoneyException) {
                    final Coin missingCoin = ((InsufficientMoneyException) t).missing;
                    final String missing = missingCoin != null ? missingCoin.toFriendlyString() : "null";
                    //noinspection unchecked
                    new Popup<>().warning(Res.get("popup.warning.insufficientBtcFundsForBsqTx", missing))
                            .actionButtonTextWithGoTo("navigation.funds.depositFunds")
                            .onAction(() -> navigation.navigateTo(MainView.class, FundsView.class, DepositView.class))
                            .show();
                } else {
                    log.error(t.toString());
                    t.printStackTrace();
                    new Popup<>().warning(t.getMessage()).show();
                }
            }
        } else {
            GUIUtil.showNotReadyForTxBroadcastPopups(p2PService, walletsSetup);
        }
        log.info("unlock tx: {}", selectedItem.getTxId());
    }

    private void openTxInBlockExplorer(LockupTxListItem item) {
        if (item.getTxId() != null)
            GUIUtil.openWebPage(preferences.getBsqBlockChainExplorer().txUrl + item.getTxId());
    }

    @Override
    protected void activate() {
        bsqBalanceUtil.activate();
        bsqWalletService.addBsqBalanceListener(this);
        onUpdateBalances(bsqWalletService.getAvailableBalance(),
                bsqWalletService.getAvailableNonBsqBalance(),
                bsqWalletService.getUnverifiedBalance(),
                bsqWalletService.getLockedForVotingBalance(),
                bsqWalletService.getLockupBondsBalance(),
                bsqWalletService.getUnlockingBondsBalance());

        bsqWalletService.getWalletTransactions().addListener(walletBsqTransactionsListener);
        bsqWalletService.addBsqBalanceListener(this);
        btcWalletService.getChainHeightProperty().addListener(walletChainHeightListener);

        tableView.setItems(lockupTxs);

        daoFacade.addBlockListener(this);

        updateList();
        onUpdateAnyChainHeight();
    }

    @Override
    public void onBlockAdded(Block block) {
        onUpdateAnyChainHeight();
    }

    private void onUpdateAnyChainHeight() {
        final int bsqBlockChainHeight = daoFacade.getChainHeight();
        final int bsqWalletChainHeight = bsqWalletService.getBestChainHeight();
        if (bsqWalletChainHeight > 0) {
            synced = bsqWalletChainHeight == bsqBlockChainHeight;
        }
        updateList();
    }

    private void updateList() {
        observableList.forEach(LockupTxListItem::cleanup);

        // copy list to avoid ConcurrentModificationException
        final List<Transaction> walletTransactions = new ArrayList<>(bsqWalletService.getWalletTransactions());
        List<LockupTxListItem> items = walletTransactions.stream()
                .map(transaction -> {
                    return new LockupTxListItem(transaction,
                            bsqWalletService,
                            btcWalletService,
                            daoFacade,
                            stateService,
                            transaction.getUpdateTime(),
                            bsqFormatter);
                })
                .collect(Collectors.toList());
        observableList.setAll(items);
    }

    @Override
    protected void deactivate() {
        bsqBalanceUtil.deactivate();
        bsqWalletService.removeBsqBalanceListener(this);

        lockupTxs.predicateProperty().unbind();
        bsqWalletService.getWalletTransactions().removeListener(walletBsqTransactionsListener);
        bsqWalletService.removeBsqBalanceListener(this);
        btcWalletService.getChainHeightProperty().removeListener(walletChainHeightListener);
        daoFacade.removeBlockListener(this);

        observableList.forEach(LockupTxListItem::cleanup);
    }

    @Override
    public void onUpdateBalances(Coin confirmedBalance,
                                 Coin availableNonBsqBalance,
                                 Coin pendingBalance,
                                 Coin lockedForVotingBalance,
                                 Coin lockupBondsBalance,
                                 Coin unlockingBondsBalance) {
        bsqValidator.setAvailableBalance(confirmedBalance);
    }
}