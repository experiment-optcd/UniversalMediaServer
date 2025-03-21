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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.jna.Platform;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableCoverArtArchive;
import net.pms.database.MediaTableFiles;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;
import net.pms.io.BasicSystemUtils;
import net.pms.util.FileUtil;
import net.pms.util.ProcessUtil;

public class RealFile extends MapFile {
	private static final Logger LOGGER = LoggerFactory.getLogger(RealFile.class);

	public RealFile(File file) {
		addFileToConfFiles(file);
		setLastModified(file.lastModified());
	}

	public RealFile(File file, String name) {
		addFileToConfFiles(file);
		getConf().setName(name);
		setLastModified(file.lastModified());
	}

	public RealFile(File file, boolean isEpisodeWithinSeasonFolder) {
		addFileToConfFiles(file);
		setLastModified(file.lastModified());
		setIsEpisodeWithinSeasonFolder(isEpisodeWithinSeasonFolder);
	}

	public RealFile(File file, boolean isEpisodeWithinSeasonFolder, boolean isEpisodeWithinTVSeriesFolder) {
		getConf().getFiles().add(file);
		setLastModified(file.lastModified());
		setIsEpisodeWithinSeasonFolder(isEpisodeWithinSeasonFolder);
		setIsEpisodeWithinTVSeriesFolder(isEpisodeWithinTVSeriesFolder);
	}

	/**
	 * Add the file to MapFileConfiguration->Files.
	 *
	 * @param file The file to add.
	 */
	private void addFileToConfFiles(File file) {
		if (configuration.isUseSymlinksTargetFile() && FileUtil.isSymbolicLink(file)) {
			getConf().getFiles().add(FileUtil.getRealFile(file));
		} else {
			getConf().getFiles().add(file);
		}
	}

	@Override
	// FIXME: this is called repeatedly for invalid files e.g. files MediaInfo can't parse
	public boolean isValid() {
		File file = this.getFile();
		if (!file.isDirectory()) {
			resolveFormat();
		}

		if (getType() == Format.SUBTITLE) {
			// Don't add subtitles as separate resources
			getConf().getFiles().remove(file);
			return false;
		}

		boolean valid = file.exists() && (getFormat() != null || file.isDirectory());
		if (valid && getParent() != null && getParent().getDefaultRenderer() != null && getParent().getDefaultRenderer().isUseMediaInfo()) {
			// we need to resolve the DLNA resource now
			run();

			// Given that here getFormat() has already matched some (possibly plugin-defined) format:
			//    Format.UNKNOWN + bad parse = inconclusive
			//    known types    + bad parse = bad/encrypted file
			if (this.getType() != Format.UNKNOWN && getMedia() != null && (getMedia().isEncrypted() || getMedia().getContainer() == null || getMedia().getContainer().equals(DLNAMediaLang.UND))) {
				if (getMedia().isEncrypted()) {
					valid = false;
					LOGGER.info("The file {} is encrypted. It will be hidden", file.getAbsolutePath());
				} else {
					// problematic media not parsed by MediaInfo try to parse it in a different way by ffmpeg, AudioFileIO or ImagesUtil
					// this is a quick fix for the MediaInfo insufficient parsing method
					getMedia().setMediaparsed(false);
					InputFile inputfile = new InputFile();
					inputfile.setFile(file);
					getMedia().setContainer(null);
					getMedia().parse(inputfile, getFormat(), getType(), false, false, null);
					if (getMedia().getContainer() == null) {
						valid = false;
						LOGGER.info("The file {} could not be parsed. It will be hidden", file.getAbsolutePath());
					}
				}

				if (!valid) {
					getConf().getFiles().remove(file);
				}
			}

			// XXX isMediaInfoThumbnailGeneration is only true for the "default renderer"
			if (getParent().getDefaultRenderer().isMediaInfoThumbnailGeneration()) {
				checkThumbnail();
			}
		} else if (this.getType() == Format.UNKNOWN && !this.isFolder()) {
			getConf().getFiles().remove(file);
			return false;
		}

		return valid;
	}

