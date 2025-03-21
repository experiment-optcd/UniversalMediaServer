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

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.Win32Exception;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.text.Collator;
import java.text.Normalizer;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.MapFileConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableFiles;
import net.pms.dlna.virtual.MediaLibrary;
import net.pms.dlna.virtual.VirtualFolder;
import net.pms.dlna.virtual.VirtualFolderDbId;
import net.pms.dlna.virtual.VirtualVideoAction;
import net.pms.formats.Format;
import net.pms.gui.GuiManager;
import net.pms.io.BasicSystemUtils;
import net.pms.io.StreamGobbler;
import net.pms.newgui.SharedContentTab;
import net.pms.platform.macos.NSFoundation;
import net.pms.platform.macos.NSFoundation.NSSearchPathDirectory;
import net.pms.platform.macos.NSFoundation.NSSearchPathDomainMask;
import net.pms.platform.windows.CSIDL;
import net.pms.platform.windows.GUID;
import net.pms.platform.windows.KnownFolders;
import net.pms.util.CodeDb;
import net.pms.util.FilePermissions;
import net.pms.util.FileUtil;
import net.pms.util.FileWatcher;
import net.pms.util.ProcessUtil;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmlwise.Plist;
import xmlwise.XmlParseException;

public class RootFolder extends DLNAResource {
	private static final Logger LOGGER = LoggerFactory.getLogger(RootFolder.class);
	private final ArrayList<DLNAResource> webFolders;
	private boolean running;
	private FolderLimit lim;
	private MediaMonitor mon;

	public RootFolder() {
		setIndexId(0);
		webFolders = new ArrayList<>();
		addVirtualMyMusicFolder();
	}

	private void addVirtualMyMusicFolder() {
		DbIdTypeAndIdent2 myAlbums = new DbIdTypeAndIdent2(DbIdMediaType.TYPE_MYMUSIC_ALBUM, null);
		VirtualFolderDbId myMusicFolder = new VirtualFolderDbId(Messages.getString("MyAlbums"), myAlbums, "");
		if (PMS.getConfiguration().displayAudioLikesInRootFolder()) {
			if (!getChildren().contains(myMusicFolder)) {
				myMusicFolder.setFakeParentId("0");
				addChild(myMusicFolder, true, false);
				LOGGER.debug("adding My Music folder to root");
			}
		} else {
			if (
				PMS.get().getLibrary().isEnabled() &&
				PMS.get().getLibrary().getAudioFolder() != null &&
				PMS.get().getLibrary().getAudioFolder().getChildren() != null &&
				!PMS.get().getLibrary().getAudioFolder().getChildren().contains(myMusicFolder)
			) {
				myMusicFolder.setFakeParentId(PMS.get().getLibrary().getAudioFolder().getId());
				PMS.get().getLibrary().getAudioFolder().addChild(myMusicFolder, true, false);
				LOGGER.debug("adding My Music folder to 'Audio' folder");
			} else {
				LOGGER.debug("couldn't add 'My Music' folder because the media library is not initialized.");
			}
		}
	}

	@Override
	public InputStream getInputStream() {
		return null;
	}

	@Override
	public String getName() {
		return "root";
	}

	@Override
	public boolean isFolder() {
		return true;
	}

	@Override
	public long length() {
		return 0;
	}

	@Override
	public String getSystemName() {
		return getName();
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public void discoverChildren() {
		discoverChildren(true);
	}

	public void discoverChildren(boolean isAddGlobally) {
		if (isDiscovered()) {
			return;
		}

		if (isAddGlobally && configuration.isShowMediaLibraryFolder()) {
			MediaLibrary libraryRes = PMS.get().getLibrary();
			if (libraryRes.isEnabled()) {
				addChild(libraryRes, true);
			}
		}

		if (configuration.getUseCache()) {
			List<Path> foldersMonitored = configuration.getMonitoredFolders();
			if (!foldersMonitored.isEmpty()) {
				File[] dirs = new File[foldersMonitored.size()];
				int i = 0;
				for (Path folderMonitored : foldersMonitored) {
					dirs[i] = new File(folderMonitored.toAbsolutePath().toString().replaceAll("&comma;", ","));
					i++;
				}
				mon = new MediaMonitor(dirs);
			}
		}

		if (isAddGlobally) {
			if (
				configuration.getFolderLimit() &&
				getDefaultRenderer() != null &&
				getDefaultRenderer().isLimitFolders()
			) {
				lim = new FolderLimit();
				addChild(lim, true);
			}

			if (configuration.isDynamicPls()) {
				addChild(PMS.get().getDynamicPls(), true);
				if (!configuration.isHideSavedPlaylistFolder()) {
					File plsdir = new File(configuration.getDynamicPlsSavePath());
					addChild(new RealFile(plsdir, Messages.getString("SavedPlaylists")), true);
				}
			}
		}

		for (DLNAResource r : getConfiguredFolders()) {
			addChild(r, true, isAddGlobally);
		}

		/**
		 * Changes to monitored folders trigger a rescan
		 */
		if (PMS.getConfiguration().getUseCache()) {
			for (Path resource : configuration.getMonitoredFolders()) {
				File file = new File(resource.toString());
				if (file.exists()) {
					if (!file.isDirectory()) {
						LOGGER.trace("Skip adding a FileWatcher for non-folder \"{}\"", file);
					} else {
						LOGGER.trace("Creating FileWatcher for " + resource.toString());
						try {
							FileWatcher.add(new FileWatcher.Watch(resource.toString() + File.separator + "**", LIBRARY_RESCANNER));
						} catch (Exception e) {
							LOGGER.warn("File watcher access denied for directory {}", resource.toString());
						}
					}
				} else {
					LOGGER.trace("Skip adding a FileWatcher for non-existent \"{}\"", file);
				}
			}
		}

		for (DLNAResource r : getVirtualFolders()) {
			addChild(r);
		}

		if (isAddGlobally) {
			loadWebConf();

			switch (Platform.getOSType()) {
				case Platform.MAC:
					if (configuration.isShowIphotoLibrary()) {
						DLNAResource iPhotoRes = getiPhotoFolder();
						if (iPhotoRes != null) {
							addChild(iPhotoRes);
						}
					}
					if (configuration.isShowApertureLibrary()) {
						DLNAResource apertureRes = getApertureFolder();
						if (apertureRes != null) {
							addChild(apertureRes);
						}
					}
				case Platform.WINDOWS:
					if (configuration.isShowItunesLibrary()) {
						DLNAResource iTunesRes = getiTunesFolder();
						if (iTunesRes != null) {
							addChild(iTunesRes);
						}
					}
			}

			if (configuration.isShowServerSettingsFolder()) {
				addAdminFolder();
			}

			setDiscovered(true);
		}
	}

	public void setFolderLim(DLNAResource r) {
		if (lim != null) {
			lim.setStart(r);
		}
	}

	public void scan() {
		if (!configuration.getUseCache()) {
			throw new IllegalStateException("Can't scan when cache is disabled");
		}
		running = true;

		if (!isDiscovered()) {
			discoverChildren(false);
		}

		setDefaultRenderer(RendererConfiguration.getDefaultConf());
		LOGGER.debug("Starting scan of: {}", this.getName());
		if (running) {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					scan(this);
					// Running might have been set false during scan
					if (running) {
						MediaTableFiles.cleanup(connection);
					}
				}
			} finally {
				MediaDatabase.close(connection);
			}
		}

