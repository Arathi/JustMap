package ru.bulldog.justmap.map;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import ru.bulldog.justmap.client.JustMapClient;
import ru.bulldog.justmap.client.MapScreen;
import ru.bulldog.justmap.client.config.ConfigFactory;
import ru.bulldog.justmap.client.render.MapTexture;
import ru.bulldog.justmap.map.data.Layer;
import ru.bulldog.justmap.map.data.MapCache;
import ru.bulldog.justmap.map.data.MapChunk;
import ru.bulldog.justmap.map.icon.WaypointIcon;
import ru.bulldog.justmap.map.waypoint.Waypoint;
import ru.bulldog.justmap.map.waypoint.WaypointKeeper;
import ru.bulldog.justmap.map.waypoint.WaypointsList;
import ru.bulldog.justmap.util.Colors;
import ru.bulldog.justmap.util.TaskManager;
import ru.bulldog.justmap.util.math.MathUtil;

public class Worldmap extends MapScreen implements IMap {

	private final static LiteralText TITLE = new LiteralText("Worldmap");
	
	private static Worldmap worldmap;
	
	public static Worldmap getScreen() {
		if (worldmap == null) {
			worldmap = new Worldmap();
		}
		
		return worldmap;
	}
	
	private int dimension;
	private int scaledWidth;
	private int scaledHeight;
	private double centerX;
	private double centerZ;
	private double startX;
	private double startZ;	
	private double endX;
	private double endZ;
	private double shiftW;
	private double shiftH;
	private float imageScale = 1.0F;
	private boolean playerTracking = true;
	private boolean changed = false;
	private int lastX = 0;
	private int lastZ = 0;
	
	private long updateInterval = 50;
	private long updated = 0;
	
	private BlockPos centerPos;
	private MapTexture mapImage;
	private MapTexture bufferImage;
	private String cursorCoords;
	private TaskManager processor;
	
	private List<WaypointIcon> waypoints = new ArrayList<>();
	
	private Worldmap() {
		super(TITLE);
	}

	@Override
	public void init() {		
		super.init();
		
		if (processor == null) {
			this.processor = TaskManager.getManager("worldmap");
		}
		
		this.paddingTop = 8;
		this.paddingBottom = 8;
		
		PlayerEntity player = minecraft.player;
		
		int currentDim = player.dimension.getRawId();
		if (centerPos == null || currentDim != dimension) {
			this.dimension = currentDim;
			this.centerPos = minecraft.player.getBlockPos();
		} else if (playerTracking) {
			this.centerPos = minecraft.player.getBlockPos();
		}
		
		this.cursorCoords = MathUtil.posToString(centerPos);
		
		this.addMapButtons();
		this.updateScale();
		
		waypoints.clear();
		List<Waypoint> wps = WaypointKeeper.getInstance().getWaypoints(dimension, true);
		if (wps != null) {
			Stream<Waypoint> stream = wps.stream().filter(wp -> MathUtil.getDistance(player.getBlockPos(), wp.pos) <= wp.showRange);
			for (Waypoint wp : stream.toArray(Waypoint[]::new)) {
				WaypointIcon waypoint = new WaypointIcon(this, wp);
				this.waypoints.add(waypoint);
			}
		}
	}
	
