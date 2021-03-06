///////////////////////////////////////////////////////////////////////////////
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package org.micromanager.magellan.datasaving;

import org.micromanager.magellan.coordinates.MagellanAffineUtils;
import org.micromanager.magellan.coordinates.PositionManager;
import org.micromanager.magellan.coordinates.XYStagePosition;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mmcorej.TaggedImage;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.micromanager.magellan.imagedisplaynew.DisplaySettings;
import org.micromanager.magellan.misc.JavaUtils;
import org.micromanager.magellan.misc.Log;
import org.micromanager.magellan.misc.LongPoint;
import org.micromanager.magellan.misc.MD;

/**
 * This class manages multiple multipage Tiff datasets, averaging multiple 2x2
 * squares of pixels to create successively lower resolutions until the
 * downsample factor is greater or equal to the number of tiles in a given
 * direction. This condition ensures that pixels will always be divisible by the
 * downsample factor without truncation
 *
 */
public class MultiResMultipageTiffStorage {

   private static final String FULL_RES_SUFFIX = "Full resolution";
   private static final String DOWNSAMPLE_SUFFIX = "Downsampled_x";
   private TaggedImageStorageMultipageTiff fullResStorage_;
   private TreeMap<Integer, TaggedImageStorageMultipageTiff> lowResStorages_; //map of resolution index to storage instance
   private String directory_;
   private JSONObject summaryMD_, displaySettings_;
   private int xOverlap_, yOverlap_;
   private int fullResTileWidthIncludingOverlap_, fullResTileHeightIncludingOverlap_;
   private int tileWidth_, tileHeight_; //Indpendent of zoom level because tile sizes stay the same--which means overlap is cut off
   private PositionManager posManager_;
   private volatile boolean finished_;
   private String uniqueAcqName_;
   private int byteDepth_;
   private double pixelSizeXY_, pixelSizeZ_;
   private AffineTransform affine_;
   private boolean rgb_;
   private ThreadPoolExecutor writingExecutor_;
   private volatile int maxResolutionLevel_ = 0;

   /**
    * Constructor to load existing storage from disk dir --top level saving
    * directory
    */
   public MultiResMultipageTiffStorage(String dir) throws IOException {
      directory_ = dir;
      finished_ = true;
      String fullResDir = dir + (dir.endsWith(File.separator) ? "" : File.separator) + FULL_RES_SUFFIX;
      //create fullResStorage
      fullResStorage_ = new TaggedImageStorageMultipageTiff(fullResDir, false, null, null, this);
      summaryMD_ = fullResStorage_.getSummaryMetadata();
      processSummaryMetadata();
      lowResStorages_ = new TreeMap<Integer, TaggedImageStorageMultipageTiff>();
      //create low res storages
      int resIndex = 1;
      while (true) {
         String dsDir = directory_ + (directory_.endsWith(File.separator) ? "" : File.separator)
                 + DOWNSAMPLE_SUFFIX + (int) Math.pow(2, resIndex);
         if (!new File(dsDir).exists()) {
            break;
         }
         maxResolutionLevel_ = resIndex;
         lowResStorages_.put(resIndex, new TaggedImageStorageMultipageTiff(dsDir, false, null, null, this));
         resIndex++;
      }

      //create position manager
      try {
         TreeMap<Integer, XYStagePosition> positions = new TreeMap<Integer, XYStagePosition>();
         for (String key : fullResStorage_.imageKeys()) {
            // array with entires channelIndex, sliceIndex, frameIndex, positionIndex
            int[] indices = MD.getIndices(key);
            int posIndex = indices[3];
            if (!positions.containsKey(posIndex)) {
               //read rowIndex, colIndex, stageX, stageY from per image metadata
               JSONObject md = fullResStorage_.getImageTags(indices[0], indices[1], indices[2], indices[3]);
               positions.put(posIndex, new XYStagePosition(new Point2D.Double(MD.getStageX(md), MD.getStageY(md)),
                       MD.getGridRow(md), MD.getGridCol(md), MD.getCoreXY(md)));
            }
         }
         JSONArray pList = new JSONArray();
         for (XYStagePosition xyPos : positions.values()) {
//            pList.put(xyPos.getMMPosition(MD.getCoreXY(summaryMD_)));
            pList.put(xyPos.getMMPosition());
         }
         posManager_ = new PositionManager(affine_, summaryMD_, tileWidth_, tileHeight_, tileWidth_, tileHeight_,
                 xOverlap_, xOverlap_, pList, lowResStorages_.size());

      } catch (Exception e) {
         Log.log("Couldn't create position manager", true);
      }
   }