	@Override
	public InputStream getInputStream() {
		try {
			return new FileInputStream(getFile());
		} catch (FileNotFoundException e) {
			LOGGER.debug("File not found: {}", getFile().getAbsolutePath());
		}

		return null;
	}

	@Override
	public long length() {
		if (getPlayer() != null && getPlayer().type() != Format.IMAGE) {
			return DLNAMediaInfo.TRANS_SIZE;
		} else if (getMedia() != null && getMedia().isMediaparsed()) {
			return getMedia().getSize();
		}
		return getFile().length();
	}

	@Override
	public boolean isFolder() {
		return getFile().isDirectory();
	}

	public File getFile() {
		if (getConf().getFiles().isEmpty()) {
			return null;
		}

		return getConf().getFiles().get(0);
	}

	@Override
	public String getName() {
		if (this.getConf().getName() == null) {
			String name = null;
			File file = getFile();

			// this probably happened because the file was removed after it could not be parsed by isValid()
			if (file == null) {
				return null;
			}

			if (file.getName().trim().isEmpty()) {
				if (Platform.isWindows()) {
					name = BasicSystemUtils.instance.getDiskLabel(file);
				}
				if (name != null && name.length() > 0) {
					name = file.getAbsolutePath().substring(0, 1) + ":\\ [" + name + "]";
				} else {
					name = file.getAbsolutePath().substring(0, 1);
				}
			} else {
				name = file.getName();
			}
			this.getConf().setName(name);
		}
		return this.getConf().getName().replaceAll("_imdb([^_]+)_", "");
	}

	@Override
	protected void resolveFormat() {
		if (getFormat() == null) {
			setFormat(FormatFactory.getAssociatedFormat(getFile().getAbsolutePath()));
		}

		super.resolveFormat();
	}

	@Override
	public String getSystemName() {
		return ProcessUtil.getShortFileNameIfWideChars(getFile().getAbsolutePath());
	}