	private void addMapButtons() {
		children.add(new ButtonWidget(width - 24, 10, 20, 20, "x", (b) -> onClose()));		
		children.add(new ButtonWidget(width / 2 - 10, height - paddingBottom - 44, 20, 20, "\u2191", (b) -> moveMap(Direction.NORTH)));
		children.add(new ButtonWidget(width / 2 - 10, height - paddingBottom - 22, 20, 20, "\u2193", (b) -> moveMap(Direction.SOUTH)));
		children.add(new ButtonWidget(width / 2 - 32, height - paddingBottom - 32, 20, 20, "\u2190", (b) -> moveMap(Direction.WEST)));
		children.add(new ButtonWidget(width / 2 + 12, height - paddingBottom - 32, 20, 20, "\u2192", (b) -> moveMap(Direction.EAST)));		
		children.add(new ButtonWidget(width - 24, height / 2 - 21, 20, 20, "+", (b) -> changeScale(-0.25F)));
		children.add(new ButtonWidget(width - 24, height / 2 + 1, 20, 20, "-", (b) -> changeScale(+0.25F)));		
		children.add(new ButtonWidget(width - 24, height - paddingBottom - 22, 20, 20, "\u271C", (b) -> setCenterByPlayer()));
		children.add(new ButtonWidget(4, paddingTop + 2, 20, 20, "\u2630",(b) -> minecraft.openScreen(ConfigFactory.getConfigScreen(this))));
		children.add(new ButtonWidget(4, height - paddingBottom - 22, 20, 20, "\u2726",(b) -> minecraft.openScreen(new WaypointsList(this))));
	}
	
	@Override
	public void renderBackground() {
		fill(x, 0, x + width, height, 0xFF444444);
		
		this.drawMap();
		
		int iconSize = MathUtil.clamp((int) (10 / imageScale), 6, 10);
		for (WaypointIcon icon : waypoints) {
			if (!icon.isHidden()) {
				icon.setPosition(
					MathUtil.screenPos(icon.waypoint.pos.getX(), startX, endX, width) - shiftW,
					MathUtil.screenPos(icon.waypoint.pos.getZ(), startZ, endZ, height) - shiftH
				);
				icon.draw(iconSize);
			}
		}
		
		PlayerEntity player = minecraft.player;
		
		int playerX = player.getBlockPos().getX();
		int playerZ = player.getBlockPos().getZ();
		double arrowX = MathUtil.screenPos(playerX, startX, endX, width) - shiftW;
		double arrowY = MathUtil.screenPos(playerZ, startZ, endZ, height) - shiftH;
		
		DirectionArrow.draw(arrowX, arrowY, iconSize, player.headYaw);
	}
	
	@Override
	public void renderForeground() {
		this.drawBorders(paddingTop, paddingBottom);
		this.drawCenteredString(minecraft.textRenderer, cursorCoords, width / 2, paddingTop + 4, Colors.WHITE);
	}
	
	private void prepareTexture() {
		if (mapImage == null || mapImage.getWidth() != scaledWidth || mapImage.getHeight() != scaledHeight) {
			if (this.mapImage != null) {
				this.bufferImage.close();
				this.mapImage.close();
			}
			
			this.bufferImage = new MapTexture(scaledWidth, scaledHeight);
			this.mapImage = new MapTexture(scaledWidth, scaledHeight);
			this.bufferImage.fill(Colors.BLACK);
			this.mapImage.fill(Colors.BLACK);
			
			this.updateMapTexture();
		}
		
		if (this.changed) {
			this.mapImage.copyImage(this.bufferImage);
			this.changed = false;
		}
		this.mapImage.upload();
	}
	
	private void updateMapTexture() {
		this.calculateShift();
		
		int centerX = centerPos.getX() >> 4;
		int centerZ = centerPos.getZ() >> 4;
		int blockX = centerPos.getX() - (centerX << 4);
		int blockZ = centerPos.getZ() - (centerZ << 4);
		int chunksX = (int) Math.ceil((scaledWidth + blockX * 2) / 32F);
		int chunksZ = (int) Math.ceil((scaledHeight + blockZ * 2) / 32F);
		int startX = centerX - chunksX;
		int startZ = centerZ - chunksZ;
		int stopX = centerX + chunksX;
		int stopZ = centerZ + chunksZ;
		
		int tmpW = (stopX << 4) - (startX << 4);
		int tmpH = (stopZ << 4) - (startZ << 4);
		
		MapCache mapData = MapCache.get();
		
		int offX = (tmpW / 2 + blockX) - scaledWidth / 2;
		int offY = (tmpH / 2 + blockZ) - scaledHeight / 2;
		
		int picX = 0;
		for (int posX = startX; posX < stopX; posX++) {
			
			if (picX < offX - 16) {
				picX += 16;
				continue;
			}
			
			int picY = 0;
			for (int posZ = startZ; posZ < stopZ; posZ++) {
				
				if (picY < offY - 16) {
					picY += 16;
					continue;
				}
				
				MapChunk mapChunk;
				if (dimension != -1) {
					mapChunk = mapData.getChunk(Layer.Type.SURFACE, 0, posX, posZ);
				} else {
					mapChunk = mapData.getCurrentChunk(posX, posZ);
				}
				
				this.bufferImage.writeChunkData(picX - offX, picY - offY, mapChunk.getColorData());
				picY += 16;
			}			
			picX += 16;
		}
		
		this.changed = true;
	}
	
