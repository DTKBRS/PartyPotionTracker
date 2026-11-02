package com.PartyPotionTracker;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import java.awt.Dimension;
import java.awt.Graphics2D;

import javax.inject.Inject;

public class SupplyStatusOverlay extends OverlayPanel {
    private final PotionTrackerPlugin plugin;

    @Inject
    SupplyStatusOverlay(PotionTrackerPlugin plugin) {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!plugin.isInsideRaid()) return null;
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(LineComponent.builder().left("Party supplies").build());
        panelComponent
                .getChildren()
                .add(LineComponent.builder().left(plugin.trackingStatus()).build());
        if (!plugin.eventLog().isEmpty()) {
            panelComponent
                    .getChildren()
                    .add(LineComponent.builder().left("Confirmed events").right(Integer.toString(plugin.eventLog().size())).build());
        }
        if (plugin.getConfig().showSupplyTotals()) {
            SupplyInventoryView inventory = plugin.inventoryView();
            panelComponent
                    .getChildren()
                    .add(
                            LineComponent.builder()
                                    .left(
                                            "Carried doses · "
                                                    + inventory.reportingMembers
                                                    + " reporting")
                                    .build());
            if (plugin.getConfig().showBrews()) addTotal("Brews", inventory.total(SupplyInventory.BREWS));
            if (plugin.getConfig().showRestores()) addTotal("Restores", inventory.total(SupplyInventory.RESTORES));
            if (plugin.getConfig().showPrayer()) addTotal("Prayer", inventory.total(SupplyInventory.PRAYER));
            int threshold = Math.max(0, plugin.getConfig().lowSupplyThreshold());
            if (threshold > 0) {
                inventory.rows.stream()
                        .filter(row -> "Current".equals(row.status) && row.inventory != null)
                        .filter(row -> trackedDoses(row) <= threshold)
                        .findFirst()
                        .ifPresent(row -> panelComponent.getChildren().add(
                                LineComponent.builder().left("Low supplies").right(row.name).build()));
            }
        }
        return super.render(graphics);
    }

    private void addTotal(String label, String value) {
        panelComponent.getChildren().add(LineComponent.builder().left(label).right(value).build());
    }

    private int trackedDoses(SupplyInventoryView.Row row) {
        int total = 0;
        if (plugin.getConfig().showBrews()) total += row.inventory.doses.getOrDefault(SupplyInventory.BREWS, 0);
        if (plugin.getConfig().showRestores()) total += row.inventory.doses.getOrDefault(SupplyInventory.RESTORES, 0);
        if (plugin.getConfig().showPrayer()) total += row.inventory.doses.getOrDefault(SupplyInventory.PRAYER, 0);
        return total;
    }
}