   /**
    * Constructor for creating new storage prior to acquisition
    */
   public MultiResMultipageTiffStorage(String dir, JSONObject summaryMetadata) {
      writingExecutor_ = new ThreadPoolExecutor(1, 1, 0, TimeUnit.NANOSECONDS,
              new LinkedBlockingQueue<java.lang.Runnable>());
      try {
         //make a copy in case tag changes are needed later
         summaryMD_ = new JSONObject(summaryMetadata.toString());
      } catch (JSONException ex) {
         Log.log("Couldnt copy summary metadata", true);
      }
      processSummaryMetadata();

      //prefix is provided by summary metadata
      try {
         String baseName = summaryMetadata.getString("Prefix");
         uniqueAcqName_ = getUniqueAcqDirName(dir, baseName);
         //create acqusition directory for actual data
         directory_ = dir + (dir.endsWith(File.separator) ? "" : File.separator) + uniqueAcqName_;
      } catch (Exception e) {
         Log.log("Couldn't make acquisition directory");
      }

      //create directory for full res data
      String fullResDir = directory_ + (dir.endsWith(File.separator) ? "" : File.separator) + FULL_RES_SUFFIX;
      try {
         JavaUtils.createDirectory(fullResDir);
      } catch (Exception ex) {
         Log.log("couldn't create saving directory", true);
      }

      try {
         posManager_ = new PositionManager(affine_, summaryMD_, tileWidth_, tileHeight_,
                 fullResTileWidthIncludingOverlap_, fullResTileHeightIncludingOverlap_, xOverlap_, yOverlap_);
      } catch (Exception e) {
         Log.log("Couldn't create position manaher", true);
      }
      try {
         //Create full Res storage
         fullResStorage_ = new TaggedImageStorageMultipageTiff(fullResDir, true, summaryMetadata, writingExecutor_, this);
      } catch (IOException ex) {
         Log.log("couldn't create Full res storage", true);
      }
      lowResStorages_ = new TreeMap<Integer, TaggedImageStorageMultipageTiff>();
   }

   public void setDisplaySettings(DisplaySettings displaySettings) {
      try {
         if (displaySettings != null) {
            displaySettings_ = new JSONObject(displaySettings.toString());
         }
      } catch (JSONException ex) {
         throw new RuntimeException();
      }
   }

   public static JSONObject readSummaryMetadata(String dir) throws IOException {
      String fullResDir = dir + (dir.endsWith(File.separator) ? "" : File.separator) + FULL_RES_SUFFIX;
      return TaggedImageStorageMultipageTiff.readSummaryMD(fullResDir);
   }

   public boolean isRGB() {
      return rgb_;
   }

   public int getXOverlap() {
      return xOverlap_;
   }

   public int getYOverlap() {
      return yOverlap_;
   }

   private void processSummaryMetadata() {
      rgb_ = MD.isRGB(summaryMD_);
      xOverlap_ = MD.getPixelOverlapX(summaryMD_);
      yOverlap_ = MD.getPixelOverlapY(summaryMD_);
      byteDepth_ = MD.getBytesPerPixel(summaryMD_);
      fullResTileWidthIncludingOverlap_ = MD.getWidth(summaryMD_);
      fullResTileHeightIncludingOverlap_ = MD.getHeight(summaryMD_);
      tileWidth_ = fullResTileWidthIncludingOverlap_ - xOverlap_;
      tileHeight_ = fullResTileHeightIncludingOverlap_ - yOverlap_;
      pixelSizeZ_ = MD.getZStepUm(summaryMD_);
      pixelSizeXY_ = MD.getPixelSizeUm(summaryMD_);
      affine_ = MagellanAffineUtils.stringToTransform(MD.getAffineTransformString(summaryMD_));
   }

   public int getByteDepth() {
      return byteDepth_;
   }

   public String getUniqueAcqName() {
      return uniqueAcqName_ + ""; //make new instance
   }

   public double getPixelSizeZ() {
      return pixelSizeZ_;
   }

   public double getPixelSizeXY() {
      return pixelSizeXY_;
   }

   public int getNumResLevels() {
      return maxResolutionLevel_ + 1;
   }

   public int getTileWidth() {
      return tileWidth_;
   }

   public int getTileHeight() {
      return tileHeight_;
   }

   public long getNumRows() {
      return posManager_.getNumRows();
   }

   public long getNumCols() {
      return posManager_.getNumCols();
   }