	private void drawMap() {
		this.prepareTexture();
		
		RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder builder = tessellator.getBuffer();
		
		builder.begin(7, VertexFormats.POSITION_TEXTURE);			
		builder.vertex(0, 0, 0).texture(0F, 0F).next();
		builder.vertex(0, height, 0).texture(0F, 1F).next();
		builder.vertex(width, height, 0).texture(1F, 1F).next();
		builder.vertex(width, 0, 0).texture(1F, 0F).next();
		
		tessellator.draw();
	}
	
	private void calculateShift() {
		this.centerX = (centerPos.getX() >> 4) << 4;
		this.centerZ = (centerPos.getZ() >> 4) << 4;
		this.startX = centerX - scaledWidth / 2;
		this.startZ = centerZ - scaledHeight / 2;
		this.endX = startX + scaledWidth;
		this.endZ = startZ + scaledHeight;
		
		double screenCX = MathUtil.screenPos(centerPos.getX(), startX, endX, width);
		double screenCY = MathUtil.screenPos(centerPos.getZ(), startZ, endZ, height);
		
		this.shiftW = screenCX - width / 2F;
		this.shiftH = screenCY - height / 2F;
	}
	
	public void setCenterByPlayer() {
		this.playerTracking = true;
		this.centerPos = minecraft.player.getBlockPos();
  		
		processor.execute(this::updateMapTexture);
	}
	
	private void updateScale() {
		this.scaledWidth = (int) Math.ceil(width * imageScale);
		this.scaledHeight = (int) Math.ceil(height * imageScale);
		
		if (scaledWidth > 2580) {
			this.imageScale = 2580F / width;
			this.updateScale();
			
			return;
		}
		
		this.updateInterval = (long) (imageScale > 1 ? 10 * imageScale : 10);
	}
	
	private void changeScale(float value) {
		this.imageScale = MathUtil.clamp(this.imageScale + value, 0.5F, 3F);
		this.updateScale();
	}
	
	private void moveMap(Direction direction) {
		long time = System.currentTimeMillis();
		
		if (time - updated < updateInterval) return;
		
		switch (direction) {
			case NORTH:
				this.centerPos = centerPos.add(0, 0, -16);
				break;
			case SOUTH:
				this.centerPos = centerPos.add(0, 0, 16);
				break;
			case EAST:
				this.centerPos = centerPos.add(16, 0, 0);
				break;
			case WEST:
				this.centerPos = centerPos.add(-16, 0, 0);
				break;
			default: break;
		}
		
		processor.execute(this::updateMapTexture);
		
		this.playerTracking = false;
		this.updated = time;
	}
	
