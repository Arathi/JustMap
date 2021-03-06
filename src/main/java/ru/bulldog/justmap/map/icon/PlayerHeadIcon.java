package ru.bulldog.justmap.map.icon;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.PlayerSkinTexture;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import ru.bulldog.justmap.JustMap;
import ru.bulldog.justmap.client.config.ClientParams;
import ru.bulldog.justmap.map.minimap.MapPlayer;
import ru.bulldog.justmap.util.Colors;
import ru.bulldog.justmap.util.DrawHelper;

import java.io.IOException;

public class PlayerHeadIcon {
	
	public long lastCheck;
	public int delay = 5000;
	public boolean success = false;
	
	private ResourceTexture playerSkin;
	
	public void draw(MatrixStack matrix, double x, double y) {
		int size = ClientParams.entityIconSize;
		this.draw(matrix, x, y, size, ClientParams.showIconsOutline);
	}
	
	public void draw(double x, double y, int size, boolean outline) {
		MatrixStack matrix = new MatrixStack();
		this.draw(matrix, x, y, size, outline);
	}

	public void draw(MatrixStack matrix, double x, double y, int size, boolean outline) {		
		double drawX = x - size / 2;
		double drawY = y - size / 2;
		y -= size / 2;
		if (outline) {
			double thickness = ClientParams.entityOutlineSize;
			DrawHelper.fill(drawX - thickness / 2, drawY - thickness / 2, size + thickness, size + thickness, Colors.LIGHT_GRAY);
		}
		this.playerSkin.bindTexture();	
		DrawHelper.drawPlayerHead(matrix, drawX, drawY, size, size);
	}
	
	public void updatePlayerSkin(MapPlayer player) {
		JustMap.WORKER.execute(() -> this.getPlayerSkin(player));
	}
	
	public void getPlayerSkin(MapPlayer player) {
		this.lastCheck = System.currentTimeMillis();
		
		Identifier defaultSkin = DefaultSkinHelper.getTexture(player.getUuid());
		if (!player.getSkinTexture().equals(defaultSkin)) {
			PlayerSkinTexture skinTexture = ClientPlayerEntity.loadSkin(player.getSkinTexture(), player.getName().getString());
			if (skinTexture != this.playerSkin) {
				if (this.playerSkin != null) {
					this.playerSkin.clearGlId();
				}
				this.playerSkin = skinTexture;

				try {
					this.playerSkin.load(MinecraftClient.getInstance().getResourceManager());
				} catch (IOException ex) {
					JustMap.LOGGER.logWarning(ex.getLocalizedMessage());
				}
				this.success = true;
			}
		} else if (this.playerSkin == null) {
			this.playerSkin = new ResourceTexture(defaultSkin);
			this.success = false;
			
			try {
				this.playerSkin.load(MinecraftClient.getInstance().getResourceManager());
			} catch (IOException ex) {
				JustMap.LOGGER.logWarning(ex.getLocalizedMessage());
			}
		}
	}
}
