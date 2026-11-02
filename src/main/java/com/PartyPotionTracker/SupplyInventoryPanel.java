package com.PartyPotionTracker;

import net.runelite.client.ui.PluginPanel;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Swing components are updated only on the event-dispatch thread. */
final class SupplyInventoryPanel extends PluginPanel {
    private final JLabel totals = new JLabel();
    private final JLabel status = new JLabel();
    private final JTextArea otherSupplies = new JTextArea();
    private final PlayerTable model = new PlayerTable();

    SupplyInventoryPanel() {
        setLayout(new BorderLayout(0, 12));
        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.setOpaque(false);
        JLabel title = new JLabel("Carried raid supplies · doses");
        title.setForeground(Color.WHITE);
        totals.setForeground(Color.WHITE);
        status.setForeground(Color.LIGHT_GRAY);
        heading.add(title);
        heading.add(Box.createVerticalStrut(12));
        heading.add(totals);
        heading.add(Box.createVerticalStrut(10));
        heading.add(status);
        add(heading, BorderLayout.NORTH);
        JTable table =
                new JTable(model) {
                    @Override
                    public String getToolTipText(MouseEvent event) {
                        int row = rowAtPoint(event.getPoint());
                        if (row < 0 || row >= model.view.rows.size()) return null;
                        SupplyInventoryView.Row player = model.view.rows.get(row);
                        return player.inventory == null
                                ? "No inventory report for this raid session"
                                : player.status + ": " + player.inventory.bottleSummary();
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
        otherSupplies.setEditable(false);
        otherSupplies.setLineWrap(true);
        otherSupplies.setWrapStyleWord(true);
        otherSupplies.setOpaque(false);
        otherSupplies.setFont(getFont());
        otherSupplies.setForeground(Color.LIGHT_GRAY);
        JScrollPane details = new JScrollPane(otherSupplies);
        details.setPreferredSize(new Dimension(210, 160));
        details.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        details.getViewport().setBackground(getBackground());
        details.setBorder(BorderFactory.createEmptyBorder());
        add(details, BorderLayout.SOUTH);
        render(SupplyInventoryView.inactive("Enter a raid to track supplies"));
    }

    void render(SupplyInventoryView view) {
        totals.setText(
                "<html>Brews: <b>"
                        + view.total(SupplyInventory.BREWS)
                        + "</b><br>Restores: <b>"
                        + view.total(SupplyInventory.RESTORES)
                        + "</b><br>Prayer potions: <b>"
                        + view.total(SupplyInventory.PRAYER)
                        + "</b></html>");
        status.setText(
                "<html>" + view.reportingMembers + " reporting<br>" + view.status + "</html>");
        status.setToolTipText(
                "Totals include current reports only. Unknown party members may be outside this"
                        + " raid.");
        model.view = view;
        model.fireTableDataChanged();
        java.util.List<String> other = new ArrayList<>();
        view.totals.forEach(
                (name, count) -> {
                    if (!name.equals(SupplyInventory.BREWS)
                            && !name.equals(SupplyInventory.RESTORES)
                            && !name.equals(SupplyInventory.PRAYER)) other.add(name + ": " + count);
                });
        otherSupplies.setText(
                "Raid variants / other potions (doses)\n"
                        + (other.isEmpty() ? "—" : String.join("\n", other))
                        + "\n\n"
                        + "Ground supplies are excluded. Hover a player for bottle counts. Stale"
                        + " rows are excluded from totals.");
    }

    private static final class PlayerTable extends AbstractTableModel {
        SupplyInventoryView view = SupplyInventoryView.inactive("");
        private final String[] columns = {"Player", "Brew", "Restore", "Prayer"};

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        public int getRowCount() {
            return view.rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int row, int column) {
            SupplyInventoryView.Row player = view.rows.get(row);
            if (column == 0)
                return "<html>"
                        + escapeHtml(player.name)
                        + ("Current".equals(player.status)
                                ? ""
                                : "<br><small>" + player.status + "</small>")
                        + "</html>";
            if (player.inventory == null) return "?";
            String potion =
                    column == 1
                            ? SupplyInventory.BREWS
                            : column == 2 ? SupplyInventory.RESTORES : SupplyInventory.PRAYER;
            return player.inventory.doses(potion) + ("Current".equals(player.status) ? "" : "*");
        }
    }
}
