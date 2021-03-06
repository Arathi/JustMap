package ru.bulldog.justmap.client.render;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.texture.TextureUtil;

import ru.bulldog.justmap.JustMap;
import ru.bulldog.justmap.util.ColorUtil;
import ru.bulldog.justmap.util.Colors;

public class MapTexture {

	private ByteBuffer buffer;
	private byte[] bytes;
	private int glId = -1;
	private final int width;
	private final int height;
	
	public boolean changed = false;
	
	private Object bufferLock = new Object();
	
	public MapTexture(int width, int height) {
		int size = 4 * width * (height - 1) + 4 * width;		
		this.bytes = new byte[size];
		this.buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
		this.width = width;
		this.height = height;
	}
	
	public int getId() {
		return this.glId;
	}
	
	public void upload() {
		if (this.glId == -1) {
			this.glId = TextureUtil.generateId();
		}
		
		this.refillBuffer();
		
		RenderSystem.bindTexture(this.glId);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
		RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_ZERO);
		RenderSystem.pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_ZERO);
		RenderSystem.pixelStore(GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_ZERO);
		
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, GL11.GL_ZERO, GL11.GL_RGBA, this.getWidth(), this.getHeight(), GL11.GL_ZERO, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8, this.buffer);
	
		this.changed = false;
	}
	
	public int getHeight() {
		return this.height;
	}

	public int getWidth() {
		return this.width;
	}

	private byte[] getBytes() {
		synchronized(bufferLock) {
			return this.bytes.clone();
		}
	}
	
	public void copyData(MapTexture image) {
		synchronized(bufferLock) {
			this.bytes = image.getBytes();
		}
	}
	
	public void writeChunkData(int x, int y, int[] colorData) {
		for (int i = 0; i < 16; i++) {
			int px = i + x;
			
			if (px >= this.getWidth()) break;
			if (px < 0) continue;
			
			for (int j = 0; j < 16; j++) {
				int py = j + y;
				
				if (py >= this.getHeight()) break;
				if (py < 0) continue;
				
				int color = colorData[i + (j << 4)];
				this.setColor(px, py, color);
			}
		}
	}
	
	public void setColor(int x, int y, int color) {
		if (x < 0 || x >= this.getWidth()) return;
		if (y < 0 || y >= this.getHeight()) return;
		
		int index = (x + y * this.getWidth()) * 4;		
		synchronized(bufferLock) {
			this.bytes[index] = (byte) (color >> 24);
			this.bytes[index + 1] = (byte) (color >> 0);
			this.bytes[index + 2] = (byte) (color >> 8);
			this.bytes[index + 3] = (byte) (color >> 16);
		}
		
		this.changed = true;
	}
	
	public int getColor(int x, int y) {
		if (x < 0 || x >= this.getWidth()) return -1;
		if (y < 0 || y >= this.getHeight()) return -1;
		
		int index = (x + y * this.getWidth()) * 4;
		
		synchronized(bufferLock) {
			int a = this.bytes[index];
			int b = this.bytes[index + 1];
			int g = this.bytes[index + 2];
			int r = this.bytes[index + 3];
			
			return (a << 24) | (b << 16) | (g << 8) | (r << 0);
		}
	}
	
	public void applyTint(int x, int y, int tint) {
		if (x < 0 || x >= this.getWidth()) return;
		if (y < 0 || y >= this.getHeight()) return;
		
		int color = this.getColor(x, y);
		this.setColor(x, y, ColorUtil.applyTint(color, tint));
	}
	
	public void fill(int color) {
		int width = this.getWidth();
		int height = this.getHeight();
		
		this.fill(0, 0, width, height, color);
	}
	
	public void fill(int x, int y, int w, int h, int color) {
		if (x < 0 || y < 0) return;
		
		int width = this.getWidth();
		int height = this.getHeight();
		
		if (x + w > width) width -= x;
		else width = w;
		if (y + h > height) height -= y;
		else height = h;
		
		if (width <= 0 || height <= 0) return;
		
		synchronized(bufferLock) {
			for(int i = x; i < x + width; i++) {
				for (int j = y; j < y + height; j++) {
					this.setColor(i, j, color);
				}
			}
		}
	}
	
	public void saveImage(File png) {
		try (OutputStream fileOut = new FileOutputStream(png)) {
			BufferedImage pngImage = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
			byte[] data = ((DataBufferByte) pngImage.getTile(0, 0).getDataBuffer()).getData();
			byte[] bytes = this.getBytes();
			for (int i = 0; i < bytes.length; i++) {
				data[i] = bytes[i];
			}
			ImageIO.write(pngImage, "png", fileOut);
			pngImage.flush();
		} catch (Exception ex) {
			JustMap.LOGGER.logWarning("Can't save image: " + png.toString());
			JustMap.LOGGER.logWarning(ex.getLocalizedMessage());
		}
	}
	
	public void loadImage(File png) {
		if (!png.exists()) return;
		try (InputStream fileInput = new FileInputStream(png)) {
			BufferedImage pngImage = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
			pngImage.setData(ImageIO.read(fileInput).getData());
			this.bytes = ((DataBufferByte) pngImage.getTile(0, 0).getDataBuffer()).getData().clone();
			this.changed = true;
			pngImage.flush();
		} catch (Exception ex) {
			JustMap.LOGGER.logWarning("Can't load image: " + png.toString());
			JustMap.LOGGER.logWarning(ex.getLocalizedMessage());
		}
	}
	
	public void clear() {
		this.fill(Colors.BLACK);
		this.upload();
	}
	
	public void close() {
		this.clearId();		
		synchronized(bufferLock) {
			this.buffer.clear();
		}
	}
	
	private void clearId() {
		if (!RenderSystem.isOnRenderThread()) {
			RenderSystem.recordRenderCall(() -> {
				if (this.glId != -1) {
					TextureUtil.deleteId(this.glId);
					this.glId = -1;
				}
			});
		} else if (this.glId != -1) {
			TextureUtil.deleteId(this.glId);
			this.glId = -1;
		}
	}
	
	private void refillBuffer() {
		synchronized(bufferLock) {
			this.buffer.clear();
			this.buffer.put(this.bytes);
			this.buffer.position(0).limit(bytes.length);
		}
	}
}