   public long getGridRow(int fullResPosIndex, int resIndex) {
      return posManager_.getGridRow(fullResPosIndex, resIndex);
   }

   public long getGridCol(int fullResPosIndex, int resIndex) {
      return posManager_.getGridCol(fullResPosIndex, resIndex);
   }

   public XYStagePosition getXYPosition(int index) {
      return posManager_.getXYPosition(index);
   }

   public int[] getPositionIndices(int[] rows, int[] cols) {
      return posManager_.getPositionIndices(rows, cols);
   }

   /* 
    * @param stageCoords x and y coordinates of image in stage space
    * @return absolute, full resolution pixel coordinate of given stage posiiton
    */
   public LongPoint getPixelCoordsFromStageCoords(double x, double y) {
      return posManager_.getPixelCoordsFromStageCoords(x, y);
   }

   /**
    *
    * @param xAbsolute x coordinate in the full Res stitched image
    * @param yAbsolute y coordinate in the full res stitched image
    * @return stage coordinates of the given pixel position
    */
   public Point2D.Double getStageCoordsFromPixelCoords(long xAbsolute, long yAbsolute) {
      return posManager_.getStageCoordsFromPixelCoords(xAbsolute, yAbsolute);
   }

   /*
    * It doesnt matter what resolution level the pixel is at since tiles
    * are the same size at every level
    */
   private long tileIndexFromPixelIndex(long i, boolean xDirection) {
      if (i >= 0) {
         return i / (xDirection ? tileWidth_ : tileHeight_);
      } else {
         //highest pixel is -1 for tile indexed -1, so need to add one to pixel values before dividing
         return (i + 1) / (xDirection ? tileWidth_ : tileHeight_) - 1;
      }
   }

   /**
    * Method for reading 3D volumes for compatibility with TeraFly
    *
    * @return
    */
   public TaggedImage loadSubvolume(int channel, int frame, int resIndex,
           int xStart, int yStart, int zStart, int width, int height, int depth) {
      JSONObject metadata = null;
      if (byteDepth_ == 1) {
         byte[] pix = new byte[width * height * depth];
         for (int z = zStart; z < zStart + depth; z++) {
            TaggedImage image = getImageForDisplay(channel, z, frame, resIndex, xStart, yStart, width, height);
            metadata = image.tags;
            System.arraycopy(image.pix, 0, pix, (z - zStart) * (width * height), width * height);
         }
         return new TaggedImage(pix, metadata);
      } else {
         short[] pix = new short[width * height * depth];
         for (int z = zStart; z < zStart + depth; z++) {
            TaggedImage image = getImageForDisplay(channel, z, frame, resIndex, xStart, yStart, width, height);
            metadata = image.tags;
            System.arraycopy(image.pix, 0, pix, (z - zStart) * (width * height), width * height);
         }
         return new TaggedImage(pix, metadata);
      }
   }