	@Override
	public synchronized void resolve() {
		File file = getFile();
		if (file.isFile() && (getMedia() == null || !getMedia().isMediaparsed())) {
			boolean found = false;
			InputFile input = new InputFile();
			input.setFile(file);
			String fileName = file.getAbsolutePath();
			if (getSplitTrack() > 0) {
				fileName += "#SplitTrack" + getSplitTrack();
			}
			Connection connection = null;
			try {
				if (configuration.getUseCache()) {
					connection = MediaDatabase.getConnectionIfAvailable();
					if (connection != null) {
						connection.setAutoCommit(false);
						DLNAMediaInfo media;
						try {
							media = MediaTableFiles.getData(connection, fileName, file.lastModified());

							setExternalSubtitlesParsed();
							if (media != null) {
								setMedia(media);
								if (configuration.isDisableSubtitles() && getMedia().isVideo()) {
									// clean subtitles obtained from the database when they are disabled but keep them in the database for the future use
									getMedia().setSubtitlesTracks(new ArrayList<>());
									resetSubtitlesStatus();
								}

								getMedia().postParse(getType(), input);
								found = true;
							}
						} catch (IOException | SQLException e) {
							LOGGER.debug("Error while getting cached information about {}, reparsing information: {}", getName(), e.getMessage());
							LOGGER.trace("", e);
						}
					}
				}

				if (!found) {
					if (getMedia() == null) {
						setMedia(new DLNAMediaInfo());
					}

					if (getFormat() != null) {
						getFormat().parse(getMedia(), input, getType(), getParent().getDefaultRenderer());
					} else {
						// Don't think that will ever happen
						getMedia().parse(input, getFormat(), getType(), false, isResume(), getParent().getDefaultRenderer());
					}

					if (connection != null && getMedia().isMediaparsed() && !getMedia().isParsing() && getConf().isAddToMediaLibrary()) {
						try {
							/*
							 * Even though subtitles will be resolved later in
							 * DLNAResource.syncResolve, we must make sure that
							 * they are resolved before insertion into the
							 * database
							 */
							if (getMedia() != null && getMedia().isVideo()) {
								registerExternalSubtitles(false);
							}
							MediaTableFiles.insertOrUpdateData(connection, fileName, file.lastModified(), getType(), getMedia());
						} catch (SQLException e) {
							LOGGER.error(
								"Database error while trying to add parsed information for \"{}\" to the cache: {}",
								fileName,
								e.getMessage());
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace("SQL error code: {}", e.getErrorCode());
								if (
									e.getCause() instanceof SQLException &&
									((SQLException) e.getCause()).getErrorCode() != e.getErrorCode()
								) {
									LOGGER.trace("Cause SQL error code: {}", ((SQLException) e.getCause()).getErrorCode());
								}
								LOGGER.trace("", e);
							}
						}
					}
				}
				if (getMedia() != null && getMedia().isSLS()) {
					setFormat(getMedia().getAudioVariantFormat());
				}
			} catch (Exception e) {
				LOGGER.error("Error in RealFile.resolve: {}", e.getMessage());
				LOGGER.trace("", e);
			} finally {
				try {
					if (connection != null) {
						connection.commit();
						connection.setAutoCommit(true);
					}
				} catch (SQLException e) {
					LOGGER.error("Error in commit in RealFile.resolve: {}", e.getMessage());
					LOGGER.trace("", e);
				}
				MediaDatabase.close(connection);
			}
		}
	}

	@Override
	public DLNAThumbnailInputStream getThumbnailInputStream() throws IOException {
		File file = getFile();
		File cachedThumbnail = null;
		MediaType mediaType = getMedia() != null ? getMedia().getMediaType() : MediaType.UNKNOWN;

		if (mediaType == MediaType.AUDIO || mediaType == MediaType.VIDEO) {
			String alternativeFolder = configuration.getAlternateThumbFolder();
			ArrayList<File> folders = new ArrayList<>(2);
			if (file.getParentFile() != null) {
				folders.add(null);
			}
			if (isNotBlank(alternativeFolder)) {
				File thumbFolder = new File(alternativeFolder);
				if (thumbFolder.isDirectory() && thumbFolder.exists()) {
					folders.add(thumbFolder);
				}
			}

			for (File folder : folders) {
				File audioVideoFile = folder == null ? file : new File(folder, file.getName());
				HashSet<File> potentials = MapFile.getPotentialFileThumbnails(audioVideoFile, true);
				if (!potentials.isEmpty()) {
					// We have no rules for how to pick a particular one if there's multiple candidates
					cachedThumbnail = potentials.iterator().next();
					break;
				}
			}
			if (cachedThumbnail == null && mediaType == MediaType.AUDIO && getParent() != null && getParent() instanceof MapFile) {
				cachedThumbnail = ((MapFile) getParent()).getPotentialCover();
			}
		}

		if (file.isDirectory()) {
			cachedThumbnail = MapFile.getFolderThumbnail(file);
		}

		boolean hasAlreadyEmbeddedCoverArt = getType() == Format.AUDIO && getMedia() != null && getMedia().getThumb() != null;

		DLNAThumbnailInputStream result = null;
		try {
			if (cachedThumbnail != null && (!hasAlreadyEmbeddedCoverArt || file.isDirectory())) {
				result = DLNAThumbnailInputStream.toThumbnailInputStream(new FileInputStream(cachedThumbnail));
			} else if (getMedia() != null && getMedia().getThumb() != null) {
				result = getMedia().getThumbnailInputStream();
			}
		} catch (IOException e) {
			LOGGER.debug("An error occurred while getting thumbnail for \"{}\", using generic thumbnail instead: {}", getName(), e.getMessage());
			LOGGER.trace("", e);
		}
		return result != null ? result : super.getThumbnailInputStream();
	}

	@Override
	public void checkThumbnail() {
		InputFile input = new InputFile();
		input.setFile(getFile());
		checkThumbnail(input, getParent().getDefaultRenderer());
	}

	@Override
	protected String getThumbnailURL(DLNAImageProfile profile) {
		if (getType() == Format.IMAGE && !configuration.getImageThumbnailsEnabled()) {
			return null;
		}
		return super.getThumbnailURL(profile);
	}

	@Override
	public boolean isSubSelectable() {
		return true;
	}

	@Override
	public String write() {
		return getName() + ">" + getFile().getAbsolutePath();
	}

	private volatile String baseNamePrettified;
	private volatile String baseNameWithoutExtension;
	private final Object displayNameBaseLock = new Object();

	@Override
	protected String getDisplayNameBase() {
		if (getParent() instanceof SubSelFile && getMediaSubtitle() instanceof DLNAMediaOnDemandSubtitle) {
			return ((DLNAMediaOnDemandSubtitle) getMediaSubtitle()).getName();
		}
		if (isFolder()) {
			return super.getDisplayNameBase();
		}
		if (configuration.isPrettifyFilenames() && getFormat() != null && getFormat().isVideo()) {
			// Double-checked locking
			if (baseNamePrettified == null) {
				synchronized (displayNameBaseLock) {
					if (baseNamePrettified == null) {
						baseNamePrettified = FileUtil.getFileNamePrettified(super.getDisplayNameBase(), getMedia(), isEpisodeWithinSeasonFolder(), isEpisodeWithinTVSeriesFolder(), getFile().getAbsolutePath());
					}
				}
			}
			return baseNamePrettified;
		} else if (configuration.isHideExtensions()) {
			// Double-checked locking
			if (baseNameWithoutExtension == null) {
				synchronized (displayNameBaseLock) {
					if (baseNameWithoutExtension == null) {
						baseNameWithoutExtension = FileUtil.getFileNameWithoutExtension(super.getDisplayNameBase());
					}
				}
			}
			return baseNameWithoutExtension;
		}

		return super.getDisplayNameBase();
	}

	@Override
	public synchronized void syncResolve() {
		super.syncResolve();
		checkCoverThumb();
	}

	/**
	 * Updates cover art archive table during scan process in case the file has already stored cover art.
	 *
	 * @param inputFile
	 */
	protected void checkCoverThumb() {
		if (getMedia() != null && getMedia().isAudio() && getMedia().getAudioTrackCount() > 0) {
			String mbReleaseId = getMedia().getAudioTracksList().get(0).getMbidRecord();
			if (!StringUtils.isAllBlank(mbReleaseId)) {
				try {
					if (!MediaTableCoverArtArchive.hasCover(mbReleaseId)) {
						AudioFile af;
						if ("mp2".equals(FileUtil.getExtension(getFile()).toLowerCase(Locale.ROOT))) {
							af = AudioFileIO.readAs(getFile(), "mp3");
						} else {
							af = AudioFileIO.read(getFile());
						}
						Tag t = af.getTag();
						LOGGER.trace("no artwork in MediaTableCoverArtArchive table");
						if (t.getFirstArtwork() != null) {
							byte[] artBytes = t.getFirstArtwork().getBinaryData();
							MediaTableCoverArtArchive.writeMBID(mbReleaseId, new ByteArrayInputStream(artBytes));
							LOGGER.trace("added cover to MediaTableCoverArtArchive");
						} else {
							LOGGER.trace("no artwork in TAG");
						}
					} else {
						LOGGER.trace("cover already exists in MediaTableCoverArtArchive");
					}
				} catch (Exception e) {
					LOGGER.trace("checkCoverThumb failed.", e);
				}
			}
		}
	}
}
