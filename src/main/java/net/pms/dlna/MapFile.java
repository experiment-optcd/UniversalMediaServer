/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.dlna;

import static org.apache.commons.lang3.StringUtils.isBlank;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import net.pms.configuration.MapFileConfiguration;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;
import net.pms.util.FileUtil;
import net.pms.util.UMSUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapFile extends DLNAResource {
	private static final Logger LOGGER = LoggerFactory.getLogger(MapFile.class);

	/**
	 * An array of {@link String}s that defines the lower-case representation of
	 * the file extensions that are eligible for evaluation as file or folder
	 * thumbnails.
	 */
	public static final Set<String> THUMBNAIL_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList(new String[] {"jpeg", "jpg", "png"})
	));

	/**
	 * An array of {@link String}s that defines the file extensions that are
	 * never media so we should not attempt to parse.
	 */
	public static final Set<String> EXTENSIONS_DENYLIST = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList(new String[] {"!qB", "!ut", "1", "dmg", "exe"})
	));

	private List<File> discoverable;
	private List<File> emptyFoldersToRescan;
	private String forcedName;
	private ArrayList<RealFile> searchList;
	private File potentialCover;
	private MapFileConfiguration conf;

	public MapFile() {
		this.conf = new MapFileConfiguration();
		setLastModified(0);
		forcedName = null;
	}

	public MapFile(MapFileConfiguration conf) {
		this.conf = conf;
		setLastModified(0);
		forcedName = null;
	}

	public MapFile(MapFileConfiguration conf, List<File> list) {
		this.conf = conf;
		setLastModified(0);
		this.discoverable = list;
		forcedName = null;
	}

	private void manageFile(File f, boolean isAddGlobally) {
		if (f.isFile() || f.isDirectory()) {
			String lcFilename = f.getName().toLowerCase();

			if (!f.isHidden()) {
				if (configuration.isArchiveBrowsing() && (lcFilename.endsWith(".zip") || lcFilename.endsWith(".cbz"))) {
					addChild(new ZippedFile(f), true, isAddGlobally);
				} else if (configuration.isArchiveBrowsing() && (lcFilename.endsWith(".rar") || lcFilename.endsWith(".cbr"))) {
					addChild(new RarredFile(f), true, isAddGlobally);
				} else if (
					configuration.isArchiveBrowsing() && (
						lcFilename.endsWith(".tar") ||
						lcFilename.endsWith(".gzip") ||
						lcFilename.endsWith(".gz") ||
						lcFilename.endsWith(".7z")
					)
				) {
					addChild(new SevenZipFile(f), true, isAddGlobally);
				} else if (
					lcFilename.endsWith(".iso") ||
					lcFilename.endsWith(".img") || (
						f.isDirectory() &&
						f.getName().toUpperCase(Locale.ROOT).equals("VIDEO_TS")
					)
				) {
					addChild(new DVDISOFile(f), true, isAddGlobally);
				} else if (
					lcFilename.endsWith(".m3u") ||
					lcFilename.endsWith(".m3u8") ||
					lcFilename.endsWith(".pls") ||
					lcFilename.endsWith(".cue") ||
					lcFilename.endsWith(".ups")
				) {
					DLNAResource d = PlaylistFolder.getPlaylist(lcFilename, f.getAbsolutePath(), 0);
					if (d != null) {
						addChild(d, true, isAddGlobally);
					}
				} else {
					ArrayList<String> ignoredFolderNames = configuration.getIgnoredFolderNames();

					/* Optionally ignore empty directories */
					if (f.isDirectory() && configuration.isHideEmptyFolders() && !FileUtil.isFolderRelevant(f, configuration)) {
						LOGGER.debug("Ignoring empty/non-relevant directory: " + f.getName());
						// Keep track of the fact that we have empty folders, so when we're asked if we should refresh,
						// we can re-scan the folders in this list to see if they contain something relevant
						if (emptyFoldersToRescan == null) {
							emptyFoldersToRescan = new ArrayList<>();
						}
						if (!emptyFoldersToRescan.contains(f)) {
							emptyFoldersToRescan.add(f);
						}
					} else if (f.isDirectory() && !ignoredFolderNames.isEmpty() && ignoredFolderNames.contains(f.getName())) {
						LOGGER.debug("Ignoring {} because it is in the ignored folders list", f.getName());
					} else {
						// Otherwise add the file
						RealFile rf = new RealFile(f);
						if (rf.length() == 0  && !rf.isFolder()) {
							LOGGER.debug("Ignoring {} because it seems corrupted when the length of the file is 0", f.getName());
							return;
						}

						//we need to propagate the flag in order to make all hierarchy stay outside the media library if needed
						rf.getConf().setAddToMediaLibrary(this.getConf().isAddToMediaLibrary());
						if (searchList != null) {
							searchList.add(rf);
						}
						addChild(rf, true, isAddGlobally);
					}
				}
			}
		}
	}

	private File getPath() {
		if (this instanceof RealFile) {
			return ((RealFile) this).getFile();
		}
		return null;
	}

	private List<File> getFilesListForDirectories() {
		List<File> out = new ArrayList<>();
		ArrayList<String> ignoredDirectoryNames = configuration.getIgnoredFolderNames();
		String directoryName;
		for (File directory : this.conf.getFiles()) {
			directoryName = directory == null || directory.getName() == null ? "unnamed" : directory.getName();
			if (directory == null || !directory.isDirectory()) {
				LOGGER.trace("Ignoring {} because it is not a valid directory", directoryName);
				continue;
			}

			// Skip if ignored
			if (!ignoredDirectoryNames.isEmpty() && ignoredDirectoryNames.contains(directoryName)) {
				LOGGER.debug("Ignoring {} because it is in the ignored directories list", directoryName);
				continue;
			}

			if (directory.canRead()) {
				File[] files = directory.listFiles((File parentDirectory, String name) -> {
					// Accept any directory
					Path path = Paths.get(parentDirectory + File.separator + name);
					if (Files.isDirectory(path)) {
						return true;
					}

					// We want to find only media files
					return isPotentialMediaFile(name);
				});

				if (files == null) {
					LOGGER.warn("Can't read files from directory: {}", directory.getAbsolutePath());
				} else {
					out.addAll(Arrays.asList(files));
				}
			} else {
				LOGGER.warn("Can't read directory: {}", directory.getAbsolutePath());
			}
		}

		return out;
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public boolean analyzeChildren(int count) {
		return analyzeChildren(count, true);
	}

	@Override
	public boolean analyzeChildren(int count, boolean isAddGlobally) {
		int currentChildrenCount = getChildren().size();
		int vfolder = 0;
		FileSearch fs = null;
		if (!discoverable.isEmpty() && configuration.getSearchInFolder()) {
			searchList = new ArrayList<>();
			fs = new FileSearch(searchList);
			addChild(new SearchFolder(fs));
		}
		while (((getChildren().size() - currentChildrenCount) < count) || (count == -1)) {
			if (vfolder < getConf().getChildren().size()) {
				addChild(new MapFile(getConf().getChildren().get(vfolder)), true, isAddGlobally);
				++vfolder;
			} else {
				if (discoverable.isEmpty()) {
					break;
				}
				manageFile(discoverable.remove(0), isAddGlobally);
			}
		}
		if (fs != null) {
			fs.update(searchList);
		}
		return discoverable.isEmpty();
	}

	@Override
	public void discoverChildren() {
		discoverChildren(null, true);
	}

	@Override
	public void discoverChildren(String str) {
		discoverChildren(str, true);
	}

	public void discoverChildren(String str, boolean isAddGlobally) {
		if (discoverable == null) {
			discoverable = new ArrayList<>();
		} else {
			return;
		}

		int sm = configuration.getSortMethod(getPath());

		List<File> files = getFilesListForDirectories();

		// Build a map of all files and their corresponding formats
		HashSet<File> images = new HashSet<>();
		HashSet<File> audioVideo = new HashSet<>();
		Iterator<File> iterator = files.iterator();
		while (iterator.hasNext()) {
			File file = iterator.next();
			if (file.isFile()) {
				if (isPotentialThumbnail(file.getName())) {
					if (isFolderThumbnail(file, false)) {
						potentialCover = file;
						iterator.remove();
					} else {
						images.add(file);
					}
				} else {
					Format format = FormatFactory.getAssociatedFormat(file.getAbsolutePath());
					if (format != null && (format.isAudio() || format.isVideo())) {
						audioVideo.add(file);
					}
				}
			}
		}

		// Remove cover/thumbnails from file list
		if (!images.isEmpty() && !audioVideo.isEmpty()) {
			HashSet<File> potentialMatches;
			for (File audioVideoFile : audioVideo) {
				potentialMatches = getPotentialFileThumbnails(audioVideoFile, false);
				iterator = images.iterator();
				while (iterator.hasNext()) {
					File imageFile = iterator.next();
					if (potentialMatches.contains(imageFile)) {
						iterator.remove();
						files.remove(imageFile);
					}
				}
			}
		}

		// ATZ handling
		if (files.size() > configuration.getATZLimit() && StringUtils.isEmpty(forcedName)) {
			/*
			 * Too many files to display at once, add A-Z folders
			 * instead and let the filters begin
			 *
			 * Note: If we done this at the level directly above we don't do it again
			 * since all files start with the same letter then
			 */
			TreeMap<String, ArrayList<File>> map = new TreeMap<>();
			for (File f : files) {
				if ((!f.isFile() && !f.isDirectory()) || f.isHidden()) {
					// skip these
					continue;
				}
				if (f.isDirectory() && configuration.isHideEmptyFolders() && !FileUtil.isFolderRelevant(f, configuration)) {
					LOGGER.debug("Ignoring empty/non-relevant directory: " + f.getName());
					// Keep track of the fact that we have empty folders, so when we're asked if we should refresh,
					// we can re-scan the folders in this list to see if they contain something relevant
					if (emptyFoldersToRescan == null) {
						emptyFoldersToRescan = new ArrayList<>();
					}
					if (!emptyFoldersToRescan.contains(f)) {
						emptyFoldersToRescan.add(f);
					}
					continue;
				}

				String filenameToSort = FileUtil.renameForSorting(f.getName(), false, f.getAbsolutePath());

				char c = filenameToSort.toUpperCase().charAt(0);

				if (!(c >= 'A' && c <= 'Z')) {
					// "other char"
					c = '#';
				}
				ArrayList<File> l = map.get(String.valueOf(c));
				if (l == null) {
					// new letter
					l = new ArrayList<>();
				}
				l.add(f);
				map.put(String.valueOf(c), l);
			}

			for (Entry<String, ArrayList<File>> entry : map.entrySet()) {
				// loop over all letters, this avoids adding
				// empty letters
				UMSUtils.sort(entry.getValue(), sm);
				MapFile mf = new MapFile(getConf(), entry.getValue());
				mf.forcedName = entry.getKey();
				addChild(mf, true, isAddGlobally);
			}
			return;
		}

		UMSUtils.sort(files, (sm == UMSUtils.SORT_RANDOM ? UMSUtils.SORT_LOC_NAT : sm));

		for (File f : files) {
			if (f.isDirectory()) {
				discoverable.add(f); // manageFile(f);
			}
		}

		// For random sorting, we only randomize file entries
		if (sm == UMSUtils.SORT_RANDOM) {
			UMSUtils.sort(files, sm);
		}

		for (File f : files) {
			if (f.isFile()) {
				discoverable.add(f); // manageFile(f);
			}
		}
	}

	public void doRefreshChildren(String str, boolean isAddGlobally) {
		getChildren().clear();
		emptyFoldersToRescan = null; // Since we're re-scanning, reset this list so it can be built again
		discoverable = null;
		discoverChildren(str, isAddGlobally);
		analyzeChildren(-1, isAddGlobally);
	}

	/**
	 * @return the conf
	 * @since 1.50
	 */
	protected MapFileConfiguration getConf() {
		return conf;
	}

	/**
	 * @param conf the conf to set
	 * @since 1.50
	 */
	protected void setConf(MapFileConfiguration conf) {
		this.conf = conf;
	}

	/**
	 * @return the potentialCover
	 * @since 1.50
	 */
	public File getPotentialCover() {
		return potentialCover;
	}

	/**
	 * @param potentialCover the potentialCover to set
	 * @since 1.50
	 */
	public void setPotentialCover(File potentialCover) {
		this.potentialCover = potentialCover;
	}

	@Override
	public boolean isRefreshNeeded() {
		long modified = 0;

		for (File f : this.getConf().getFiles()) {
			if (f != null) {
				modified = Math.max(modified, f.lastModified());
			}
		}

		// Check if any of our previously empty folders now have content
		boolean emptyFolderNowNotEmpty = false;
		if (emptyFoldersToRescan != null) {
			for (File emptyFile : emptyFoldersToRescan) {
				if (FileUtil.isFolderRelevant(emptyFile, configuration)) {
					emptyFolderNowNotEmpty = true;
					break;
				}
			}
		}
		return
			getLastRefreshTime() < modified ||
			configuration.getSortMethod(getPath()) == UMSUtils.SORT_RANDOM ||
			emptyFolderNowNotEmpty;
	}

	@Override
	public void doRefreshChildren() {
		doRefreshChildren(null, true);
	}

	@Override
	public void doRefreshChildren(String str) {
		doRefreshChildren(str, true);
	}

	@Override
	public boolean isSearched() {
		return (getParent() instanceof SearchFolder);
	}

	@Override
	public boolean isAddToMediaLibrary() {
		return getConf().isAddToMediaLibrary();
	}

	@Override
	public String getSystemName() {
		return getName();
	}

	@Override
	public long length() {
		return 0;
	}

	@Override
	public String getName() {
		if (StringUtils.isEmpty(forcedName)) {
			return this.getConf().getName();
		}
		return forcedName;
	}

	@Override
	public boolean isFolder() {
		return true;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return null;
	}

	@Override
	public boolean allowScan() {
		return isFolder();
	}

	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		result.append(getClass().getSimpleName());
		result.append(" [id=").append(getId());
		result.append(", name=").append(getName());
		result.append(", format=").append(getFormat());
		result.append(", discovered=").append(isDiscovered());
		if (getMediaAudio() != null) {
			result.append(", selected audio=[").append(getMediaAudio()).append("]");
		}
		if (getMediaSubtitle() != null) {
			result.append(", selected subtitles=[").append(getMediaSubtitle()).append("]");
		}
		if (getPlayer() != null) {
			result.append(", player=").append(getPlayer());
		}
		if (getChildren() != null && !getChildren().isEmpty()) {
			result.append(", children=").append(getChildren());
		}
		result.append(']');
		return result.toString();
	}

	/**
	 * Returns the first {@link File} in a specified folder that is considered a
	 * "folder thumbnail" by naming convention.
	 *
	 * @param folder the folder to search for a folder thumbnail.
	 * @return The first "folder thumbnail" file in {@code folder} or
	 *         {@code null} if none was found.
	 */
	public static File getFolderThumbnail(File folder) {
		if (folder == null || !folder.isDirectory()) {
			return null;
		}
		try (DirectoryStream<Path> folderThumbnails = Files.newDirectoryStream(folder.toPath(), (Path entry) -> {
				Path fileNamePath = entry.getFileName();
				if (fileNamePath == null) {
					return false;
				}
				String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
				if (fileName.startsWith("folder.") || fileName.contains("albumart")) {
					return isPotentialThumbnail(fileName);
				}
				return false;
			})) {
			for (Path folderThumbnail : folderThumbnails) {
				// We don't have any rule to prioritize between them; return the first
				return folderThumbnail.toFile();
			}
		} catch (IOException e) {
			LOGGER.warn("An error occurred while trying to browse folder \"{}\": {}", folder.getAbsolutePath(), e.getMessage());
			LOGGER.trace("", e);
		}
		return null;
	}

	/**
	 * Returns whether or not the specified {@link File} is considered a
	 * "folder thumbnail" by naming convention.
	 *
	 * @param file the {@link File} to evaluate.
	 * @param evaluateExtension if {@code true} the file extension will also be
	 *            evaluated in addition to the file name, if {@code false} only
	 *            the file name will be evaluated.
	 * @return {@code true} if {@code file} name matches the naming convention
	 *         for folder thumbnails, {@code false} otherwise.
	 */
	public static boolean isFolderThumbnail(File file, boolean evaluateExtension) {
		if (file == null || !file.isFile()) {
			return false;
		}

		String fileName = file.getName();
		if (isBlank(fileName)) {
			return false;
		}
		if (evaluateExtension && !isPotentialThumbnail(fileName)) {
			return false;
		}
		fileName = fileName.toLowerCase(Locale.ROOT);
		return fileName.startsWith("folder.") || fileName.contains("albumart");
	}

	/**
	 * Returns the potential thumbnail resources for the specified audio or
	 * video file as a {@link Set} of {@link File}s.
	 *
	 * @param audioVideoFile the {@link File} for which to enumerate potential
	 *            thumbnail files.
	 * @param existingOnly if {@code true}, files will only be added to the
	 *            returned {@link Set} if they {@link File#exists()}.
	 * @return The {@link Set} of {@link File}s.
	 */
	public static HashSet<File> getPotentialFileThumbnails(
		File audioVideoFile,
		boolean existingOnly
	) {
		File file;
		HashSet<File> potentialMatches = new HashSet<>(THUMBNAIL_EXTENSIONS.size() * 2);
		for (String extension : THUMBNAIL_EXTENSIONS) {
			file = FileUtil.replaceExtension(audioVideoFile, extension, false, true);
			if (!existingOnly || file.exists()) {
				potentialMatches.add(file);
			}
			file = new File(audioVideoFile.toString() + ".cover." + extension);
			if (!existingOnly || file.exists()) {
				potentialMatches.add(file);
			}
		}
		return potentialMatches;
	}

	/**
	 * Returns whether or not the specified {@link File} has an extension that
	 * makes it a possible thumbnail file that should be considered a thumbnail
	 * resource belonging to another file or folder.
	 *
	 * @param file the {@link File} to evaluate.
	 * @return {@code true} if {@code file} has one of the predefined
	 *         {@link MapFile#THUMBNAIL_EXTENSIONS} extensions, {@code false}
	 *         otherwise.
	 */
	public static boolean isPotentialThumbnail(File file) {
		return file != null && file.isFile() && isPotentialThumbnail(file.getName());
	}

	/**
	 * Returns whether or not {@code fileName} has an extension that makes it a
	 * possible thumbnail file that should be considered a thumbnail resource
	 * belonging to another file or folder.
	 *
	 * @param fileName the file name to evaluate.
	 * @return {@code true} if {@code fileName} has one of the predefined
	 *         {@link MapFile#THUMBNAIL_EXTENSIONS} extensions, {@code false}
	 *         otherwise.
	 */
	public static boolean isPotentialThumbnail(String fileName) {
		return MapFile.THUMBNAIL_EXTENSIONS.contains(FileUtil.getExtension(fileName));
	}

	/**
	 * Returns whether {@code fileName} has an extension that is not on our
	 * list of extensions that can't be media files.
	 *
	 * @param fileName the file name to evaluate.
	 * @return {@code true} if {@code fileName} has not the one of the predefined
	 *         {@link MapFile#EXTENSIONS_DENYLIST} extensions, {@code false}
	 *         otherwise.
	 */
	public static boolean isPotentialMediaFile(String fileName) {
		return !MapFile.EXTENSIONS_DENYLIST.contains(FileUtil.getExtension(fileName));
	}

}
