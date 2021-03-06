/*
 * Copyright (c) 2005 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 * You acknowledge that this software is not designed or intended for use
 * in the design, construction, operation or maintenance of any nuclear
 * facility.
 * 
 * Sun gratefully acknowledges that this software was originally authored
 * and developed by Kenneth Bradley Russell and Christopher John Kline.
 */

package me.nallar.jdds.internal.jogl;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;


/**
 * A reader and writer for DirectDraw Surface (.dds) files, which are
 * used to describe textures. These files can contain multiple mipmap
 * levels in one file. This class is currently minimal and does not
 * support all of the possible file formats.
 */

public class DDSImage {
	/**
	 * Simple class describing images and data; does not encapsulate
	 * image format information. User is responsible for transmitting
	 * that information in another way.
	 */
	public static class ImageInfo {
		private final ByteBuffer data;
		private final int width;
		private final int height;
		private final boolean isCompressed;
		private final int compressionFormat;

		public ImageInfo(ByteBuffer data, int width, int height, boolean compressed, int compressionFormat) {
			this.data = data;
			this.width = width;
			this.height = height;
			this.isCompressed = compressed;
			this.compressionFormat = compressionFormat;
		}

		public ByteBuffer getData() {
			return data;
		}

	}

	private FileInputStream fis;
	private FileChannel chan;
	private ByteBuffer buf;
	private Header header;

	//
	// Selected bits in header flags
	//

	public static final int DDSD_CAPS = 0x00000001; // Capacities are valid
	public static final int DDSD_HEIGHT = 0x00000002; // Height is valid
	public static final int DDSD_WIDTH = 0x00000004; // Width is valid
	public static final int DDSD_PITCH = 0x00000008; // Pitch is valid
	public static final int DDSD_PIXELFORMAT = 0x00001000; // ddpfPixelFormat is valid
	public static final int DDSD_MIPMAPCOUNT = 0x00020000; // Mip map count is valid
	public static final int DDSD_LINEARSIZE = 0x00080000; // dwLinearSize is valid

	public static final int DDPF_ALPHAPIXELS = 0x00000001; // Alpha channel is present
	public static final int DDPF_FOURCC = 0x00000004; // FourCC code is valid
	public static final int DDPF_RGB = 0x00000040; // RGB data is present

	// Selected bits in DDS capabilities flags
	public static final int DDSCAPS_COMPLEX = 0x00000008; // Complex surface structure, such as a cube map

	// Selected bits in DDS extended capabilities flags
	public static final int DDSCAPS2_CUBEMAP = 0x00000200;
	public static final int DDSCAPS2_CUBEMAP_POSITIVEX = 0x00000400;
	public static final int DDSCAPS2_CUBEMAP_NEGATIVEX = 0x00000800;
	public static final int DDSCAPS2_CUBEMAP_POSITIVEY = 0x00001000;
	public static final int DDSCAPS2_CUBEMAP_NEGATIVEY = 0x00002000;
	public static final int DDSCAPS2_CUBEMAP_POSITIVEZ = 0x00004000;
	public static final int DDSCAPS2_CUBEMAP_NEGATIVEZ = 0x00008000;
	public static final int DDSCAPS2_VOLUME = 0x00200000;

	// Known pixel formats
	public static final int D3DFMT_UNKNOWN = 0;
	public static final int D3DFMT_R8G8B8 = 20;
	public static final int D3DFMT_A8R8G8B8 = 21;
	public static final int D3DFMT_X8R8G8B8 = 22;
	public static final int D3DFMT_A1R5G5B5 = 25;

	// The following are also valid FourCC codes
	public static final int D3DFMT_DXT1 = 0x31545844;
	public static final int D3DFMT_DXT2 = 0x32545844;
	public static final int D3DFMT_DXT3 = 0x33545844;
	public static final int D3DFMT_DXT4 = 0x34545844;
	public static final int D3DFMT_DXT5 = 0x35545844;

	/**
	 * Reads a DirectDraw surface from the specified file, returning
	 * the resulting DDSImage.
	 *
	 * @param file File object
	 * @return DDS image object
	 * @throws java.io.IOException if an I/O exception occurred
	 */
	public static DDSImage read(File file) throws IOException {
		DDSImage image = new DDSImage();
		image.readFromFile(file);
		return image;
	}

