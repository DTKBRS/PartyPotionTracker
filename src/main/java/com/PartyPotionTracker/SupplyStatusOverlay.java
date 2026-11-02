package com.PartyPotionTracker;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

/** Shows a small warning only when a party member is low on tracked raid supplies. */
public class SupplyStatusOverlay extends OverlayPanel {
    private final PotionTrackerPlugin plugin;

    @Inject
    SupplyStatusOverlay(PotionTrackerPlugin plugin) {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!plugin.isSupportedRaid()) return null;
        int threshold = Math.max(0, plugin.getConfig().lowSupplyThreshold());
        if (threshold == 0) return null;

        List<SupplyInventoryView.Row> lowSupplyPlayers =
                plugin.inventoryView().rows.stream()
                        .filter(row -> "Current".equals(row.status) && row.inventory != null)
                        .filter(row -> trackedDoses(row) <= threshold)
                        .collect(Collectors.toList());
        if (lowSupplyPlayers.isEmpty()) return null;

        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(LineComponent.builder().left("Low supplies").build());
        for (SupplyInventoryView.Row row : lowSupplyPlayers) {
            panelComponent
                    .getChildren()
                    .add(
                            LineComponent.builder()
                                    .left(row.name)
                                    .right(trackedDoses(row) + " doses")
                                    .build());
        }
        return super.render(graphics);
    }

    private int trackedDoses(SupplyInventoryView.Row row) {
        return row.inventory.doses.values().stream().mapToInt(Integer::intValue).sum();
    }
}