   /**
    * Return a subimage of the larger stitched image at the appropriate zoom
    * level, loading only the tiles neccesary to form the subimage
    *
    * @param channel
    * @param slice
    * @param frame
    * @param dsIndex 0 for full res, 1 for 2x downsample, 2 for 4x downsample,
    * etc..
    * @param x coordinate of leftmost pixel in requested resolution
    * @param y coordinate of topmost pixel in requested resolution
    * @param width pixel width of image at requested resolution
    * @param height pixel height of image at requested resolution
    * @return Tagged image or taggeded image with background pixels and null
    * tags if no pixel data is present
    */
   public TaggedImage getImageForDisplay(int channel, int slice, int frame, int dsIndex, long x, long y,
           int width, int height) {
      Object pixels;
      if (rgb_) {
         pixels = new byte[width * height * 4];
      } else if (byteDepth_ == 1) {
         pixels = new byte[width * height];
      } else {
         pixels = new short[width * height];
      }
      //go line by line through one column of tiles at a time, then move to next column
      JSONObject topLeftMD = null;
      //first calculate how many columns and rows of tiles are relevant and the number of pixels
      //of each tile to copy into the returned image
      long previousCol = tileIndexFromPixelIndex(x, true) - 1; //make it one less than the first col in loop
      LinkedList<Integer> lineWidths = new LinkedList<Integer>();
      for (long i = x; i < x + width; i++) { //Iterate through every column of pixels in the image to be returned
         long colIndex = tileIndexFromPixelIndex(i, true);
         if (colIndex != previousCol) {
            lineWidths.add(0);
         }
         //Increment current width
         lineWidths.add(lineWidths.removeLast() + 1);
         previousCol = colIndex;
      }
      //do the same thing for rows
      long previousRow = tileIndexFromPixelIndex(y, false) - 1; //one less than first row in loop?
      LinkedList<Integer> lineHeights = new LinkedList<Integer>();
      for (long i = y; i < y + height; i++) {
         long rowIndex = tileIndexFromPixelIndex(i, false);
         if (rowIndex != previousRow) {
            lineHeights.add(0);
         }
         //add one to pixel count of current height
         lineHeights.add(lineHeights.removeLast() + 1);
         previousRow = rowIndex;
      }
      //get starting row and column
      long rowStart = tileIndexFromPixelIndex(y, false);
      long colStart = tileIndexFromPixelIndex(x, true);
      //xOffset and y offset are the distance from the top left of the display image into which 
      //we are copying data
      int xOffset = 0;
      for (long col = colStart; col < colStart + lineWidths.size(); col++) {
         int yOffset = 0;
         for (long row = rowStart; row < rowStart + lineHeights.size(); row++) {
            TaggedImage tile = null;
            if (dsIndex == 0) {
               tile = fullResStorage_.getImage(channel, slice, frame, posManager_.getPositionIndexFromTilePosition(dsIndex, row, col));
            } else {
               tile = lowResStorages_.get(dsIndex) == null ? null
                       : lowResStorages_.get(dsIndex).getImage(channel, slice, frame, posManager_.getPositionIndexFromTilePosition(dsIndex, row, col));
            }
            if (tile == null) {
               yOffset += lineHeights.get((int) (row - rowStart)); //increment y offset so new tiles appear in correct position
               continue; //If no data present for this tile go on to next one
            } else if ((tile.pix instanceof byte[] && ((byte[]) tile.pix).length == 0)
                    || (tile.pix instanceof short[] && ((short[]) tile.pix).length == 0)) {
               //Somtimes an inability to read IFDs soon after they are written results in an image being read 
               //with 0 length pixels. Can't figure out why this happens, but it is rare and will result at worst with
               //a black flickering during acquisition
               yOffset += lineHeights.get((int) (row - rowStart)); //increment y offset so new tiles appear in correct position
               continue;
            }
            //take top left tile for metadata
            if (topLeftMD == null) {
               topLeftMD = tile.tags;
            }
            //Copy pixels into the image to be returned
            //yOffset is how many rows from top of viewable area, y is top of image to top of area
            for (int line = yOffset; line < lineHeights.get((int) (row - rowStart)) + yOffset; line++) {
               int tileYPix = (int) ((y + line) % tileHeight_);
               int tileXPix = (int) ((x + xOffset) % tileWidth_);
               //make sure tile pixels are positive
               while (tileXPix < 0) {
                  tileXPix += tileWidth_;
               }
               while (tileYPix < 0) {
                  tileYPix += tileHeight_;
               }
               try {
                  int multiplier = rgb_ ? 4 : 1;
                  if (dsIndex == 0) {
                     //account for overlaps when viewing full resolution tiles
                     tileYPix += yOverlap_ / 2;
                     tileXPix += xOverlap_ / 2;
                     System.arraycopy(tile.pix, multiplier * (tileYPix * fullResTileWidthIncludingOverlap_ + tileXPix), pixels, (xOffset + width * line) * multiplier, multiplier * lineWidths.get((int) (col - colStart)));
                  } else {
                     System.arraycopy(tile.pix, multiplier * (tileYPix * tileWidth_ + tileXPix), pixels, multiplier * (xOffset + width * line), multiplier * lineWidths.get((int) (col - colStart)));
                  }
               } catch (Exception e) {
                  e.printStackTrace();
                  Log.log("Problem copying pixels");
               }
            }
            yOffset += lineHeights.get((int) (row - rowStart));

         }
         xOffset += lineWidths.get((int) (col - colStart));
      }
      return new TaggedImage(pixels, topLeftMD);
   }

//   /**
//    * Called before any images have been added to initialize the resolution to
//    * the specifiec zoom level
//    *
//    * @param resIndex
//    */
//   public void initializeToLevel(int resIndex) {
//      //create a null pointer in lower res storages to signal addToLoResStorage function
//      //to continue downsampling to this level
//      maxResolutionLevel_ = resIndex;
//      //Make sure position nodes for lower resolutions are created if they weren't automatically
//      posManager_.updateLowerResolutionNodes(resIndex);
//   }
   /**
    * create an additional lower resolution levels for zooming purposes
    */
   private void addResolutionsUpTo(int index) throws InterruptedException, ExecutionException {
      if (index <= maxResolutionLevel_) {
         return;
      }
      int oldLevel = maxResolutionLevel_;
      maxResolutionLevel_ = index;
      //update position manager to reflect addition of new resolution level
      posManager_.updateLowerResolutionNodes(maxResolutionLevel_);
      ArrayList<Future> finished = new ArrayList<Future>();
      for (int i = oldLevel + 1; i <= maxResolutionLevel_; i++) {
         populateNewResolutionLevel(finished, i);
         for (Future f : finished) {
            f.get();
         }
      }
   }

