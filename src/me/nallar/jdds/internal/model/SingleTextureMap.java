/**
 *
 */
package me.nallar.jdds.internal.model;

import gr.zdimensions.jsquish.Squish.CompressionType;
import me.nallar.jdds.internal.ddsutil.ByteBufferedImage;

import java.awt.image.*;
import java.nio.*;


/**
 * TextureMap without MipMaps
 *
 * @author danielsenff
 */
public class SingleTextureMap extends AbstractTextureMap {

	final BufferedImage bi;

	/**
	 * @param bi
	 */
	public SingleTextureMap(final BufferedImage bi) {
		super();
		this.bi = bi;
	}

	/* (non-Javadoc)
	 * @see DDSUtil.AbstractMipMaps#getDXTCompressedBuffer(gr.zdimensions.jsquish.Squish.CompressionType)
	 */
	@Override
	public ByteBuffer[] getDXTCompressedBuffer(final CompressionType compressionType) {
		ByteBuffer[] buffer = new ByteBuffer[1];
		buffer[0] = super.compress(bi, compressionType);
		return buffer;
	}


	/* (non-Javadoc)
	 * @see DDSUtil.AbstractMipMaps#getHeight()
	 */
	@Override
	public int getHeight() {
		return this.bi.getHeight();
	}

	/* (non-Javadoc)
	 * @see DDSUtil.AbstractMipMaps#getWidth()
	 */
	@Override
	public int getWidth() {
		return this.bi.getWidth();
	}

	/* (non-Javadoc)
	 * @see DDSUtil.AbstractTextureMap#getUncompressedBuffer()
	 */
	@Override
	public ByteBuffer[] getUncompressedBuffer() {
		ByteBuffer[] mipmapBuffer = new ByteBuffer[1];
		mipmapBuffer[0] = ByteBuffer.wrap(ByteBufferedImage.convertBIintoARGBArray(this.bi));
		return mipmapBuffer;
	}

}