		GuiManager.setScanLibraryStatus(configuration.getUseCache(), false);
		GuiManager.setStatusLine(null);
	}

	public void stopScan() {
		running = false;
	}

	public void scan(DLNAResource resource) {
		if (running) {
			for (DLNAResource child : resource.getChildren()) {
				// wait until the realtime lock is released before starting
				PMS.REALTIME_LOCK.lock();
				PMS.REALTIME_LOCK.unlock();

				if (running && child.allowScan()) {
					child.setDefaultRenderer(resource.getDefaultRenderer());

					// Display and log which folder is being scanned
					String childName = child.getName();
					if (child instanceof RealFile) {
						LOGGER.debug("Scanning folder: " + childName);
						GuiManager.setStatusLine(Messages.getString("ScanningFolder") + " " + childName);
					}

					if (child.isDiscovered()) {
						child.refreshChildren();
					} else {
						if (child instanceof DVDISOFile || child instanceof DVDISOTitle) { // ugly hack
							child.syncResolve();
						}
						child.discoverChildren();
						child.analyzeChildren(-1, false);
						child.setDiscovered(true);
					}

					int count = child.getChildren().size();

					if (count == 0) {
						continue;
					}

					scan(child);
					child.getChildren().clear();
				} else if (!running) {
					break;
				}
			}
		} else {
			GuiManager.setScanLibraryStatus(configuration.getUseCache(), false);
			GuiManager.setStatusLine(null);
		}
	}

	@Nullable
	private static Path getWindowsKnownFolder(GUID guid) {
		try {
			String folderPath = Shell32Util.getKnownFolderPath(guid);
			if (isNotBlank(folderPath)) {
				Path folder = Paths.get(folderPath);
				try {
					FilePermissions permissions = new FilePermissions(folder);
					if (permissions.isBrowsable()) {
						return folder;
					}
					LOGGER.warn("Insufficient permissions to read default folder \"{}\"", guid);
				} catch (FileNotFoundException e) {
					LOGGER.debug("Default folder \"{}\" not found", folder);
				}
			}
		} catch (Win32Exception e) {
			LOGGER.debug("Default folder \"{}\" not found: {}", guid, e.getMessage());
		} catch (InvalidPathException e) {
			LOGGER.error("Unexpected error while resolving default Windows folder with GUID {}: {}", guid, e.getMessage());
			LOGGER.trace("", e);
		}
		return null;
	}

	@Nullable
	private static Path getWindowsFolder(@Nullable CSIDL csidl) {
		if (csidl == null) {
			return null;
		}
		try {
			String folderPath = Shell32Util.getFolderPath(csidl.getValue());
			if (isNotBlank(folderPath)) {
				Path folder = Paths.get(folderPath);
				FilePermissions permissions;
				try {
					permissions = new FilePermissions(folder);
					if (permissions.isBrowsable()) {
						return folder;
					}
					LOGGER.warn("Insufficient permissions to read default folder \"{}\"", csidl);
				} catch (FileNotFoundException e) {
					LOGGER.debug("Default folder \"{}\" not found", folder);
				}
			}
		} catch (Win32Exception e) {
			LOGGER.debug("Default folder \"{}\" not found: {}", csidl, e.getMessage());
		} catch (InvalidPathException e) {
			LOGGER.error("Unexpected error while resolving default Windows folder with id {}: {}", csidl, e.getMessage());
			LOGGER.trace("", e);
		}
		return null;
	}

	private static final Object DEFAULT_FOLDERS_LOCK = new Object();
	@GuardedBy("defaultFoldersLock")
	private static List<Path> defaultFolders = null;

	/**
	 * Enumerates and sets the default shared folders if none is configured.
	 *
	 * Note: This is a getter and a setter in one.
	 *
	 * @return The default shared folders.
	 */
	@Nonnull
	public static List<Path> getDefaultFolders() {
		synchronized (DEFAULT_FOLDERS_LOCK) {
			if (defaultFolders == null) {
				// Lazy initialization
				defaultFolders = new ArrayList<Path>();
				if (Platform.isWindows()) {
					Double version = BasicSystemUtils.instance.getWindowsVersion();
					if (version != null && version >= 6d) {
						ArrayList<GUID> knownFolders = new ArrayList<>(Arrays.asList(new GUID[]{
							KnownFolders.FOLDERID_Music,
							KnownFolders.FOLDERID_Pictures,
							KnownFolders.FOLDERID_Videos,
						}));
						for (GUID guid : knownFolders) {
							Path folder = getWindowsKnownFolder(guid);
							if (folder != null) {
								defaultFolders.add(folder);
							}
						}
					} else {
						CSIDL[] csidls = {
							CSIDL.CSIDL_MYMUSIC,
							CSIDL.CSIDL_MYPICTURES,
							CSIDL.CSIDL_MYVIDEO
						};
						for (CSIDL csidl : csidls) {
							Path folder = getWindowsFolder(csidl);
							if (folder != null) {
								defaultFolders.add(folder);
							}
						}
					}
				} else if (Platform.isMac()) {
					defaultFolders.addAll(NSFoundation.nsSearchPathForDirectoriesInDomains(
						NSSearchPathDirectory.NSMoviesDirectory,
						NSSearchPathDomainMask.NSAllDomainsMask,
						true
					));
					defaultFolders.addAll(NSFoundation.nsSearchPathForDirectoriesInDomains(
						NSSearchPathDirectory.NSMusicDirectory,
						NSSearchPathDomainMask.NSAllDomainsMask,
						true
					));
					defaultFolders.addAll(NSFoundation.nsSearchPathForDirectoriesInDomains(
						NSSearchPathDirectory.NSPicturesDirectory,
						NSSearchPathDomainMask.NSAllDomainsMask,
						true
					));
				} else {
					defaultFolders.add(Paths.get("").toAbsolutePath());
					String userHome = System.getProperty("user.home");
					if (isNotBlank(userHome)) {
						defaultFolders.add(Paths.get(userHome));
					}
					//TODO: (Nad) Implement xdg-user-dir for Linux when EnginesRegistration is merged:
					// xdg-user-dir DESKTOP
					// xdg-user-dir DOWNLOAD
					// xdg-user-dir PUBLICSHARE
					// xdg-user-dir MUSIC
					// xdg-user-dir PICTURES
					// xdg-user-dir VIDEOS
				}
				defaultFolders = Collections.unmodifiableList(defaultFolders);
			}
			return defaultFolders;
		}
	}

	@Nonnull
	private List<RealFile> getConfiguredFolders() {
		List<RealFile> resources = new ArrayList<>();
		List<Path> folders = configuration.getSharedFolders();
		List<Path> ignoredList = configuration.getIgnoredFolders();

		if (!ignoredList.isEmpty()) {
			for (Iterator<Path> iterator = folders.iterator(); iterator.hasNext();) {
				Path path = iterator.next();
				if (ignoredList.contains(path)) {
					iterator.remove();
				}
			}
		}

		for (Path folder : folders) {
			resources.add(new RealFile(folder.toFile()));
		}

		if (configuration.getSearchFolder()) {
			SearchFolder sf = new SearchFolder(Messages.getString("SearchDiscFolders"), new FileSearch(resources));
			addChild(sf);
		}

		return resources;
	}

	private static List<DLNAResource> getVirtualFolders() {
		List<DLNAResource> res = new ArrayList<>();
		List<MapFileConfiguration> mapFileConfs = MapFileConfiguration.parseVirtualFolders();

		if (mapFileConfs != null) {
			for (MapFileConfiguration f : mapFileConfs) {
				res.add(new MapFile(f));
			}
		}

		return res;
	}

	/**
	 * Removes all web folders, re-parses the web config file, and adds a
	 * file watcher for the file.
	 */
	public synchronized void loadWebConf() {
		Integer currentlySelectedPosition = -1;

		if (SharedContentTab.webContentList != null) {
			currentlySelectedPosition = SharedContentTab.webContentList.getSelectedRow();
		}

		for (DLNAResource d : webFolders) {
			getChildren().remove(d);
		}
		webFolders.clear();
		String webConfPath = configuration.getWebConfPath();
		File webConf = new File(webConfPath);
		if (!webConf.exists()) {
			configuration.writeWebConfigurationFile();
		}
		if (
			webConf.exists() &&
			configuration.getExternalNetwork() &&
			(
				SharedContentTab.lastWebContentUpdate == 1L ||
				SharedContentTab.lastWebContentUpdate < (System.currentTimeMillis() - 2000)
			)
		) {
			/**
			 * If the GUI last updated less than 2 seconds ago, chances are good
			 * that this method was triggered by changes in the GUI, which means
			 * we can skip updating the GUI here (avoiding the peakaboo effect)
			 */
			LOGGER.trace("The last web content update via GUI was more than 2 seconds ago, refreshing");
			parseWebConf(webConf, currentlySelectedPosition);
			FileWatcher.add(new FileWatcher.Watch(webConf.getPath(), ROOT_WATCHER, this, RELOAD_WEB_CONF));
		}
		setLastModified(1);
	}

	/**
	 * This parses the web config and populates the virtual Web folder.
	 *
	 * @param webConf
	 */
	private synchronized void parseWebConf(File webConf, Integer currentlySelectedPosition) {
		try {
			try (LineNumberReader br = new LineNumberReader(new InputStreamReader(new FileInputStream(webConf), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					line = line.trim();

					if (line.length() > 0 && !line.startsWith("#") && line.indexOf('=') > -1) {
						String key = line.substring(0, line.indexOf('='));
						String value = line.substring(line.indexOf('=') + 1);
						String[] keys = parseFeedKey(key);
						String sourceType = keys[0];
						String folderName = keys[1] == null ? null : keys[1];

						try {
							if (
								sourceType.equals("imagefeed") ||
								sourceType.equals("audiofeed") ||
								sourceType.equals("videofeed") ||
								sourceType.equals("audiostream") ||
								sourceType.equals("videostream")
							) {
								String[] values = parseFeedValue(value);
								String uri = values[0];
								DLNAResource parent = null;

								if (folderName != null) {
									StringTokenizer st = new StringTokenizer(folderName, ",");
									DLNAResource currentRoot = this;

									while (st.hasMoreTokens()) {
										String folder = st.nextToken();
										parent = currentRoot.searchByName(folder);

										if (parent == null) {
											parent = new VirtualFolder(folder, "");
											if (currentRoot == this) {
												// parent is a top-level web folder
												webFolders.add(parent);
											}
											currentRoot.addChild(parent);
										}

										currentRoot = parent;
									}
								}

								if (parent == null) {
									parent = this;
								}

								// Handle web playlists
								if (sourceType.endsWith("stream")) {
									int type = sourceType.startsWith("audio") ? Format.AUDIO : Format.VIDEO;
									DLNAResource playlist = PlaylistFolder.getPlaylist(uri, values[1], type);
									if (playlist != null) {
										parent.addChild(playlist);
										continue;
									}
								}

								String optionalStreamThumbnail = values.length > 2 ? values[2] : null;

								switch (sourceType) {
									case "imagefeed":
										parent.addChild(new ImagesFeed(uri));
										break;
									case "videofeed":
										// Convert YouTube channel URIs to their feed URIs
										if (uri.contains("youtube.com/channel/")) {
											uri = uri.replaceAll("youtube.com/channel/", "youtube.com/feeds/videos.xml?channel_id=");
										}

										parent.addChild(new VideosFeed(uri));
										break;
									case "audiofeed":
										parent.addChild(new AudiosFeed(uri));
										break;
									case "audiostream":
										parent.addChild(new WebAudioStream(uri, values[1], optionalStreamThumbnail));
										break;
									case "videostream":
										parent.addChild(new WebVideoStream(uri, values[1], optionalStreamThumbnail));
										break;
									default:
										break;
								}
							}
						} catch (ArrayIndexOutOfBoundsException e) {
							// catch exception here and go with parsing
							LOGGER.info("Error at line " + br.getLineNumber() + " of WEB.conf: " + e.getMessage());
							LOGGER.debug(null, e);
						}
					}
				}
			}
		} catch (FileNotFoundException e) {
			LOGGER.debug("Can't read web configuration file {}", e.getMessage());
		} catch (IOException e) {
			LOGGER.warn("Unexpected error in WEB.conf: " + e.getMessage());
			LOGGER.debug("", e);
		} finally {
			if (SharedContentTab.webContentList != null) {
				SharedContentTab.setWebContentGUIFromWebConfFile(webConf, currentlySelectedPosition);
			}
		}
	}

	/**
	 * Splits the first part of a WEB.conf spec into a pair of Strings
	 * representing the resource type and its DLNA folder.
	 *
	 * @param spec (String) to be split
	 * @return Array of (String) that represents the tokenized entry.
	 */
	public static String[] parseFeedKey(String spec) {
		String[] pair = StringUtils.split(spec, ".", 2);

		if (pair == null || pair.length < 2) {
			pair = new String[2];
		}

		if (pair[0] == null) {
			pair[0] = "";
		}

		return pair;
	}

	/**
	 * Splits the second part of a WEB.conf spec into a triple of Strings
	 * representing the DLNA path, resource URI, optional thumbnail URI
	 * and name.
	 *
	 * @param spec (String) to be split
	 * @return Array of (String) that represents the tokenized entry.
	 */
	public static String[] parseFeedValue(String spec) {
		return spec.split(",");
	}

	/**
	 * Creates, populates and returns a virtual folder mirroring the
	 * contents of the system's iPhoto folder.
	 * Mac OS X only.
	 *
	 * @return iPhotoVirtualFolder the populated <code>VirtualFolder</code>, or null if one couldn't be created.
	 */
	private static DLNAResource getiPhotoFolder() {
		VirtualFolder iPhotoVirtualFolder = null;

		if (Platform.isMac()) {
			LOGGER.debug("Adding iPhoto folder");
			Process process = null;
			try {
				// This command will show the XML files for recently opened iPhoto databases
				process = Runtime.getRuntime().exec("defaults read com.apple.iApps iPhotoRecentDatabases");
			} catch (IOException e1) {
				LOGGER.error("Something went wrong with the iPhoto Library scan: ", e1);
				return null;
			}

			try (InputStream inputStream = process.getInputStream()) {
				List<String> lines = IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
				LOGGER.debug("iPhotoRecentDatabases: {}", lines);

				if (lines.size() >= 2) {
					// we want the 2nd line
					String line = lines.get(1);

					// Remove extra spaces
					line = line.trim();

					// Remove quotes
					line = line.substring(1, line.length() - 1);

					URI uri = new URI(line);
					URL url = uri.toURL();
					File file = FileUtils.toFile(url);
					LOGGER.debug("Resolved URL to file: {} -> {}", url, file.getAbsolutePath());

					// Load the properties XML file.
					Map<String, Object> iPhotoLib = Plist.load(file);

					// The list of all photos
					Map<?, ?> photoList = (Map<?, ?>) iPhotoLib.get("Master Image List");

					// The list of events (rolls)
					List<Map<?, ?>> listOfRolls = (List<Map<?, ?>>) iPhotoLib.get("List of Rolls");

					iPhotoVirtualFolder = new VirtualFolder("iPhoto Library", null);

					for (Map<?, ?> roll : listOfRolls) {
						Object rollName = roll.get("RollName");

						if (rollName != null) {
							VirtualFolder virtualFolder = new VirtualFolder(rollName.toString(), null);

							// List of photos in an event (roll)
							List<?> rollPhotos = (List<?>) roll.get("KeyList");

							for (Object photo : rollPhotos) {
								Map<?, ?> photoProperties = (Map<?, ?>) photoList.get(photo);

								if (photoProperties != null) {
									Object imagePath = photoProperties.get("ImagePath");

									if (imagePath != null) {
										RealFile realFile = new RealFile(new File(imagePath.toString()));
										virtualFolder.addChild(realFile);
									}
								}
							}

							iPhotoVirtualFolder.addChild(virtualFolder);
						}
					}
				} else {
					LOGGER.info("iPhoto folder not found");
				}
			} catch (XmlParseException | URISyntaxException | IOException e) {
				LOGGER.error("Something went wrong with the iPhoto Library scan: ", e);
			}
		}

		return iPhotoVirtualFolder;
	}

	/**
	 * Returns Aperture folder. Used by manageRoot, so it is usually used as
	 * a folder at the root folder. Only works when DMS is run on Mac OS X.
	 * TODO: Requirements for Aperture.
	 */
	private DLNAResource getApertureFolder() {
		VirtualFolder res = null;

		if (Platform.isMac()) {
			Process process = null;

			try {
				process = Runtime.getRuntime().exec("defaults read com.apple.iApps ApertureLibraries");
				try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
					// Every line entry is one aperture library. We want all of them as a dlna folder.
					String line;
					res = new VirtualFolder("Aperture libraries", null);

					while ((line = in.readLine()) != null) {
						if (line.startsWith("(") || line.startsWith(")")) {
							continue;
						}

						line = line.trim(); // remove extra spaces
						line = line.substring(1, line.lastIndexOf('"')); // remove quotes and spaces
						VirtualFolder apertureLibrary = createApertureDlnaLibrary(line);

						if (apertureLibrary != null) {
							res.addChild(apertureLibrary);
						}
					}
				}
			} catch (IOException | XmlParseException | URISyntaxException e) {
				LOGGER.error("Something went wrong with the aperture library scan: ", e);
			} finally {
				// Avoid zombie processes, or open stream failures
				if (process != null) {
					try {
						// The process seems to always finish, so we can wait for it.
						// If the result code is not read by parent. The process might turn into a zombie (they are real!)
						process.waitFor();
					} catch (InterruptedException e) {
						LOGGER.warn("Interrupted while waiting for stream for process");
					}

					try {
						process.getErrorStream().close();
					} catch (Exception e) {
						LOGGER.warn("Could not close process output stream: {}", e.getMessage());
						LOGGER.trace("", e);
					}

					try {
						process.getInputStream().close();
					} catch (Exception e) {
						LOGGER.warn("Could not close stream for output process", e);
					}

					try {
						process.getOutputStream().close();
					} catch (Exception e) {
						LOGGER.warn("Could not close stream for output process", e);
					}
				}
			}
		}

		return res;
	}

	private VirtualFolder createApertureDlnaLibrary(String url) throws UnsupportedEncodingException, MalformedURLException, XmlParseException, IOException, URISyntaxException {
		VirtualFolder res = null;

		if (url != null) {
			Map<String, Object> iPhotoLib;
			// every project is a album, too
			List<?> listOfAlbums;
			Map<?, ?> album;
			Map<?, ?> photoList;

			URI tURI = new URI(url);
			iPhotoLib = Plist.load(URLDecoder.decode(tURI.toURL().getFile(), System.getProperty("file.encoding"))); // loads the (nested) properties.
			photoList = (Map<?, ?>) iPhotoLib.get("Master Image List"); // the list of photos
			final Object mediaPath = iPhotoLib.get("Archive Path");
			String mediaName;

			if (mediaPath != null) {
				mediaName = mediaPath.toString();

				if (mediaName != null && mediaName.lastIndexOf('/') != -1 && mediaName.lastIndexOf(".aplibrary") != -1) {
					mediaName = mediaName.substring(mediaName.lastIndexOf('/'), mediaName.lastIndexOf(".aplibrary"));
				} else {
					mediaName = "unknown library";
				}
			} else {
				mediaName = "unknown library";
			}

			LOGGER.info("Going to parse aperture library: " + mediaName);
			res = new VirtualFolder(mediaName, null);
			listOfAlbums = (List<?>) iPhotoLib.get("List of Albums"); // the list of events (rolls)

			for (Object item : listOfAlbums) {
				album = (Map<?, ?>) item;

				if (album.get("Parent") == null) {
					VirtualFolder vAlbum = createApertureAlbum(photoList, album, listOfAlbums);
					res.addChild(vAlbum);
				}
			}
		} else {
			LOGGER.info("No Aperture library found.");
		}
		return res;
	}

	private VirtualFolder createApertureAlbum(
		Map<?, ?> photoList,
		Map<?, ?> album, List<?> listOfAlbums
	) {

		List<?> albumPhotos;
		int albumId = (Integer) album.get("AlbumId");
		VirtualFolder vAlbum = new VirtualFolder(album.get("AlbumName").toString(), null);

		for (Object item : listOfAlbums) {
			Map<?, ?> sub = (Map<?, ?>) item;

			if (sub.get("Parent") != null) {
				// recursive album creation
				int parent = (Integer) sub.get("Parent");

				if (parent == albumId) {
					VirtualFolder subAlbum = createApertureAlbum(photoList, sub, listOfAlbums);
					vAlbum.addChild(subAlbum);
				}
			}
		}

		albumPhotos = (List<?>) album.get("KeyList");

		if (albumPhotos == null) {
			return vAlbum;
		}

		boolean firstPhoto = true;

		for (Object photoKey : albumPhotos) {
			Map<?, ?> photo = (Map<?, ?>) photoList.get(photoKey);

			if (firstPhoto) {
				Object x = photoList.get("ThumbPath");

				if (x != null) {
					vAlbum.setThumbnail(x.toString());
				}

				firstPhoto = false;
			}

			RealFile file = new RealFile(new File(photo.get("ImagePath").toString()));
			vAlbum.addChild(file);
		}

		return vAlbum;
	}

	/**
	 * Returns the iTunes XML file. This file has all the information of the
	 * iTunes database. The methods used in this function depends on whether
	 * DMS runs on Mac OS X or Windows.
	 *
	 * @return (String) Absolute path to the iTunes XML file.
	 * @throws Exception
	 */
	private String getiTunesFile() throws Exception {
		String line;
		String iTunesFile = null;
		String customUserPath = configuration.getItunesLibraryPath();

		if (!"".equals(customUserPath)) {
			return customUserPath;
		}

		if (Platform.isMac()) {
			// the second line should contain a quoted file URL e.g.:
			// "file://localhost/Users/MyUser/Music/iTunes/iTunes%20Music%20Library.xml"
			Process process = Runtime.getRuntime().exec("defaults read com.apple.iApps iTunesRecentDatabases");
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				// we want the 2nd line
				String line1 = in.readLine();
				String line2 = in.readLine();
				if (line1 != null && line2 != null) {
					line = line2.trim(); // remove extra spaces
					line = line.substring(1, line.length() - 1); // remove quotes and spaces
					URI tURI = new URI(line);
					iTunesFile = URLDecoder.decode(tURI.toURL().getFile(), "UTF8");
				}
			}
		} else if (Platform.isWindows()) {
			Process process = Runtime.getRuntime().exec("reg query \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders\" /v \"My Music\"");
			String location;
			//TODO The encoding of the output from reg query is unclear, this must be investigated further
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				location = null;
				while ((line = in.readLine()) != null) {
					final String lookFor = "REG_SZ";
					if (line.contains(lookFor)) {
						location = line.substring(line.indexOf(lookFor) + lookFor.length()).trim();
					}
				}
			}

			if (location != null) {
				// Add the iTunes folder to the end
				location += "\\iTunes\\iTunes Music Library.xml";
				iTunesFile = location;
			} else {
				LOGGER.info("Could not find the My Music folder");
			}
		}

		return iTunesFile;
	}

	private static boolean areNamesEqual(String aThis, String aThat) {
		Collator collator = Collator.getInstance(Locale.getDefault());
		collator.setStrength(Collator.PRIMARY);
		int comparison = collator.compare(aThis, aThat);

		return (comparison == 0);
	}

	/**
	 * Returns iTunes folder. Used by manageRoot, so it is usually used as a
	 * folder at the root folder. Only works on Mac OS X or Windows.
	 *
	 * The iTunes XML is parsed fully when this method is called, so it can
	 * take some time for larger (+1000 albums) databases.
	 *
	 * This method does not support genius playlists and does not provide a
	 * media library.
	 *
	 * @see RootFolder#getiTunesFile()
	 */
	private DLNAResource getiTunesFolder() {
		DLNAResource res = null;

		if (Platform.isMac() || Platform.isWindows()) {
			Map<String, Object> iTunesLib;
			List<?> playlists;
			Map<?, ?> playlist;
			Map<?, ?> tracks;
			Map<?, ?> track;
			List<?> playlistTracks;

			try {
				String iTunesFile = getiTunesFile();

				if (iTunesFile != null && (new File(iTunesFile)).exists()) {
					iTunesLib = Plist.load(URLDecoder.decode(iTunesFile, System.getProperty("file.encoding"))); // loads the (nested) properties.
					tracks = (Map<?, ?>) iTunesLib.get("Tracks"); // the list of tracks
					playlists = (List<?>) iTunesLib.get("Playlists"); // the list of Playlists
					res = new VirtualFolder("iTunes Library", null);

					VirtualFolder playlistsFolder = null;

					for (Object item : playlists) {
						playlist = (Map<?, ?>) item;

						if (playlist.containsKey("Visible") && playlist.get("Visible").equals(Boolean.FALSE)) {
							continue;
						}

						if (playlist.containsKey("Music") && playlist.get("Music").equals(Boolean.TRUE)) {
							// Create virtual folders for artists, albums and genres

							VirtualFolder musicFolder = new VirtualFolder(playlist.get("Name").toString(), null);
							res.addChild(musicFolder);

							VirtualFolder virtualFolderArtists = new VirtualFolder(Messages.getString("BrowseByArtist"), null);
							VirtualFolder virtualFolderAlbums = new VirtualFolder(Messages.getString("BrowseByAlbum"), null);
							VirtualFolder virtualFolderGenres = new VirtualFolder(Messages.getString("BrowseByGenre"), null);
							VirtualFolder virtualFolderAllTracks = new VirtualFolder(Messages.getString("AllAudioTracks"), null);
							playlistTracks = (List<?>) playlist.get("Playlist Items"); // list of tracks in a playlist

							String artistName;
							String albumName;
							String genreName;

							if (playlistTracks != null) {
								for (Object t : playlistTracks) {
									Map<?, ?> td = (Map<?, ?>) t;
									track = (Map<?, ?>) tracks.get(td.get("Track ID").toString());

									if (
										track != null &&
										track.get("Location") != null &&
										track.get("Location").toString().startsWith("file://")
									) {
										String name = Normalizer.normalize((String) track.get("Name"), Normalizer.Form.NFC);
										// remove dots from name to prevent media renderer from trimming
										name = name.replace('.', '-');

										if (track.containsKey("Protected") && track.get("Protected").equals(Boolean.TRUE)) {
											name = name + "-" + Messages.getString("Protected_lowercase");
										}

										boolean isCompilation = (track.containsKey("Compilation") && track.get("Compilation").equals(Boolean.TRUE));

										artistName = (String) track.get("Artist");
										if (isCompilation) {
											artistName = "Compilation";
										} else if (track.containsKey("Album Artist")) {
											artistName = (String) track.get("Album Artist");
										}
										albumName = (String) track.get("Album");
										genreName = (String) track.get("Genre");

										if (artistName == null) {
											artistName = "Unknown Artist";
										} else {
											artistName = Normalizer.normalize(artistName, Normalizer.Form.NFC);
										}

										if (albumName == null) {
											albumName = "Unknown Album";
										} else {
											albumName = Normalizer.normalize(albumName, Normalizer.Form.NFC);
										}

										if (genreName == null || "".equals(genreName.replaceAll("[^a-zA-Z]", ""))) {
											// This prevents us from adding blank or numerical genres
											genreName = "Unknown Genre";
										} else {
											genreName = Normalizer.normalize(genreName, Normalizer.Form.NFC);
										}

										// Replace &nbsp with space and then trim
										artistName = artistName.replace('\u0160', ' ').trim();
										albumName  = albumName.replace('\u0160', ' ').trim();
										genreName  = genreName.replace('\u0160', ' ').trim();

										URI tURI2 = new URI(track.get("Location").toString());
										File refFile = new File(URLDecoder.decode(tURI2.toURL().getFile(), "UTF-8"));
										RealFile file = new RealFile(refFile, name);

										// Put the track into the artist's album folder and the artist's "All tracks" folder
										VirtualFolder individualArtistFolder = null;
										VirtualFolder individualArtistAllTracksFolder;
										VirtualFolder individualArtistAlbumFolder = null;

										for (DLNAResource artist : virtualFolderArtists.getChildren()) {
											if (areNamesEqual(artist.getName(), artistName)) {
												individualArtistFolder = (VirtualFolder) artist;
												for (DLNAResource album : individualArtistFolder.getChildren()) {
													if (areNamesEqual(album.getName(), albumName)) {
														individualArtistAlbumFolder = (VirtualFolder) album;
													}
												}
												break;
											}
										}

										if (individualArtistFolder == null) {
											individualArtistFolder = new VirtualFolder(artistName, null);
											virtualFolderArtists.addChild(individualArtistFolder);
											individualArtistAllTracksFolder = new VirtualFolder(Messages.getString("AllAudioTracks"), null);
											individualArtistFolder.addChild(individualArtistAllTracksFolder);
										} else {
											individualArtistAllTracksFolder = (VirtualFolder) individualArtistFolder.getChildren().get(0);
										}

										if (individualArtistAlbumFolder == null) {
											individualArtistAlbumFolder = new VirtualFolder(albumName, null);
											individualArtistFolder.addChild(individualArtistAlbumFolder);
										}

										individualArtistAlbumFolder.addChild(file.clone());
										individualArtistAllTracksFolder.addChild(file);

										// Put the track into its album folder
										if (!isCompilation) {
											albumName += " - " + artistName;
										}

										VirtualFolder individualAlbumFolder = null;
										for (DLNAResource album : virtualFolderAlbums.getChildren()) {
											if (areNamesEqual(album.getName(), albumName)) {
												individualAlbumFolder = (VirtualFolder) album;
												break;
											}
										}
										if (individualAlbumFolder == null) {
											individualAlbumFolder = new VirtualFolder(albumName, null);
											virtualFolderAlbums.addChild(individualAlbumFolder);
										}
										individualAlbumFolder.addChild(file.clone());

										// Put the track into its genre folder
										VirtualFolder individualGenreFolder = null;
										for (DLNAResource genre : virtualFolderGenres.getChildren()) {
											if (areNamesEqual(genre.getName(), genreName)) {
												individualGenreFolder = (VirtualFolder) genre;
												break;
											}
										}
										if (individualGenreFolder == null) {
											individualGenreFolder = new VirtualFolder(genreName, null);
											virtualFolderGenres.addChild(individualGenreFolder);
										}
										individualGenreFolder.addChild(file.clone());

										// Put the track into the global "All tracks" folder
										virtualFolderAllTracks.addChild(file.clone());
									}
								}
							}

							musicFolder.addChild(virtualFolderArtists);
							musicFolder.addChild(virtualFolderAlbums);
							musicFolder.addChild(virtualFolderGenres);
							musicFolder.addChild(virtualFolderAllTracks);

							// Sort the virtual folders alphabetically
							Collections.sort(virtualFolderArtists.getChildren(), new Comparator<DLNAResource>() {
								@Override
								public int compare(DLNAResource o1, DLNAResource o2) {
									VirtualFolder a = (VirtualFolder) o1;
									VirtualFolder b = (VirtualFolder) o2;
									return a.getName().compareToIgnoreCase(b.getName());
								}
							});

							Collections.sort(virtualFolderAlbums.getChildren(), new Comparator<DLNAResource>() {
								@Override
								public int compare(DLNAResource o1, DLNAResource o2) {
									VirtualFolder a = (VirtualFolder) o1;
									VirtualFolder b = (VirtualFolder) o2;
									return a.getName().compareToIgnoreCase(b.getName());
								}
							});

							Collections.sort(virtualFolderGenres.getChildren(), new Comparator<DLNAResource>() {
								@Override
								public int compare(DLNAResource o1, DLNAResource o2) {
									VirtualFolder a = (VirtualFolder) o1;
									VirtualFolder b = (VirtualFolder) o2;
									return a.getName().compareToIgnoreCase(b.getName());
								}
							});
						} else {
							// Add all playlists
							VirtualFolder pf = new VirtualFolder(playlist.get("Name").toString(), null);
							playlistTracks = (List<?>) playlist.get("Playlist Items"); // list of tracks in a playlist

							if (playlistTracks != null) {
								for (Object t : playlistTracks) {
									Map<?, ?> td = (Map<?, ?>) t;
									track = (Map<?, ?>) tracks.get(td.get("Track ID").toString());

									if (
										track != null &&
										track.get("Location") != null &&
										track.get("Location").toString().startsWith("file://")
									) {
										String name = Normalizer.normalize(track.get("Name").toString(), Normalizer.Form.NFC);
										// remove dots from name to prevent media renderer from trimming
										name = name.replace('.', '-');

										if (track.containsKey("Protected") && track.get("Protected").equals(Boolean.TRUE)) {
											name = name + "-" + Messages.getString("Protected_lowercase");
										}

										URI tURI2 = new URI(track.get("Location").toString());
										RealFile file = new RealFile(new File(URLDecoder.decode(tURI2.toURL().getFile(), "UTF-8")), name);
										pf.addChild(file);
									}
								}
							}

							int kind = playlist.containsKey("Distinguished Kind") ? ((Number) playlist.get("Distinguished Kind")).intValue() : -1;
							if (kind >= 0 && kind != 17 && kind != 19 && kind != 20) {
								// System folder, but not voice memos (17) and purchased items (19 & 20)
								res.addChild(pf);
							} else {
								// User playlist or playlist folder
								if (playlistsFolder == null) {
									playlistsFolder = new VirtualFolder("Playlists", null);
									res.addChild(playlistsFolder);
								}
								playlistsFolder.addChild(pf);
							}
						}
					}
				} else {
					LOGGER.info("Could not find the iTunes file");
				}
			} catch (Exception e) {
				LOGGER.error("Something went wrong with the iTunes Library scan: ", e);
			}
		}

		return res;
	}

	private void addAdminFolder() {
		DLNAResource res = new VirtualFolder(Messages.getString("ServerSettings"), null);
		DLNAResource vsf = getVideoSettingsFolder();

		if (vsf != null) {
			res.addChild(vsf);
		}

		if (configuration.getScriptDir() != null) {
			final File scriptDir = new File(configuration.getScriptDir());

			if (scriptDir.exists()) {
				res.addChild(new VirtualFolder(Messages.getString("Scripts"), null) {
					@Override
					public void discoverChildren() {
						File[] files = scriptDir.listFiles();
						if (files != null) {
							for (File file : files) {
								String name = file.getName().replaceAll("_", " ");
								int pos = name.lastIndexOf('.');

								if (pos != -1) {
									name = name.substring(0, pos);
								}

								final File f = file;

								addChild(new VirtualVideoAction(name, true) {
									@Override
									public boolean enable() {
										try {
											ProcessBuilder pb = new ProcessBuilder(f.getAbsolutePath());
											pb.redirectErrorStream(true);
											Process pid = pb.start();
											// consume the error and output process streams
											StreamGobbler.consume(pid.getInputStream());
											pid.waitFor();
										} catch (IOException | InterruptedException e) {
										}

										return true;
									}
								});
							}
						}
					}
				});
			}
		}

		// Resume file management
		if (configuration.isResumeEnabled()) {
			res.addChild(new VirtualFolder(Messages.getString("ManageResumeFiles"), null) {
				@Override
				public void discoverChildren() {
					final File[] files = ResumeObj.resumeFiles();
					addChild(new VirtualVideoAction(Messages.getString("DeleteAllFiles"), true) {
						@Override
						public boolean enable() {
							for (File f : files) {
								f.delete();
							}
							getParent().getChildren().remove(this);
							return false;
						}
					});
					for (final File f : files) {
						String name = FileUtil.getFileNameWithoutExtension(f.getName());
						name = name.replaceAll(ResumeObj.CLEAN_REG, "");
						addChild(new VirtualVideoAction(name, false) {
							@Override
							public boolean enable() {
								f.delete();
								getParent().getChildren().remove(this);
								return false;
							}
						});
					}
				}
			});
		}

		// Reboot DMS
		res.addChild(new VirtualVideoAction(Messages.getString("RebootUms"), true) {
			@Override
			public boolean enable() {
				ProcessUtil.reboot();
				// Reboot failed if we get here
				return false;
			}
		});

		addChild(res);
	}

	/**
	 * Returns Video Settings folder. Used by manageRoot, so it is usually
	 * used as a folder at the root folder. Child objects are created when
	 * this folder is created.
	 */
	private DLNAResource getVideoSettingsFolder() {
		DLNAResource res = null;

		if (configuration.isShowServerSettingsFolder()) {
			res = new VirtualFolder(Messages.getString("VideoSettings_FolderName"), null);
			VirtualFolder vfSub = new VirtualFolder(Messages.getString("Subtitles"), null);
			res.addChild(vfSub);

			if (configuration.useCode() && !PMS.get().masterCodeValid() &&
				StringUtils.isNotEmpty(PMS.get().codeDb().lookup(CodeDb.MASTER))) {
				// if the master code is valid we don't add this
				VirtualVideoAction vva = new VirtualVideoAction("MasterCode", true) {
					@Override
					public boolean enable() {
						CodeEnter ce = (CodeEnter) getParent();
						if (ce.validCode(this)) {
							PMS.get().setMasterCode(ce);
							return true;
						}
						return false;
					}
				};
				CodeEnter ce1 = new CodeEnter(vva);
				ce1.setCode(CodeDb.MASTER);
				res.addChild(ce1);
			}

			res.addChild(new VirtualVideoAction(Messages.getString("AvSyncAlternativeMethod"), configuration.isMencoderNoOutOfSync()) {
				@Override
				public boolean enable() {
					configuration.setMencoderNoOutOfSync(!configuration.isMencoderNoOutOfSync());
					return configuration.isMencoderNoOutOfSync();
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("DefaultH264RemuxMencoder"), configuration.isMencoderMuxWhenCompatible()) {
				@Override
				public boolean enable() {
					configuration.setMencoderMuxWhenCompatible(!configuration.isMencoderMuxWhenCompatible());

					return configuration.isMencoderMuxWhenCompatible();
				}
			});

			res.addChild(new VirtualVideoAction("  !!-- Fix 23.976/25fps A/V Mismatch --!!", configuration.isFix25FPSAvMismatch()) {
				@Override
				public boolean enable() {
					configuration.setMencoderForceFps(!configuration.isFix25FPSAvMismatch());
					configuration.setFix25FPSAvMismatch(!configuration.isFix25FPSAvMismatch());
					return configuration.isFix25FPSAvMismatch();
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("DeinterlaceFilter"), configuration.isMencoderYadif()) {
				@Override
				public boolean enable() {
					configuration.setMencoderYadif(!configuration.isMencoderYadif());

					return configuration.isMencoderYadif();
				}
			});

			vfSub.addChild(new VirtualVideoAction(Messages.getString("DisableSubtitles"), configuration.isDisableSubtitles()) {
				@Override
				public boolean enable() {
					boolean oldValue = configuration.isDisableSubtitles();
					boolean newValue = !oldValue;
					configuration.setDisableSubtitles(newValue);
					return newValue;
				}
			});

			vfSub.addChild(new VirtualVideoAction(Messages.getString("AutomaticallyLoadSrtSubtitles"), configuration.isAutoloadExternalSubtitles()) {
				@Override
				public boolean enable() {
					boolean oldValue = configuration.isAutoloadExternalSubtitles();
					boolean newValue = !oldValue;
					configuration.setAutoloadExternalSubtitles(newValue);
					return newValue;
				}
			});

			vfSub.addChild(new VirtualVideoAction(Messages.getString("UseEmbeddedStyle"), configuration.isUseEmbeddedSubtitlesStyle()) {
				@Override
				public boolean enable() {
					boolean oldValue = configuration.isUseEmbeddedSubtitlesStyle();
					boolean newValue = !oldValue;
					configuration.setUseEmbeddedSubtitlesStyle(newValue);
					return newValue;
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("SkipLoopFilterDeblocking"), configuration.getSkipLoopFilterEnabled()) {
				@Override
				public boolean enable() {
					configuration.setSkipLoopFilterEnabled(!configuration.getSkipLoopFilterEnabled());
					return configuration.getSkipLoopFilterEnabled();
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("KeepDtsTracks"), configuration.isAudioEmbedDtsInPcm()) {
				@Override
				public boolean enable() {
					configuration.setAudioEmbedDtsInPcm(!configuration.isAudioEmbedDtsInPcm());
					return configuration.isAudioEmbedDtsInPcm();
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("SaveConfiguration"), true) {
				@Override
				public boolean enable() {
					try {
						configuration.save();
					} catch (ConfigurationException e) {
						LOGGER.debug("Caught exception", e);
					}
					return true;
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("RestartServer"), true) {
				@Override
				public boolean enable() {
					PMS.get().resetMediaServer();
					return true;
				}
			});

			res.addChild(new VirtualVideoAction(Messages.getString("ShowLiveSubtitlesFolder"), configuration.isShowLiveSubtitlesFolder()) {
				@Override
				public boolean enable() {
					configuration.setShowLiveSubtitlesFolder(configuration.isShowLiveSubtitlesFolder());
					return configuration.isShowLiveSubtitlesFolder();
				}
			});
		}

		return res;
	}

	@Override
	public String toString() {
		return "RootFolder[" + getChildren() + "]";
	}

	public void reset() {
		setDiscovered(false);
	}

	public void stopPlaying(DLNAResource res) {
		if (mon != null) {
			mon.stopped(res);
		}
	}

	// Automatic reloading

	public final static int RELOAD_WEB_CONF = 1;

	public static final FileWatcher.Listener ROOT_WATCHER = new FileWatcher.Listener() {
		@Override
		public void notify(String filename, String event, FileWatcher.Watch watch, boolean isDir) {
			RootFolder r = (RootFolder) watch.getItem();
			if (r != null) {
				if (watch.flag == RELOAD_WEB_CONF) {
					r.loadWebConf();
				}
			}
		}
	};

	/**
	 * Adds and removes files from the database when they are created or
	 * deleted on the hard drive.
	 */
	public static final FileWatcher.Listener LIBRARY_RESCANNER = new FileWatcher.Listener() {
		@Override
		public void notify(String filename, String event, FileWatcher.Watch watch, boolean isDir) {
			if (("ENTRY_DELETE".equals(event) || "ENTRY_CREATE".equals(event)) && PMS.getConfiguration().getUseCache()) {
				Connection connection = null;
				try {
					connection = MediaDatabase.getConnectionIfAvailable();
					if (connection != null) {
						/**
						 * If a new directory is created with files, the listener may not
						 * give us information about those new files, as it wasn't listening
						 * when they were created, so make sure we parse them.
						 */
						if (isDir) {
							if ("ENTRY_CREATE".equals(event)) {
								LOGGER.trace("Folder {} was created on the hard drive", filename);

								File[] files = new File(filename).listFiles();
								if (files != null) {
									LOGGER.trace("Crawling {}", filename);
									for (File file : files) {
										if (file.isFile()) {
											LOGGER.trace("File {} found in {}", file.getName(), filename);
											parseFileForDatabase(file);
										}
									}
								} else {
									LOGGER.trace("Folder {} is empty", filename);
								}
							} else if ("ENTRY_DELETE".equals(event)) {
								LOGGER.trace("Folder {} was deleted or moved on the hard drive, removing all files within it from the database", filename);
								MediaTableFiles.removeMediaEntriesInFolder(connection, filename);
								bumpSystemUpdateId();
							}
						} else {
							if ("ENTRY_DELETE".equals(event)) {
								LOGGER.trace("File {} was deleted or moved on the hard drive, removing it from the database", filename);
								MediaTableFiles.removeMediaEntry(connection, filename, true);
								bumpSystemUpdateId();
							} else if ("ENTRY_CREATE".equals(event)) {
								LOGGER.trace("File {} was created on the hard drive", filename);
								File file = new File(filename);
								parseFileForDatabase(file);
							}
						}
					}
				} finally {
					MediaDatabase.close(connection);
				}
			}
		}
	};

	/**
	 * Parses a file so it gets parsed and added to the database
	 * along the way.
	 *
	 * @param file the file to parse
	 */
	public static final void parseFileForDatabase(File file) {
		if (!MapFile.isPotentialMediaFile(file.getAbsolutePath())) {
			LOGGER.trace("Not parsing file that can't be media");
			return;
		}

		// TODO: Can this use UnattachedFolder and add instead?
		RealFile rf = new RealFile(file);
		rf.setParent(rf);
		rf.getParent().setDefaultRenderer(RendererConfiguration.getDefaultConf());
		rf.resolveFormat();
		rf.syncResolve();

		if (rf.isValid()) {
			LOGGER.info("New file {} was detected and added to the Media Library", file.getName());
			bumpSystemUpdateId();

			/*
			 * Something about this process causes Java to hold onto the
			 * file, which prevents things happening to it on the filesystem
			 * until the garbage collector runs.
			 * Some sources say it is a symptom of the nio namespace itself
			 * and the fix is to use older syntax, and others say other things,
			 * but until we have a real fix for it we ask Java to collect the
			 * garbage. It might not do it, but usually it does, which is better
			 * than what we had before.
			 */
			System.gc();
			System.runFinalization();
		} else {
			LOGGER.trace("File {} was not recognized as valid media so was not added to the database", file.getName());
		}
	}

	/**
	 * Starts partial rescan
	 *
	 * @param filename This is the partial root of the scan. If a file is given,
	 *                 the parent folder will be scanned.
	 */
	public static void rescanLibraryFileOrFolder(String filename) {
		if (
			hasSameBasePath(PMS.getConfiguration().getSharedFolders(), filename) ||
			hasSameBasePath(RootFolder.getDefaultFolders(), filename)
		) {
			LOGGER.debug("rescanning file or folder : " + filename);

			if (!LibraryScanner.isScanLibraryRunning()) {
				Runnable scan = () -> {
					File file = new File(filename);
					if (file.isFile()) {
						file = file.getParentFile();
					}
					DLNAResource dir = new RealFile(file);
					dir.setDefaultRenderer(RendererConfiguration.getDefaultConf());
					dir.doRefreshChildren();
					PMS.get().getRootFolder(null).scan(dir);
				};
				Thread scanThread = new Thread(scan, "rescanLibraryFileOrFolder");
				scanThread.start();
			}
		} else {
			LOGGER.warn("given file or folder doesn't share same base path as this server : " + filename);
		}
	}

	public static boolean hasSameBasePath(List<Path> dirs, String content) {
		for (Path path : dirs) {
			if (content.startsWith(path.toString())) {
				return true;
			}
		}
		return false;
	}
}