	/**
	 * Reads a DirectDraw surface from the specified ByteBuffer, returning
	 * the resulting DDSImage.
	 *
	 * @param buf Input data
	 * @return DDS image object
	 * @throws java.io.IOException if an I/O exception occurred
	 */
	public static DDSImage read(ByteBuffer buf) throws IOException {
		DDSImage image = new DDSImage();
		image.readFromBuffer(buf);
		return image;
	}

	/**
	 * Closes open files and resources associated with the open
	 * DDSImage. No other methods may be called on this object once
	 * this is called.
	 */
	public void close() {
		try {
			if (chan != null) {
				chan.close();
				chan = null;
			}
			if (fis != null) {
				fis.close();
				fis = null;
			}
			buf = null;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates a new DDSImage from data supplied by the user. The
	 * resulting DDSImage can be written to disk using the write()
	 * method.
	 *
	 * @param d3dFormat  the D3DFMT_ constant describing the data; it is
	 *                   assumed that it is packed tightly
	 * @param width      the width in pixels of the topmost mipmap image
	 * @param height     the height in pixels of the topmost mipmap image
	 * @param mipmapData the data for each mipmap level of the resulting
	 *                   DDSImage; either only one mipmap level should
	 *                   be specified, or they all must be
	 * @return DDS image object
	 * @throws IllegalArgumentException if the data does not match the
	 *                                  specified arguments
	 */
	public static DDSImage createFromData(int d3dFormat,
										  int width,
										  int height,
										  ByteBuffer[] mipmapData) throws IllegalArgumentException {
		DDSImage image = new DDSImage();
		image.initFromData(d3dFormat, width, height, mipmapData);
		return image;
	}

	public void write(FileOutputStream fos) throws IOException {
		FileChannel chan = fos.getChannel();
		// Create ByteBuffer for header in case the start of our
		// ByteBuffer isn't actually memory-mapped
		ByteBuffer hdr = ByteBuffer.allocate(Header.writtenSize());
		hdr.order(ByteOrder.LITTLE_ENDIAN);
		header.write(hdr);
		hdr.rewind();
		chan.write(hdr);
		buf.position(Header.writtenSize());
		chan.write(buf);
		chan.force(true);
		chan.close();
	}

	/**
	 * Writes this DDSImage to the specified file name.
	 *
	 * @param file File object to write to
	 * @throws java.io.IOException if an I/O exception occurred
	 */
	public void write(File file) throws IOException {
		FileOutputStream stream = new FileOutputStream(file);
		write(stream);
		stream.close();
	}

	/**
	 * Test for presence/absence of surface description flags (DDSD_*)
	 *
	 * @param flag DDSD_* flags set to test
	 * @return true if flag present or false otherwise
	 */
	public boolean isSurfaceDescFlagSet(int flag) {
		return ((header.flags & flag) != 0);
	}

	/**
	 * Test for presence/absence of pixel format flags (DDPF_*)
	 */
	public boolean isPixelFormatFlagSet(int flag) {
		return ((header.pfFlags & flag) != 0);
	}

	/**
	 * Gets the pixel format of this texture (D3DFMT_*) based on some
	 * heuristics. Returns D3DFMT_UNKNOWN if could not recognize the
	 * pixel format.
	 *
	 * @return
	 */
	public int getPixelFormat() {
		if (isCompressed()) {
			return getCompressionFormat();
		} else if (isPixelFormatFlagSet(DDPF_RGB)) {
			if (isPixelFormatFlagSet(DDPF_ALPHAPIXELS)) {
				if (getDepth() == 32 &&
						header.pfRBitMask == 0x00FF0000 &&
						header.pfGBitMask == 0x0000FF00 &&
						header.pfBBitMask == 0x000000FF &&
						header.pfABitMask == 0xFF000000) {
					return D3DFMT_A8R8G8B8;
				} else if (getDepth() == 16 &&
						header.pfRBitMask == 0x7c00 &&
						header.pfGBitMask == 0x3e0 &&
						header.pfBBitMask == 0x1f &&
						header.pfABitMask == 0x8000) {
					return D3DFMT_A1R5G5B5;
				}
			} else {
				if (getDepth() == 24 &&
						header.pfRBitMask == 0x00FF0000 &&
						header.pfGBitMask == 0x0000FF00 &&
						header.pfBBitMask == 0x000000FF) {
					return D3DFMT_R8G8B8;
				} else if (getDepth() == 32 &&
						header.pfRBitMask == 0x00FF0000 &&
						header.pfGBitMask == 0x0000FF00 &&
						header.pfBBitMask == 0x000000FF) {
					return D3DFMT_X8R8G8B8;
				}
			}
		}

		return D3DFMT_UNKNOWN;
	}

	/**
	 * Indicates whether this texture is cubemap
	 *
	 * @return true if cubemap or false otherwise
	 */
	public boolean isCubemap() {
		return ((header.ddsCaps1 & DDSCAPS_COMPLEX) != 0) && ((header.ddsCaps2 & DDSCAPS2_CUBEMAP) != 0);
	}

	/**
	 * Indicates whether this texture is volume texture
	 *
	 * @return true if cubemap or false otherwise
	 */
	public boolean isVolume() {
		return ((header.ddsCaps1 & DDSCAPS_COMPLEX) != 0) && ((header.ddsCaps2 & DDSCAPS2_VOLUME) != 0);
	}

	/**
	 * Indicates whether this cubemap side present
	 *
	 * @param side Side to test
	 * @return true if side present or false otherwise
	 */
	public boolean isCubemapSidePresent(int side) {
		return isCubemap() && (header.ddsCaps2 & side) != 0;
	}

	/**
	 * Indicates whether this texture is compressed.
	 */
	public boolean isCompressed() {
		return (isPixelFormatFlagSet(DDPF_FOURCC));
	}

	/**
	 * If this surface is compressed, returns the kind of me.nallar.jdds.internal.compression
	 * used (DXT1..DXT5).
	 */
	public int getCompressionFormat() {
		return header.pfFourCC;
	}

	/**
	 * Width of the texture (or the top-most mipmap if mipmaps are
	 * present)
	 */
	public int getWidth() {
		return header.width;
	}

	/**
	 * Height of the texture (or the top-most mipmap if mipmaps are
	 * present)
	 */
	public int getHeight() {
		return header.height;
	}

	/**
	 * Total number of bits per pixel. Only valid if DDPF_RGB is
	 * present. For A8R8G8B8, would be 32.
	 */
	public int getDepth() {
		return header.pfRGBBitCount;
	}

	/**
	 * Number of mip maps in the texture
	 */
	public int getNumMipMaps() {
		if (!isSurfaceDescFlagSet(DDSD_MIPMAPCOUNT)) {
			return 0;
		}
		return header.mipMapCountOrAux;
	}

	/**
	 * Gets the <i>i</i>th mipmap data (0..getNumMipMaps() - 1)
	 *
	 * @param map Mipmap index
	 * @return Image object
	 */
	public ImageInfo getMipMap(int map) {
		return getMipMap(0, map);
	}

	/**
	 * Gets the <i>i</i>th mipmap data (0..getNumMipMaps() - 1)
	 *
	 * @param side Cubemap side or 0 for 2D texture
	 * @param map  Mipmap index
	 * @return Image object
	 */
	public ImageInfo getMipMap(int side, int map) {
		if (!isCubemap() && (side != 0)) {
			throw new RuntimeException("Illegal side for 2D texture: " + side);
		}
		if (isCubemap() && !isCubemapSidePresent(side)) {
			throw new RuntimeException("Illegal side, side not present: " + side);
		}
		if (getNumMipMaps() > 0 &&
				((map < 0) || (map >= getNumMipMaps()))) {
			throw new RuntimeException("Illegal mipmap number " + map + " (0.." + (getNumMipMaps() - 1) + ")");
		}

		// Figure out how far to seek
		int seek = Header.writtenSize();
		if (isCubemap()) {
			seek += sideShiftInBytes(side);
		}
		for (int i = 0; i < map; i++) {
			seek += mipMapSizeInBytes(i);
		}
		buf.limit(seek + mipMapSizeInBytes(map));
		buf.position(seek);
		ByteBuffer next = buf.slice();
		buf.position(0);
		buf.limit(buf.capacity());
		return new ImageInfo(next, mipMapWidth(map), mipMapHeight(map), isCompressed(), getCompressionFormat());
	}

	//----------------------------------------------------------------------
	// Internals only below this point
	//

	private static final int MAGIC = 0x20534444;

	static class Header {
		int size;                 // size of the DDSURFACEDESC structure
		int flags;                // determines what fields are valid
		int height;               // height of surface to be created
		int width;                // width of input surface
		int pitchOrLinearSize;
		int backBufferCountOrDepth;
		int mipMapCountOrAux;     // number of mip-map levels requested (in this context)
		int alphaBitDepth;        // depth of alpha buffer requested
		int reserved1;            // reserved
		int surface;              // pointer to the associated surface memory
		// NOTE: following two entries are from DDCOLORKEY data structure
		// Are overlaid with color for empty cubemap faces (unused in this reader)
		int colorSpaceLowValue;
		int colorSpaceHighValue;
		int destBltColorSpaceLowValue;
		int destBltColorSpaceHighValue;
		int srcOverlayColorSpaceLowValue;
		int srcOverlayColorSpaceHighValue;
		int srcBltColorSpaceLowValue;
		int srcBltColorSpaceHighValue;
		// NOTE: following entries are from DDPIXELFORMAT data structure
		// Are overlaid with flexible vertex format description of vertex
		// buffers (unused in this reader)
		int pfSize;                 // size of DDPIXELFORMAT structure
		int pfFlags;                // pixel format flags
		int pfFourCC;               // (FOURCC code)
		// Following five entries have multiple interpretations, not just
		// RGBA (but that's all we support right now)
		int pfRGBBitCount;          // how many bits per pixel
		int pfRBitMask;             // mask for red bits
		int pfGBitMask;             // mask for green bits
		int pfBBitMask;             // mask for blue bits
		int pfABitMask;             // mask for alpha channel
		int ddsCaps1;               // Texture and mip-map flags
		int ddsCaps2;               // Advanced capabilities including cubemap support
		int ddsCapsReserved1;
		int ddsCapsReserved2;
		int textureStage;           // stage in multitexture cascade

		void read(ByteBuffer buf) throws IOException {
			int magic = buf.getInt();
			if (magic != MAGIC) {
				throw new IOException("Incorrect magic number 0x" +
						Integer.toHexString(magic) +
						" (expected " + MAGIC + ")");
			}

			size = buf.getInt();
			flags = buf.getInt();
			height = buf.getInt();
			width = buf.getInt();
			pitchOrLinearSize = buf.getInt();
			backBufferCountOrDepth = buf.getInt();
			mipMapCountOrAux = buf.getInt();
			alphaBitDepth = buf.getInt();
			reserved1 = buf.getInt();
			surface = buf.getInt();
			colorSpaceLowValue = buf.getInt();
			colorSpaceHighValue = buf.getInt();
			destBltColorSpaceLowValue = buf.getInt();
			destBltColorSpaceHighValue = buf.getInt();
			srcOverlayColorSpaceLowValue = buf.getInt();
			srcOverlayColorSpaceHighValue = buf.getInt();
			srcBltColorSpaceLowValue = buf.getInt();
			srcBltColorSpaceHighValue = buf.getInt();
			pfSize = buf.getInt();
			pfFlags = buf.getInt();
			pfFourCC = buf.getInt();
			pfRGBBitCount = buf.getInt();
			pfRBitMask = buf.getInt();
			pfGBitMask = buf.getInt();
			pfBBitMask = buf.getInt();
			pfABitMask = buf.getInt();
			ddsCaps1 = buf.getInt();
			ddsCaps2 = buf.getInt();
			ddsCapsReserved1 = buf.getInt();
			ddsCapsReserved2 = buf.getInt();
			textureStage = buf.getInt();
		}

		// buf must be in little-endian byte order
		void write(ByteBuffer buf) {
			buf.putInt(MAGIC);
			buf.putInt(size);
			buf.putInt(flags);
			buf.putInt(height);
			buf.putInt(width);
			buf.putInt(pitchOrLinearSize);
			buf.putInt(backBufferCountOrDepth);
			buf.putInt(mipMapCountOrAux);
			buf.putInt(alphaBitDepth);
			buf.putInt(reserved1);
			buf.putInt(surface);
			buf.putInt(colorSpaceLowValue);
			buf.putInt(colorSpaceHighValue);
			buf.putInt(destBltColorSpaceLowValue);
			buf.putInt(destBltColorSpaceHighValue);
			buf.putInt(srcOverlayColorSpaceLowValue);
			buf.putInt(srcOverlayColorSpaceHighValue);
			buf.putInt(srcBltColorSpaceLowValue);
			buf.putInt(srcBltColorSpaceHighValue);
			buf.putInt(pfSize);
			buf.putInt(pfFlags);
			buf.putInt(pfFourCC);
			buf.putInt(pfRGBBitCount);
			buf.putInt(pfRBitMask);
			buf.putInt(pfGBitMask);
			buf.putInt(pfBBitMask);
			buf.putInt(pfABitMask);
			buf.putInt(ddsCaps1);
			buf.putInt(ddsCaps2);
			buf.putInt(ddsCapsReserved1);
			buf.putInt(ddsCapsReserved2);
			buf.putInt(textureStage);
		}

		private static int size() {
			return 124;
		}

		private static int pfSize() {
			return 32;
		}

		private static int writtenSize() {
			return 128;
		}
	}

	private DDSImage() {
	}

	private void readFromFile(File file) throws IOException {
		fis = new FileInputStream(file);
		chan = fis.getChannel();
		ByteBuffer buf = chan.map(FileChannel.MapMode.READ_ONLY,
				0, (int) file.length());
		readFromBuffer(buf);
	}

	private void readFromBuffer(ByteBuffer buf) throws IOException {
		this.buf = buf;
		buf.order(ByteOrder.LITTLE_ENDIAN);
		header = new Header();
		header.read(buf);
		fixupHeader();
	}

	private void initFromData(int d3dFormat,
							  int width,
							  int height,
							  ByteBuffer[] mipmapData) throws IllegalArgumentException {
		// Check size of mipmap data compared against format, width and
		// height
		int topmostMipmapSize = width * height;
		int pitchOrLinearSize = width;
		boolean isCompressed = false;
		switch (d3dFormat) {
			case D3DFMT_R8G8B8:
				topmostMipmapSize *= 3;
				pitchOrLinearSize *= 3;
				break;
			case D3DFMT_A8R8G8B8:
				topmostMipmapSize *= 4;
				pitchOrLinearSize *= 4;
				break;
			case D3DFMT_X8R8G8B8:
				topmostMipmapSize *= 4;
				pitchOrLinearSize *= 4;
				break;
			case D3DFMT_DXT1:
			case D3DFMT_DXT2:
			case D3DFMT_DXT3:
			case D3DFMT_DXT4:
			case D3DFMT_DXT5:
				topmostMipmapSize = computeCompressedBlockSize(width, height, 1, d3dFormat);
				pitchOrLinearSize = topmostMipmapSize;
				isCompressed = true;
				break;
			default:
				throw new IllegalArgumentException("d3dFormat must be one of the known formats");
		}

		// Now check the mipmaps against this size
		int curSize = topmostMipmapSize;
		int mipmapWidth = width;
		int mipmapHeight = height;
		int totalSize = 0;
		for (int i = 0; i < mipmapData.length; i++) {
			if (mipmapData[i].remaining() != curSize) {
				throw new IllegalArgumentException("Mipmap level " + i +
						" didn't match expected data size (expected " + curSize + ", got " +
						mipmapData[i].remaining() + ")");
			}
			/* Change Daniel Senff 
			 * I got the problem, that MipMaps below the dimension of 8x8 blocks with DXT5 
			 * where assume smaller than they are created. 
			 * Assumed: < 16byte where 16byte where used by the me.nallar.jdds.internal.compression. */
			if (isCompressed) {
				// size calculation for compressed mipmaps 
				if (mipmapWidth > 1) mipmapWidth /= 2;
				if (mipmapHeight > 1) mipmapHeight /= 2;
				curSize = computeCompressedBlockSize(mipmapWidth, mipmapHeight, 1, d3dFormat);
			} else {
				curSize /= 4;
			}
			totalSize += mipmapData[i].remaining();
		}

		// OK, create one large ByteBuffer to hold all of the mipmap data
		totalSize += Header.writtenSize();
		ByteBuffer buf = ByteBuffer.allocate(totalSize);
		buf.position(Header.writtenSize());
		for (ByteBuffer aMipmapData : mipmapData) {
			buf.put(aMipmapData);
		}
		this.buf = buf;

		// Allocate and initialize a Header
		header = new Header();
		header.size = Header.size();
		header.flags = DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PIXELFORMAT;
		if (mipmapData.length > 1) {
			header.flags |= DDSD_MIPMAPCOUNT;
			header.mipMapCountOrAux = mipmapData.length;
		}
		header.width = width;
		header.height = height;
		if (isCompressed) {
			header.flags |= DDSD_LINEARSIZE;
			header.pfFlags |= DDPF_FOURCC;
			header.pfFourCC = d3dFormat;
		} else {
			header.flags |= DDSD_PITCH;
			// Figure out the various settings from the pixel format
			header.pfFlags |= DDPF_RGB;
			switch (d3dFormat) {
				case D3DFMT_R8G8B8:
					header.pfRGBBitCount = 24;
					break;
				case D3DFMT_A8R8G8B8:
					header.pfRGBBitCount = 32;
					header.pfFlags |= DDPF_ALPHAPIXELS;
					break;
				case D3DFMT_X8R8G8B8:
					header.pfRGBBitCount = 32;
					break;
			}
			header.pfRBitMask = 0x00FF0000;
			header.pfGBitMask = 0x0000FF00;
			header.pfBBitMask = 0x000000FF;
			if (d3dFormat == D3DFMT_A8R8G8B8) {
				header.pfABitMask = 0xFF000000;
			}
		}
		header.pitchOrLinearSize = pitchOrLinearSize;
		header.pfSize = Header.pfSize();
		// Not sure whether we can get away with leaving the rest of the
		// header blank
	}

	// Microsoft doesn't follow their own specifications and the
	// simplest conversion using the DxTex tool to e.g. a DXT3 texture
	// results in an illegal .dds file without either DDSD_PITCH or
	// DDSD_LINEARSIZE set in the header's flags. This code, adapted
	// from the DevIL library, fixes up the header in these situations.
	private void fixupHeader() {
		if (isCompressed() && !isSurfaceDescFlagSet(DDSD_LINEARSIZE)) {
			// Figure out how big the linear size should be
			int depth = header.backBufferCountOrDepth;
			if (depth == 0) {
				depth = 1;
			}

			header.pitchOrLinearSize = computeCompressedBlockSize(getWidth(), getHeight(), depth, getCompressionFormat());
			header.flags |= DDSD_LINEARSIZE;
		}
	}

	private static int computeCompressedBlockSize(int width,
												  int height,
												  int depth,
												  int compressionFormat) {
		int blockSize = ((width + 3) / 4) * ((height + 3) / 4) * ((depth + 3) / 4);
		switch (compressionFormat) {
			case D3DFMT_DXT1:
				blockSize *= 8;
				break;
			default:
				blockSize *= 16;
				break;
		}
		return blockSize;
	}

	private int mipMapWidth(int map) {
		int width = getWidth();
		for (int i = 0; i < map; i++) {
			width >>= 1;
		}
		return Math.max(width, 1);
	}

	private int mipMapHeight(int map) {
		int height = getHeight();
		for (int i = 0; i < map; i++) {
			height >>= 1;
		}
		return Math.max(height, 1);
	}

	public int mipMapSizeInBytes(int map) {
		int width = mipMapWidth(map);
		int height = mipMapHeight(map);
		if (isCompressed()) {
			int blockSize = (getCompressionFormat() == D3DFMT_DXT1 ? 8 : 16);
			return ((width + 3) / 4) * ((height + 3) / 4) * blockSize;
		} else {
			return width * height * (getDepth() / 8);
		}
	}

	private int sideSizeInBytes() {
		int numLevels = getNumMipMaps();
		if (numLevels == 0) {
			numLevels = 1;
		}

		int size = 0;
		for (int i = 0; i < numLevels; i++) {
			size += mipMapSizeInBytes(i);
		}

		return size;
	}

	private int sideShiftInBytes(int side) {
		int[] sides = {
				DDSCAPS2_CUBEMAP_POSITIVEX,
				DDSCAPS2_CUBEMAP_NEGATIVEX,
				DDSCAPS2_CUBEMAP_POSITIVEY,
				DDSCAPS2_CUBEMAP_NEGATIVEY,
				DDSCAPS2_CUBEMAP_POSITIVEZ,
				DDSCAPS2_CUBEMAP_NEGATIVEZ
		};

		int shift = 0;
		int sideSize = sideSizeInBytes();
		for (int temp : sides) {
			if ((temp & side) != 0) {
				return shift;
			}

			shift += sideSize;
		}

		throw new RuntimeException("Illegal side: " + side);
	}

}