	@Override
	public boolean keyPressed(int i, int j, int k) {
		switch(i) {
			case GLFW.GLFW_KEY_W:
			case GLFW.GLFW_KEY_UP:
				moveMap(Direction.NORTH);
		  		return true;
		  	case GLFW.GLFW_KEY_S:
		  	case GLFW.GLFW_KEY_DOWN:
		  		moveMap(Direction.SOUTH);
		  		return true;
		  	case GLFW.GLFW_KEY_A:
		  	case GLFW.GLFW_KEY_LEFT:
		  		moveMap(Direction.WEST);
		  		return true;
		  	case GLFW.GLFW_KEY_D:
		  	case GLFW.GLFW_KEY_RIGHT:
		  		moveMap(Direction.EAST);
		  		return true;
		  	case GLFW.GLFW_KEY_MINUS:
		  	case GLFW.GLFW_KEY_KP_SUBTRACT:
		  		changeScale(0.25F);
		  		return true;
		  	case GLFW.GLFW_KEY_EQUAL:
		  	case GLFW.GLFW_KEY_KP_ADD:
		  		changeScale(-0.25F);
		  		return true;
		  	case GLFW.GLFW_KEY_X:
		  		setCenterByPlayer();
		  		return true;
		  	case GLFW.GLFW_KEY_M:
		  		this.onClose();
		  		return true;
		  	default:
		  		return super.keyPressed(i, j, k);
		}
	}
	
	@Override
	public boolean mouseDragged(double d, double e, int i, double f, double g) {
		if (super.mouseDragged(d, e, i, f, g)) return true;
		
		if (i == 0) {
			long time = System.currentTimeMillis();
			if (time - updated < updateInterval) return true;
			
			int x = centerPos.getX();
			int y = centerPos.getY();
			int z = centerPos.getZ();
			
			x -= Math.round(2 * f * imageScale);
			z -= Math.round(2 * g * imageScale);
			
			this.centerPos = new BlockPos(x, y, z);
			
			processor.execute(this::updateMapTexture);
			
			this.playerTracking = false;
			this.updated = time;
		
			return true;
		}
		
		return false;
	}
	
	private int pixelToPos(double x, int cx, double range, double scaledRange) {
		double x1 = cx - scaledRange / 2;
		double x2 = x1 + scaledRange;
		
		return MathUtil.worldPos(x, x1, x2, range);
	}
	
	private BlockPos cursorBlockPos(double x, double y) {
		
		int posX = pixelToPos(x, centerPos.getX(), width, scaledWidth);
		int posZ = pixelToPos(y, centerPos.getZ(), height, scaledHeight);
		
		int chunkX = posX >> 4;
		int chunkZ = posZ >> 4;
		
		MapChunk mapChunk;
		if (dimension != -1) {
			mapChunk = MapCache.get().getChunk(Layer.Type.SURFACE, 0, chunkX, chunkZ);
		} else {
			mapChunk = MapCache.get().getCurrentChunk(chunkX, chunkZ);
		}
		
		int cx = posX - (chunkX << 4);
		int cz = posZ - (chunkZ << 4);
		
		int posY = mapChunk.getHeighmap()[cx + (cz << 4)];
		posY = posY == -1 ? centerPos.getY() : posY;
		
		return new BlockPos(posX, posY, posZ);
	}
	
	@Override
	public void mouseMoved(double d, double e) {		
		this.cursorCoords = MathUtil.posToString(cursorBlockPos(d, e));
	}
	
	private int clicks = 0;
	private long clicked = 0;
	
	@Override
	public boolean mouseReleased(double d, double e, int i) {
		if (super.mouseReleased(d, e, i)) return true; 
		
		if (i == 0) {
			long time = System.currentTimeMillis();
			if (time - clicked > 500) clicks = 0;
			
			if (++clicks == 2) {			
				JustMapClient.MAP.createWaypoint(dimension, cursorBlockPos(d, e));
				
				clicked = 0;
				clicks = 0;
			} else {
				clicked = time;
			}
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public void onClose() {
		this.processor.stop();
		this.processor = null;
		
		super.onClose();
	}
	
	@Override
	public boolean mouseScrolled(double d, double e, double f) {
		changeScale(f > 0 ? -0.25F : 0.25F);
		return true;
	}

	@Override
	public int getWidth() {
		return this.width;
	}

	@Override
	public int getHeight() {
		return this.height;
	}

	@Override
	public int getScaledWidth() {
		return this.scaledWidth;
	}

	@Override
	public int getScaledHeight() {
		return this.scaledHeight;
	}

	@Override
	public float getScale() {
		return this.getScale();
	}
}
