package com.PartyPotionTracker;

import net.runelite.client.ui.PluginPanel;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Swing components are updated only on the event-dispatch thread. */
final class SupplyInventoryPanel extends PluginPanel {
    private final JLabel status = new JLabel();
    private final JTextArea eventLog = new JTextArea();
    private final PlayerTable model = new PlayerTable();
    private final PotionTrackerConfig config;

    SupplyInventoryPanel(PotionTrackerConfig config) {
        this.config = config;
        setLayout(new BorderLayout(0, 12));
        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.setOpaque(false);
        JLabel title = new JLabel("Raid supplies");
        title.setForeground(Color.WHITE);
        status.setForeground(Color.LIGHT_GRAY);
        heading.add(title);
        heading.add(Box.createVerticalStrut(8));
        heading.add(status);
        add(heading, BorderLayout.NORTH);
        JTable table =
                new JTable(model) {
                    @Override
                    public String getToolTipText(MouseEvent event) {
                        int row = rowAtPoint(event.getPoint());
                        if (row <= 0 || row > model.view.rows.size()) return null;
                        SupplyInventoryView.Row player = model.view.rows.get(row - 1);
                        return player.inventory == null
                                ? "No supply count available yet"
                                : player.inventory.bottleSummary();
                    }
                };
        table.setRowHeight(40);
        table.setBackground(new Color(35, 35, 35));
        table.setForeground(Color.WHITE);
        table.setGridColor(new Color(65, 65, 65));
        table.getTableHeader().setBackground(new Color(45, 45, 45));
        table.getTableHeader().setForeground(Color.WHITE);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(0).setMinWidth(75);
        for (int column = 1; column < 4; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(43);
            table.getColumnModel().getColumn(column).setMinWidth(35);
        }
        table.setPreferredScrollableViewportSize(new Dimension(220, 240));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        add(scroll, BorderLayout.CENTER);
        eventLog.setEditable(false);
        eventLog.setLineWrap(true);
        eventLog.setWrapStyleWord(true);
        eventLog.setOpaque(false);
        eventLog.setFont(getFont());
        eventLog.setForeground(Color.LIGHT_GRAY);
        JScrollPane details = new JScrollPane(eventLog);
        details.setPreferredSize(new Dimension(210, 160));
        details.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        details.getViewport().setBackground(getBackground());
        details.setBorder(BorderFactory.createEmptyBorder());
        add(details, BorderLayout.SOUTH);
        render(SupplyInventoryView.inactive("Enter a raid to track supplies"), java.util.Collections.emptyList());
    }

    void render(SupplyInventoryView view, java.util.List<SupplyEventRecord> events) {
        status.setText(
                view.rows.isEmpty()
                        ? "No raid supplies yet"
                        : view.reportingMembers + " player" + (view.reportingMembers == 1 ? "" : "s") + " tracked");
        status.setToolTipText(
                "Supply counts update while players are in the raid.");
        List<SupplyInventoryView.Row> rows = new ArrayList<>(view.rows);
        Comparator<SupplyInventoryView.Row> byName =
                Comparator.comparing(row -> row.name, String.CASE_INSENSITIVE_ORDER);
        if (config.panelSortOrder() == SupplyPanelSortOrder.LOWEST_SUPPLIES) {
            rows.sort(
                    Comparator.comparingInt(SupplyInventoryPanel::trackedDoses)
                            .thenComparing(byName));
        } else {
            rows.sort(byName);
        }
        model.view = new SupplyInventoryView(view.status, rows);
        model.fireTableDataChanged();
        StringBuilder log = new StringBuilder("Recent events\n");
        if (events.isEmpty()) log.append("—");
        else {
            int start = Math.max(0, events.size() - 30);
            for (int i = events.size() - 1; i >= start; i--) {
                SupplyEventRecord event = events.get(i);
                String itemName = event.itemName == null ? "Unknown supply" : event.itemName;
                log.append(event.drop ? "Drop" : "Pickup")
                        .append(" · ").append(event.actor)
                        .append(" · ").append(itemName)
                        .append(" × ").append(event.quantity).append('\n');
            }
        }
        eventLog.setText(log.toString());
    }

    private static int trackedDoses(SupplyInventoryView.Row row) {
        return row.inventory == null
                ? Integer.MAX_VALUE
                : row.inventory.doses.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static final class PlayerTable extends AbstractTableModel {
        SupplyInventoryView view = SupplyInventoryView.inactive("");
        private final String[] columns = {"Player", "Brew", "Restore", "Prayer"};

        public int getRowCount() {
            return view.rows.size() + 1;
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int row, int column) {
            if (row == 0) {
                if (column == 0) return "Total";
                String potion = column == 1 ? SupplyInventory.BREWS
                        : column == 2 ? SupplyInventory.RESTORES : SupplyInventory.PRAYER;
                return view.total(potion);
            }
            SupplyInventoryView.Row player = view.rows.get(row - 1);
            if (column == 0)
                return player.name;
            if (player.inventory == null) return "—";
            String potion =
                    column == 1
                            ? SupplyInventory.BREWS
                            : column == 2 ? SupplyInventory.RESTORES : SupplyInventory.PRAYER;
            return player.inventory.doses(potion);
        }
    }
}
