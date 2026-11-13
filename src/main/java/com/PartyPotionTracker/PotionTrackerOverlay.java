package com.PartyPotionTracker;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import lombok.extern.slf4j.Slf4j;
import javax.inject.Inject;
import java.awt.*;

@Slf4j
public class PotionTrackerOverlay extends Overlay {
    private final Client client;
    private final PotionTrackerPlugin plugin;

    @Inject
    public PotionTrackerOverlay(Client client, PotionTrackerPlugin plugin) {

        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.getConfig().usePotionHighlight()) {
            for (TrackedItem trackedItem : plugin.getPickupManager().getMyDroppedItems()) {
                WorldPoint worldPoint = trackedItem.getWorldPoint();
                if (worldPoint == null) continue;

                // Convert WorldPoint to LocalPoint
                LocalPoint localPoint = LocalPoint.fromWorld(client.getTopLevelWorldView(), worldPoint);
                if (localPoint == null) continue;

                // OPTIONAL: Draw a small dot in center using text location trick
                Point canvasPoint = Perspective.getCanvasTextLocation(client, graphics, localPoint, ".", 0);
                if (canvasPoint != null) {
                    Color potionColor = plugin.getColorForPartyMember(trackedItem.getOwnerName());
                    graphics.setColor(potionColor);
                    graphics.fillOval(canvasPoint.getX() - 3, canvasPoint.getY() - 3, 6, 6);
                }
            }
        }
        return null;
    }
}