   private void downsample(Object currentLevelPix, Object previousLevelPix, int fullResPositionIndex, int resolutionIndex) {
      //Determine which position in 2x2 this tile sits in
      int xPos = (int) Math.abs((posManager_.getGridCol(fullResPositionIndex, resolutionIndex - 1) % 2));
      int yPos = (int) Math.abs((posManager_.getGridRow(fullResPositionIndex, resolutionIndex - 1) % 2));
      //Add one if top or left so border pixels from an odd length image gets added in
      for (int x = 0; x < tileWidth_; x += 2) { //iterate over previous res level pixels
         for (int y = 0; y < tileHeight_; y += 2) {
            //average a square of 4 pixels from previous level
            //edges: if odd number of pixels in tile, round to determine which
            //tiles pixels make it to next res level

            //these are the indices of pixels at the previous res level, which are offset
            //when moving from res level 0 to one as we throw away the overlapped image edges
            int pixelX, pixelY, previousLevelWidth, previousLevelHeight;
            if (resolutionIndex == 1) {
               //add offsets to account for overlap pixels at resolution level 0
               pixelX = x + xOverlap_ / 2;
               pixelY = y + yOverlap_ / 2;
               previousLevelWidth = fullResTileWidthIncludingOverlap_;
               previousLevelHeight = fullResTileHeightIncludingOverlap_;
            } else {
               pixelX = x;
               pixelY = y;
               previousLevelWidth = tileWidth_;
               previousLevelHeight = tileHeight_;

            }
            int rgbMultiplier_ = rgb_ ? 4 : 1;
            for (int compIndex = 0; compIndex < (rgb_ ? 3 : 1); compIndex++) {
               int count = 1; //count is number of pixels (out of 4) used to create a pixel at this level
               //always take top left pixel, maybe take others depending on whether at image edge
               int sum = 0;
               if (byteDepth_ == 1 || rgb_) {
                  sum += ((byte[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xff;
               } else {
                  sum += ((short[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xffff;
               }

               //pixel index can be different from index in tile at resolution level 0 if there is nonzero overlap
               if (x < previousLevelWidth - 1 && y < previousLevelHeight - 1) { //if not bottom right corner, add three more pix
                  count += 3;
                  if (byteDepth_ == 1 || rgb_) {
                     sum += (((byte[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xff)
                             + (((byte[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xff)
                             + (((byte[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xff);
                  } else {
                     sum += (((short[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xffff)
                             + (((short[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xffff)
                             + (((short[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xffff);
                  }
               } else if (x < previousLevelWidth - 1) { //if not right edge, add one more pix
                  count++;
                  if (byteDepth_ == 1 || rgb_) {
                     sum += ((byte[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xff;
                  } else {
                     sum += ((short[]) previousLevelPix)[(pixelY * previousLevelWidth + pixelX + 1) * rgbMultiplier_ + compIndex] & 0xffff;
                  }
               } else if (y < previousLevelHeight - 1) { // if not bottom edge, add one more pix
                  count++;
                  if (byteDepth_ == 1 || rgb_) {
                     sum += ((byte[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xff;
                  } else {
                     sum += ((short[]) previousLevelPix)[((pixelY + 1) * previousLevelWidth + pixelX) * rgbMultiplier_ + compIndex] & 0xffff;
                  }
               } else {
                  //it is the bottom right corner, no more pix to add
               }
               //add averaged pixel into appropriate quadrant of current res level
               //if full res tile has an odd number of pix, the last one gets chopped off
               //to make it fit into tile containers
               try {
                  int index = (((y + yPos * tileHeight_) / 2) * tileWidth_ + (x + xPos * tileWidth_) / 2) * rgbMultiplier_ + compIndex;
                  if (byteDepth_ == 1 || rgb_) {
                     ((byte[]) currentLevelPix)[index] = (byte) Math.round(sum / count);
                  } else {
                     ((short[]) currentLevelPix)[index] = (short) Math.round(sum / count);
                  }
               } catch (Exception e) {
                  Log.log("Couldn't copy pixels to lower resolution");
                  e.printStackTrace();
                  throw new RuntimeException(e);
               }

            }
         }
      }
   }

   private void populateNewResolutionLevel(List<Future> writeFinishedList, int resolutionIndex) {
      createDownsampledStorage(resolutionIndex);
      //add all tiles from existing resolution levels to this new one            
      TaggedImageStorageMultipageTiff previousLevelStorage
              = resolutionIndex == 1 ? fullResStorage_ : lowResStorages_.get(resolutionIndex - 1);
      Set<String> imageKeys = previousLevelStorage.imageKeys();
      for (String key : imageKeys) {
         String[] indices = key.split("_");
         TaggedImage ti = previousLevelStorage.getImage(Integer.parseInt(indices[0]), Integer.parseInt(indices[1]),
                 Integer.parseInt(indices[2]), Integer.parseInt(indices[3]));
         writeFinishedList.addAll(addToLowResStorage(ti, resolutionIndex - 1,
                 posManager_.getFullResPositionIndex(Integer.parseInt(indices[3]), resolutionIndex - 1)));
      }
   }

   /**
    * return a future for when the current res level is done writing
    */
   private List<Future> addToLowResStorage(TaggedImage img, int previousResIndex, int fullResPositionIndex) {
      List<Future> writeFinishedList = new ArrayList<>();
      //Read indices
      int channel = MD.getChannelIndex(img.tags);
      int slice = MD.getSliceIndex(img.tags);
      int frame = MD.getFrameIndex(img.tags);

      Object previousLevelPix = img.pix;
      int resolutionIndex = previousResIndex + 1;

      while (resolutionIndex <= maxResolutionLevel_) {
         //Create this storage level if needed and add all existing tiles form the previous one
         if (!lowResStorages_.containsKey(resolutionIndex)) {
            //re add all tiles from previous res level
            populateNewResolutionLevel(writeFinishedList, resolutionIndex);
            //its been re added, so can return here and previous call will get to the code below
            return writeFinishedList;
         }

         //Create pixels or get appropriate pixels to add to
         TaggedImage existingImage = lowResStorages_.get(resolutionIndex).getImage(channel, slice, frame,
                 posManager_.getLowResPositionIndex(fullResPositionIndex, resolutionIndex));
         Object currentLevelPix;
         if (existingImage == null) {
            if (rgb_) {
               currentLevelPix = new byte[tileWidth_ * tileHeight_ * 4];
            } else if (byteDepth_ == 1) {
               currentLevelPix = new byte[tileWidth_ * tileHeight_];
            } else {
               currentLevelPix = new short[tileWidth_ * tileHeight_];
            }
         } else {
            currentLevelPix = existingImage.pix;
         }

         downsample(currentLevelPix, previousLevelPix, fullResPositionIndex, resolutionIndex);

         //store this tile in the storage class correspondign to this resolution
         try {
            if (existingImage == null) {     //Image doesn't yet exist at this level, so add it
               //create a copy of tags so tags from a different res level arent inadverntanly modified
               // while waiting for being written to disk
               JSONObject tags = new JSONObject(img.tags.toString());
               //modify tags to reflect image size, and correct position index
               MD.setWidth(tags, tileWidth_);
               MD.setHeight(tags, tileHeight_);
               long gridRow = posManager_.getGridRow(fullResPositionIndex, resolutionIndex);
               long gridCol = posManager_.getGridCol(fullResPositionIndex, resolutionIndex);
               MD.setPositionName(tags, "Grid_" + gridRow + "_" + gridCol);
               MD.setPositionIndex(tags, posManager_.getLowResPositionIndex(fullResPositionIndex, resolutionIndex));
               Future f = lowResStorages_.get(resolutionIndex).putImage(new TaggedImage(currentLevelPix, tags));
               //need to make sure this one gets written before others can be overwritten
               f.get();
            } else {
               //Image already exists, only overwrite pixels to include new tiles
               writeFinishedList.addAll(lowResStorages_.get(resolutionIndex).overwritePixels(currentLevelPix,
                       channel, slice, frame, posManager_.getLowResPositionIndex(fullResPositionIndex, resolutionIndex)));
            }
         } catch (Exception e) {
            e.printStackTrace();
            Log.log("Couldnt modify tags for lower resolution level");
            throw new RuntimeException(e);
         }

         //go on to next level of downsampling
         previousLevelPix = currentLevelPix;
         resolutionIndex++;
      }
      return writeFinishedList;
   }

   private void createDownsampledStorage(int resIndex) {
      String dsDir = directory_ + (directory_.endsWith(File.separator) ? "" : File.separator)
              + DOWNSAMPLE_SUFFIX + (int) Math.pow(2, resIndex);
      try {
         JavaUtils.createDirectory(dsDir);
      } catch (Exception ex) {
         Log.log("copuldnt create directory");
      }
      try {
         JSONObject smd = new JSONObject(summaryMD_.toString());
         //reset dimensions so that overlap not included
         MD.setWidth(smd, tileWidth_);
         MD.setHeight(smd, tileHeight_);
         TaggedImageStorageMultipageTiff storage = new TaggedImageStorageMultipageTiff(dsDir, true, smd, writingExecutor_, this);
         lowResStorages_.put(resIndex, storage);
      } catch (Exception ex) {
         Log.log("Couldnt create downsampled storage");
      }
   }

   /**
    * Don't return until all images have been written to disk
    */
   public void putImage(TaggedImage MagellanTaggedImage) {
      try {
         List<Future> writeFinishedList = new ArrayList<Future>();
         //write to full res storage as normal (i.e. with overlap pixels present)
         writeFinishedList.add(fullResStorage_.putImage(MagellanTaggedImage));
         //check if maximum resolution level needs to be updated based on full size of image
         long fullResPixelWidth = getNumCols() * getTileWidth();
         long fullResPixelHeight = getNumRows() * getTileHeight();
         int maxResIndex = (int) Math.ceil(Math.log((Math.max(fullResPixelWidth, fullResPixelHeight)
                 / 4)) / Math.log(2));
         addResolutionsUpTo(maxResIndex);
         writeFinishedList.addAll(addToLowResStorage(MagellanTaggedImage, 0, MD.getPositionIndex(MagellanTaggedImage.tags)));
         for (Future f : writeFinishedList) {
            f.get();
         }
      } catch (IOException | ExecutionException | InterruptedException ex) {
         Log.log(ex.toString());
         throw new RuntimeException(ex);
      }
   }

   public TaggedImage getImage(int channelIndex, int sliceIndex, int frameIndex, int positionIndex, int resLevel) {
      if (resLevel == 0) {
         return fullResStorage_.getImage(channelIndex, sliceIndex, frameIndex, positionIndex);
      } else {
         return lowResStorages_.get(resLevel).getImage(channelIndex, sliceIndex, frameIndex, positionIndex);
      }
   }

   public TaggedImage getImage(int channelIndex, int sliceIndex, int frameIndex, int positionIndex) {
      //return a single tile from the full res image
      return fullResStorage_.getImage(channelIndex, sliceIndex, frameIndex, positionIndex);
   }

   public JSONObject getImageTags(int channelIndex, int sliceIndex, int frameIndex, int positionIndex) {
      return getImage(channelIndex, sliceIndex, frameIndex, positionIndex).tags;
   }

   public Set<String> imageKeys() {
      return fullResStorage_.imageKeys();
   }

   public void finishedWriting() {
      if (finished_) {
         return;
      }
      fullResStorage_.finished();
      for (TaggedImageStorageMultipageTiff s : lowResStorages_.values()) {
         if (s != null) {
            //s shouldn't be null ever, this check is to prevent window from getting into unclosable state
            //when other bugs prevent storage from being properly created
            s.finished();
         }
      }
      writingExecutor_.shutdown();
      //shut down writing executor--pause here until all tasks have finished writing
      //so that no attempt is made to close the dataset (and thus the FileChannel)
      //before everything has finished writing
      //mkae sure all images have finished writing if they are on seperate thread 
      try {
         writingExecutor_.awaitTermination(5, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ex) {
         Log.log("unexpected interrup when closing image storage");
      }
      finished_ = true;
   }

   public boolean isFinished() {
      return finished_;
   }

   public void setSummaryMetadata(JSONObject md) {
      fullResStorage_.setSummaryMetadata(md);
   }

   public JSONObject getSummaryMetadata() {
      return fullResStorage_.getSummaryMetadata();
   }

   public JSONObject getDisplaySettings() {
      return displaySettings_;
   }

   public void close() {
      //put closing on differnt channel so as to not hang up EDT while waiting for finishing
      new Thread(new Runnable() {
         @Override
         public void run() {
            while (!finished_) {
               try {
                  Thread.sleep(5);
               } catch (InterruptedException ex) {
                  throw new RuntimeException("closing thread interrupted");
               }
            }
            fullResStorage_.close();
            for (TaggedImageStorageMultipageTiff s : lowResStorages_.values()) {
               if (s != null) { //this only happens if the viewer requested new resolution levels that were never filled in because no iamges arrived                  
                  s.close();
               }
            }
         }
      }, "closing thread").start();
   }

   public String getDiskLocation() {
      //For display purposes
      return directory_;
   }

   public int getNumChannels() {
      return fullResStorage_.getNumChannels();
   }

   public int getNumFrames() {
      return fullResStorage_.getMaxFrameIndexOpenedDataset() + 1;
   }

   public int getNumSlices() {
      return fullResStorage_.getMaxSliceIndexOpenedDataset() - fullResStorage_.getMinSliceIndexOpenedDataset() + 1;
   }

   public int getMinSliceIndexOpenedDataset() {
      return fullResStorage_.getMinSliceIndexOpenedDataset();
   }

   public int getMaxSliceIndexOpenedDataset() {
      return fullResStorage_.getMaxSliceIndexOpenedDataset();
   }

   public long getDataSetSize() {
      long sum = 0;
      sum += fullResStorage_.getDataSetSize();
      for (TaggedImageStorageMultipageTiff s : lowResStorages_.values()) {
         sum += s.getDataSetSize();
      }
      return sum;
   }

   //Copied from MMAcquisition
   private String getUniqueAcqDirName(String root, String prefix) throws Exception {
      File rootDir = JavaUtils.createDirectory(root);
      int curIndex = getCurrentMaxDirIndex(rootDir, prefix + "_");
      return prefix + "_" + (1 + curIndex);
   }

   private int getCurrentMaxDirIndex(File rootDir, String prefix) throws NumberFormatException {
      int maxNumber = 0;
      int number;
      String theName;
      for (File acqDir : rootDir.listFiles()) {
         theName = acqDir.getName();
         if (theName.toUpperCase().startsWith(prefix.toUpperCase())) {
            try {
               //e.g.: "blah_32.ome.tiff"
               Pattern p = Pattern.compile("\\Q" + prefix.toUpperCase() + "\\E" + "(\\d+).*+");
               Matcher m = p.matcher(theName.toUpperCase());
               if (m.matches()) {
                  number = Integer.parseInt(m.group(1));
                  if (number >= maxNumber) {
                     maxNumber = number;
                  }
               }
            } catch (NumberFormatException e) {
            } // Do nothing.
         }
      }
      return maxNumber;
   }

   /**
    *
    * @param sliceIndex
    * @return set of points (col, row) with indices of tiles that have been
    * added at this slice index
    */
   public Set<Point> getTileIndicesWithDataAt(int sliceIndex) {
      Set<Point> exploredTiles = new TreeSet<Point>(new Comparator<Point>() {
         @Override
         public int compare(Point o1, Point o2) {
            if (o1.x != o2.x) {
               return o1.x - o2.x;
            } else if (o1.y != o2.y) {
               return o1.y - o2.y;
            }
            return 0;
         }
      });
      Set<String> keys = new TreeSet<String>(imageKeys());
      for (String s : keys) {
         int[] indices = MD.getIndices(s);
         if (indices[1] == sliceIndex) {
            exploredTiles.add(new Point((int) posManager_.getGridCol(indices[3], 0), (int) posManager_.getGridRow(indices[3], 0)));
         }

      }
      return exploredTiles;
   }

   public long getMinRow() {
      return posManager_.getMinRow();
   }

   public long getMinCol() {
      return posManager_.getMinCol();
   }

   int getPositionIndexFromStageCoords(double xPos, double yPos) {
      return posManager_.getFullResPositionIndexFromStageCoords(xPos, yPos);
   }

   public PositionManager getPosManager() {
      return posManager_;
   }

   public List<String> getChannelNames() {
      List<String> channelNames = new ArrayList<>();
      Set<String> channelIndices = new TreeSet<String>();
      for (String key : imageKeys()) {
         String[] indices = key.split("_");
         if (!channelIndices.contains(indices[0])) {
            channelIndices.add(indices[0]);
            channelNames.add(MD.getChannelName(getImageTags(
                    Integer.parseInt(indices[0]), Integer.parseInt(indices[1]),
                    Integer.parseInt(indices[2]), Integer.parseInt(indices[3]))));
         }
      }
      return channelNames;
   }

}
