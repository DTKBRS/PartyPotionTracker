package com.PartyPotionTracker;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

public class SupplyStatusOverlay extends OverlayPanel {
    private final PotionTrackerPlugin plugin;
    @Inject SupplyStatusOverlay(PotionTrackerPlugin plugin) { this.plugin = plugin; setPosition(OverlayPosition.TOP_LEFT); }
    @Override public Dimension render(Graphics2D graphics) {
        if (!plugin.isInsideRaid()) return null;
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(LineComponent.builder().left("Party supplies").build());
        panelComponent.getChildren().add(LineComponent.builder().left(plugin.trackingStatus()).build());
        return super.render(graphics);
    }
}
