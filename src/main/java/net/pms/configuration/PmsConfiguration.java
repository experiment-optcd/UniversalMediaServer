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
package net.pms.configuration;

import static org.apache.commons.lang3.StringUtils.isBlank;
import ch.qos.logback.classic.Level;
import com.sun.jna.Platform;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.dlna.CodeEnter;
import net.pms.dlna.RootFolder;
import net.pms.encoders.Player;
import net.pms.encoders.PlayerFactory;
import net.pms.encoders.PlayerId;
import net.pms.encoders.StandardPlayerId;
import net.pms.formats.Format;
import net.pms.gui.GuiManager;
import net.pms.service.PreventSleepMode;
import net.pms.service.Services;
import net.pms.service.SleepManager;
import net.pms.util.CoverSupplier;
import net.pms.util.FilePermissions;
import net.pms.util.FileUtil;
import net.pms.util.FileUtil.FileLocation;
import net.pms.util.FullyPlayedAction;
import net.pms.util.InvalidArgumentException;
import net.pms.util.Languages;
import net.pms.util.LogSystemInformationMode;
import net.pms.util.PropertiesUtil;
import net.pms.util.StringUtil;
import net.pms.util.SubtitleColor;
import net.pms.util.UMSUtils;
import net.pms.util.UniqueList;
import net.pms.util.WindowsRegistry;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container for all configurable UMS settings. Settings are typically defined by three things:
 * a unique key for use in the configuration file "UMS.conf", a getter (and setter) method and
 * a default value. When a key cannot be found in the current configuration, the getter will
 * return a default value. Setters only store a value, they do not permanently save it to
 * file.
 */
public class PmsConfiguration extends RendererConfiguration {
	private static final Logger LOGGER = LoggerFactory.getLogger(PmsConfiguration.class);
	protected static final int DEFAULT_PROXY_SERVER_PORT = -1;
	protected static final int DEFAULT_SERVER_PORT = 5001;
	// 90000 lines is approximately 10 MiB depending on locale and message length
	public static final int LOGGING_LOGS_TAB_LINEBUFFER_MAX = 90000;
	public static final int LOGGING_LOGS_TAB_LINEBUFFER_MIN = 100;
	public static final int LOGGING_LOGS_TAB_LINEBUFFER_STEP = 500;

	private static volatile boolean enabledEnginesBuilt = false;
	private static final ReentrantReadWriteLock ENABLED_ENGINES_LOCK = new ReentrantReadWriteLock();
	private static UniqueList<PlayerId> enabledEngines;

	private static volatile boolean enginesPriorityBuilt = false;
	private static final ReentrantReadWriteLock ENGINES_PRIORITY_LOCK = new ReentrantReadWriteLock();
	private static UniqueList<PlayerId> enginesPriority;

	/*
	 * MEncoder has a hardwired maximum of 8 threads for -lavcopts and 16
	 * for -lavdopts.
	 * The Windows SubJunk Builds can take 16 for both, but we keep it at 8
	 * for compatibility with other operating systems.
	 */
	protected static final int MENCODER_MAX_THREADS = 8;

	protected static final String KEY_3D_SUBTITLES_DEPTH = "3d_subtitles_depth";
	protected static final String KEY_ALIVE_DELAY = "ALIVE_delay";
	protected static final String KEY_ALTERNATE_SUBTITLES_FOLDER = "alternate_subtitles_folder";
	protected static final String KEY_ALTERNATE_THUMB_FOLDER = "alternate_thumb_folder";
	protected static final String KEY_APPEND_PROFILE_NAME = "append_profile_name";
	protected static final String KEY_ATZ_LIMIT = "atz_limit";
	protected static final String KEY_AUTOMATIC_DISCOVER = "automatic_discover";
	protected static final String KEY_AUTOMATIC_MAXIMUM_BITRATE = "automatic_maximum_bitrate";
	protected static final String KEY_AUDIO_BITRATE = "audio_bitrate";
	protected static final String KEY_AUDIO_CHANNEL_COUNT = "audio_channels";
	protected static final String KEY_AUDIO_EMBED_DTS_IN_PCM = "audio_embed_dts_in_pcm";
	protected static final String KEY_AUDIO_LANGUAGES = "audio_languages";
	protected static final String KEY_AUDIO_LIKES_IN_ROOT_FOLDER = "audio_likes_visible_root";
	protected static final String KEY_AUDIO_REMUX_AC3 = "audio_remux_ac3";
	protected static final String KEY_AUDIO_RESAMPLE = "audio_resample";
	protected static final String KEY_AUDIO_SUB_LANGS = "audio_subtitles_languages";
	protected static final String KEY_AUDIO_THUMBNAILS_METHOD = "audio_thumbnails_method";
	protected static final String KEY_AUDIO_USE_PCM = "audio_use_pcm";
	protected static final String KEY_AUDIO_UPDATE_RATING_TAG = "audio_update_rating_tag";
	protected static final String KEY_AUTO_UPDATE = "auto_update";
	protected static final String KEY_AUTOLOAD_SUBTITLES = "autoload_external_subtitles";
	protected static final String KEY_AVISYNTH_CONVERT_FPS = "avisynth_convert_fps";
	protected static final String KEY_AVISYNTH_INTERFRAME = "avisynth_interframe";
	protected static final String KEY_AVISYNTH_INTERFRAME_GPU = "avisynth_interframegpu";
	protected static final String KEY_AVISYNTH_MULTITHREADING = "avisynth_multithreading";
	protected static final String KEY_AVISYNTH_SCRIPT = "avisynth_script";
	protected static final String KEY_ASS_MARGIN = "subtitles_ass_margin";
	protected static final String KEY_ASS_OUTLINE = "subtitles_ass_outline";
	protected static final String KEY_ASS_SCALE = "subtitles_ass_scale";
	protected static final String KEY_ASS_SHADOW = "subtitles_ass_shadow";
	protected static final String KEY_API_KEY = "api_key";
	protected static final String KEY_BUFFER_MAX = "buffer_max";
	protected static final String KEY_BUMP_ADDRESS = "bump";
	protected static final String KEY_BUMP_IPS = "allowed_bump_ips";
	protected static final String KEY_BUMP_JS = "bump.js";
	protected static final String KEY_BUMP_SKIN_DIR = "bump.skin";
	protected static final String KEY_CHAPTER_INTERVAL = "chapter_interval";
	protected static final String KEY_CHAPTER_SUPPORT = "chapter_support";
	protected static final String KEY_CHROMECAST_DBG = "chromecast_debug";
	protected static final String KEY_CHROMECAST_EXT = "chromecast_extension";
	protected static final String KEY_CODE_CHARS = "code_charset";
	protected static final String KEY_CODE_THUMBS = "code_show_thumbs_no_code";
	protected static final String KEY_CODE_TMO = "code_valid_timeout";
	protected static final String KEY_CODE_USE = "code_enable";
	public    static final String KEY_SORT_AUDIO_TRACKS_BY_ALBUM_POSITION = "sort_audio_tracks_by_album_position";
	protected static final String KEY_DATABASE_MEDIA_CACHE_SIZE_KB = "database_media_cache_size";
	protected static final String KEY_DATABASE_MEDIA_USE_CACHE_SOFT = "database_media_use_cache_soft";
	protected static final String KEY_DATABASE_MEDIA_USE_MEMORY_INDEXES = "database_media_use_memory_indexes";
	protected static final String KEY_DISABLE_EXTERNAL_ENTITIES = "disable_external_entities";
	protected static final String KEY_DISABLE_FAKESIZE = "disable_fakesize";
	public    static final String KEY_DISABLE_SUBTITLES = "disable_subtitles";
	protected static final String KEY_DISABLE_TRANSCODE_FOR_EXTENSIONS = "disable_transcode_for_extensions";
	protected static final String KEY_DISABLE_TRANSCODING = "disable_transcoding";
	protected static final String KEY_DVDISO_THUMBNAILS = "dvd_isos_thumbnails";
	protected static final String KEY_DYNAMIC_PLS = "dynamic_playlist";
	protected static final String KEY_DYNAMIC_PLS_AUTO_SAVE = "dynamic_playlist_auto_save";
	protected static final String KEY_DYNAMIC_PLS_HIDE = "dynamic_playlist_hide_folder";
	protected static final String KEY_DYNAMIC_PLS_SAVE_PATH = "dynamic_playlist_save_path";
	protected static final String KEY_ENCODED_AUDIO_PASSTHROUGH = "encoded_audio_passthrough";
	protected static final String KEY_ENGINES = "engines";
	protected static final String KEY_ENGINES_PRIORITY = "engines_priority";
	protected static final String KEY_FFMPEG_ALTERNATIVE_PATH = "alternativeffmpegpath"; // TODO: FFmpegDVRMSRemux will be removed and DVR-MS will be transcoded
	protected static final String KEY_FFMPEG_AVAILABLE_GPU_ACCELERATION_METHODS = "ffmpeg_available_gpu_acceleration_methods";
	protected static final String KEY_FFMPEG_AVISYNTH_CONVERT_FPS = "ffmpeg_avisynth_convertfps";
	protected static final String KEY_FFMPEG_AVISYNTH_INTERFRAME = "ffmpeg_avisynth_interframe";
	protected static final String KEY_FFMPEG_AVISYNTH_INTERFRAME_GPU = "ffmpeg_avisynth_interframegpu";
	protected static final String KEY_FFMPEG_AVISYNTH_MULTITHREADING = "ffmpeg_avisynth_multithreading";
	protected static final String KEY_FFMPEG_FONTCONFIG = "ffmpeg_fontconfig";
	protected static final String KEY_FFMPEG_GPU_DECODING_ACCELERATION_METHOD = "ffmpeg_gpu_decoding_acceleration_method";
	protected static final String KEY_FFMPEG_GPU_DECODING_ACCELERATION_THREAD_NUMBER = "ffmpeg_gpu_decoding_acceleration_thread_number";
	protected static final String KEY_FFMPEG_LOGGING_LEVEL = "ffmpeg_logging_level";
	protected static final String KEY_FFMPEG_MENCODER_PROBLEMATIC_SUBTITLES = "ffmpeg_mencoder_problematic_subtitles";
	protected static final String KEY_FFMPEG_MULTITHREADING = "ffmpeg_multithreading";
	protected static final String KEY_FFMPEG_MUX_TSMUXER_COMPATIBLE = "ffmpeg_mux_tsmuxer_compatible";
	protected static final String KEY_FFMPEG_SOX = "ffmpeg_sox";
	protected static final String KEY_FIX_25FPS_AV_MISMATCH = "fix_25fps_av_mismatch";
	protected static final String KEY_FOLDER_LIMIT = "folder_limit";
	protected static final String KEY_FOLDER_NAMES_IGNORED = "folder_names_ignored";
	protected static final String KEY_FOLDERS = "folders";
	protected static final String KEY_FOLDERS_IGNORED = "folders_ignored";
	protected static final String KEY_FOLDERS_MONITORED = "folders_monitored";
	protected static final String KEY_FONT = "subtitles_font";
	protected static final String KEY_FORCE_EXTERNAL_SUBTITLES = "force_external_subtitles";
	protected static final String KEY_FORCE_TRANSCODE_FOR_EXTENSIONS = "force_transcode_for_extensions";
	protected static final String KEY_FORCED_SUBTITLE_LANGUAGE = "forced_subtitle_language";
	protected static final String KEY_FORCED_SUBTITLE_TAGS = "forced_subtitle_tags";
	public    static final String KEY_GPU_ACCELERATION = "gpu_acceleration";
	protected static final String KEY_GUI_LOG_SEARCH_CASE_SENSITIVE = "gui_log_search_case_sensitive";
	protected static final String KEY_GUI_LOG_SEARCH_MULTILINE = "gui_log_search_multiline";
	protected static final String KEY_GUI_LOG_SEARCH_USE_REGEX = "gui_log_search_use_regex";
	protected static final String KEY_HIDE_ADVANCED_OPTIONS = "hide_advanced_options";
	protected static final String KEY_HIDE_EMPTY_FOLDERS = "hide_empty_folders";
	protected static final String KEY_USE_SYMLINKS_TARGET_FILE = "use_symlinks_target_file";
	protected static final String KEY_HIDE_ENGINENAMES = "hide_enginenames";
	protected static final String KEY_HIDE_EXTENSIONS = "hide_extensions";

	/**
	 * @deprecated, replaced by {@link #KEY_SUBS_INFO_LEVEL}
	 */
	protected static final String KEY_HIDE_SUBS_INFO = "hide_subs_info";

	/**
	 * @deprecated, replaced by {@link #KEY_SERVER_ENGINE}
	 */
	protected static final String KEY_HTTP_ENGINE_V2 = "http_engine_v2";
	protected static final String KEY_IGNORE_THE_WORD_A_AND_THE = "ignore_the_word_a_and_the";
	protected static final String KEY_IMAGE_THUMBNAILS_ENABLED = "image_thumbnails";
	protected static final String KEY_INFO_DB_RETRY = "infodb_retry";
	protected static final String KEY_IP_FILTER = "ip_filter";
	protected static final String KEY_ITUNES_LIBRARY_PATH = "itunes_library_path";
	protected static final String KEY_LANGUAGE = "language";
	protected static final String KEY_LIVE_SUBTITLES_KEEP = "live_subtitles_keep";
	protected static final String KEY_LIVE_SUBTITLES_LIMIT = "live_subtitles_limit";
	protected static final String KEY_LOG_SYSTEM_INFO = "log_system_info";
	protected static final String KEY_LOGGING_LOGFILE_NAME = "logging_logfile_name";
	protected static final String KEY_LOGGING_BUFFERED = "logging_buffered";
	protected static final String KEY_LOGGING_FILTER_CONSOLE = "logging_filter_console";
	protected static final String KEY_LOGGING_FILTER_LOGS_TAB = "logging_filter_logs_tab";
	protected static final String KEY_LOGGING_LOGS_TAB_LINEBUFFER = "logging_logs_tab_linebuffer";
	protected static final String KEY_LOGGING_SYSLOG_FACILITY = "logging_syslog_facility";
	protected static final String KEY_LOGGING_SYSLOG_HOST = "logging_syslog_host";
	protected static final String KEY_LOGGING_SYSLOG_PORT = "logging_syslog_port";
	protected static final String KEY_LOGGING_USE_SYSLOG = "logging_use_syslog";
	protected static final String KEY_LOG_DATABASE = "log_database";
	protected static final String KEY_MANAGED_PLAYLIST_FOLDER = "managed_playlist_folder";
	protected static final String KEY_MAX_AUDIO_BUFFER = "maximum_audio_buffer_size";
	protected static final String KEY_MAX_BITRATE = "maximum_bitrate";
	protected static final String KEY_MAX_MEMORY_BUFFER_SIZE = "maximum_video_buffer_size";
	protected static final String KEY_MEDIA_LIB_SORT = "media_lib_sort";
	protected static final String KEY_MENCODER_ASS = "mencoder_ass";
	protected static final String KEY_MENCODER_AC3_FIXED = "mencoder_ac3_fixed";
	protected static final String KEY_MENCODER_CODEC_SPECIFIC_SCRIPT = "mencoder_codec_specific_script";
	protected static final String KEY_MENCODER_CUSTOM_OPTIONS = "mencoder_custom_options";
	protected static final String KEY_MENCODER_FONT_CONFIG = "mencoder_fontconfig";
	protected static final String KEY_MENCODER_FORCE_FPS = "mencoder_forcefps";
	protected static final String KEY_MENCODER_INTELLIGENT_SYNC = "mencoder_intelligent_sync";
	protected static final String KEY_MENCODER_MAX_THREADS = "mencoder_max_threads";
	protected static final String KEY_MENCODER_MUX_COMPATIBLE = "mencoder_mux_compatible";
	protected static final String KEY_MENCODER_MT = "mencoder_mt";
	protected static final String KEY_MENCODER_NO_OUT_OF_SYNC = "mencoder_nooutofsync";
	protected static final String KEY_MENCODER_NOASS_BLUR = "mencoder_noass_blur";
	protected static final String KEY_MENCODER_NOASS_OUTLINE = "mencoder_noass_outline";
	protected static final String KEY_MENCODER_NOASS_SCALE = "mencoder_noass_scale";
	protected static final String KEY_MENCODER_NOASS_SUBPOS = "mencoder_noass_subpos";
	protected static final String KEY_MENCODER_NORMALIZE_VOLUME = "mencoder_normalize_volume";
	protected static final String KEY_MENCODER_OVERSCAN_COMPENSATION_HEIGHT = "mencoder_overscan_compensation_height";
	protected static final String KEY_MENCODER_OVERSCAN_COMPENSATION_WIDTH = "mencoder_overscan_compensation_width";
	protected static final String KEY_MENCODER_REMUX_MPEG2 = "mencoder_remux_mpeg2";
	protected static final String KEY_MENCODER_SCALER = "mencoder_scaler";
	protected static final String KEY_MENCODER_SCALEX = "mencoder_scalex";
	protected static final String KEY_MENCODER_SCALEY = "mencoder_scaley";
	protected static final String KEY_MENCODER_SUB_FRIBIDI = "mencoder_subfribidi";
	protected static final String KEY_MENCODER_USE_PCM_FOR_HQ_AUDIO_ONLY = "mencoder_usepcm_for_hq_audio_only";
	protected static final String KEY_MENCODER_VOBSUB_SUBTITLE_QUALITY = "mencoder_vobsub_subtitle_quality";
	protected static final String KEY_MENCODER_YADIF = "mencoder_yadif";
	protected static final String KEY_MIN_MEMORY_BUFFER_SIZE = "minimum_video_buffer_size";
	protected static final String KEY_MIN_PLAY_TIME = "minimum_watched_play_time";
	protected static final String KEY_MIN_PLAY_TIME_FILE = "min_playtime_file";
	protected static final String KEY_MIN_PLAY_TIME_WEB = "min_playtime_web";
	protected static final String KEY_MIN_STREAM_BUFFER = "minimum_web_buffer_size";
	protected static final String KEY_MINIMIZED = "minimized";
	protected static final String KEY_MPEG2_MAIN_SETTINGS = "mpeg2_main_settings";
	protected static final String KEY_MUX_ALLAUDIOTRACKS = "tsmuxer_mux_all_audiotracks";
	protected static final String KEY_NETWORK_INTERFACE = "network_interface";
	protected static final String KEY_NUMBER_OF_CPU_CORES = "number_of_cpu_cores";
	protected static final String KEY_OPEN_ARCHIVES = "enable_archive_browsing";
	protected static final String KEY_OVERSCAN = "mencoder_overscan";
	protected static final String KEY_PLAYLIST_AUTO_ADD_ALL = "playlist_auto_add_all";
	protected static final String KEY_PLAYLIST_AUTO_CONT = "playlist_auto_continue";
	protected static final String KEY_PLAYLIST_AUTO_PLAY = "playlist_auto_play";
	protected static final String KEY_PLUGIN_DIRECTORY = "plugins";
	protected static final String KEY_PLUGIN_PURGE_ACTION = "plugin_purge";
	protected static final String KEY_PRETTIFY_FILENAMES = "prettify_filenames";
	protected static final String KEY_PREVENT_SLEEP = "prevent_sleep";
	protected static final String KEY_PROFILE_NAME = "name";
	protected static final String KEY_PROXY_SERVER_PORT = "proxy";
	protected static final String KEY_RENDERER_DEFAULT = "renderer_default";
	protected static final String KEY_RENDERER_FORCE_DEFAULT = "renderer_force_default";
	protected static final String KEY_RESUME = "resume";
	protected static final String KEY_RESUME_BACK = "resume_back";
	protected static final String KEY_RESUME_KEEP_TIME = "resume_keep_time";
	protected static final String KEY_RESUME_REWIND = "resume_rewind";
	protected static final String KEY_ROOT_LOG_LEVEL = "log_level";
	protected static final String KEY_RUN_WIZARD = "run_wizard";
	protected static final String KEY_SCAN_SHARED_FOLDERS_ON_STARTUP = "scan_shared_folders_on_startup";
	protected static final String KEY_SCRIPT_DIR = "script_dir";
	protected static final String KEY_SEARCH_FOLDER = "search_folder";
	protected static final String KEY_SEARCH_IN_FOLDER = "search_in_folder";
	protected static final String KEY_SEARCH_RECURSE = "search_recurse"; // legacy option
	protected static final String KEY_SEARCH_RECURSE_DEPTH = "search_recurse_depth";
	protected static final String KEY_SELECTED_RENDERERS = "selected_renderers";
	protected static final String KEY_SERVER_ENGINE = "server_engine";
	protected static final String KEY_SERVER_HOSTNAME = "hostname";
	protected static final String KEY_SERVER_NAME = "server_name";
	protected static final String KEY_SERVER_PORT = "port";
	protected static final String KEY_SHARES = "shares";
	protected static final String KEY_SHOW_APERTURE_LIBRARY = "show_aperture_library";
	protected static final String KEY_SHOW_IPHOTO_LIBRARY = "show_iphoto_library";
	protected static final String KEY_SHOW_ITUNES_LIBRARY = "show_itunes_library";
	protected static final String KEY_SHOW_LIVE_SUBTITLES_FOLDER = "show_live_subtitles_folder";
	protected static final String KEY_SHOW_MEDIA_LIBRARY_FOLDER = "show_media_library_folder";
	protected static final String KEY_SHOW_RECENTLY_PLAYED_FOLDER = "show_recently_played_folder";
	protected static final String KEY_SHOW_SERVER_SETTINGS_FOLDER = "show_server_settings_folder";
	protected static final String KEY_SHOW_SPLASH_SCREEN = "show_splash_screen";
	protected static final String KEY_SHOW_TRANSCODE_FOLDER = "show_transcode_folder";
	protected static final String KEY_SINGLE = "single_instance";
	protected static final String KEY_SKIP_LOOP_FILTER_ENABLED = "mencoder_skip_loop_filter";
	protected static final String KEY_SKIP_NETWORK_INTERFACES = "skip_network_interfaces";
	protected static final String KEY_SORT_METHOD = "sort_method";
	protected static final String KEY_SORT_PATHS = "sort_paths";
	protected static final String KEY_SPEED_DBG = "speed_debug";
	protected static final String KEY_SUBS_COLOR = "subtitles_color";
	protected static final String KEY_SUBS_INFO_LEVEL = "subs_info_level";
	protected static final String KEY_SUBTITLES_CODEPAGE = "subtitles_codepage";
	protected static final String KEY_SUBTITLES_LANGUAGES = "subtitles_languages";
	protected static final String KEY_TEMP_FOLDER_PATH = "temp_directory";
	protected static final String KEY_THUMBNAIL_GENERATION_ENABLED = "generate_thumbnails";
	protected static final String KEY_THUMBNAIL_SEEK_POS = "thumbnail_seek_position";
	protected static final String KEY_TRANSCODE_BLOCKS_MULTIPLE_CONNECTIONS = "transcode_block_multiple_connections";
	protected static final String KEY_TRANSCODE_FOLDER_NAME = "transcode_folder_name";
	protected static final String KEY_TRANSCODE_KEEP_FIRST_CONNECTION = "transcode_keep_first_connection";
	protected static final String KEY_TSMUXER_FORCEFPS = "tsmuxer_forcefps";
	protected static final String KEY_UPNP_DEBUG = "upnp_debug";
	protected static final String KEY_UPNP_ENABLED = "upnp_enable";
	protected static final String KEY_UPNP_PORT = "upnp_port";
	protected static final String KEY_USE_CACHE = "use_cache";
	protected static final String KEY_USE_EMBEDDED_SUBTITLES_STYLE = "use_embedded_subtitles_style";
	protected static final String KEY_USE_IMDB_INFO = "use_imdb_info";
	protected static final String KEY_USE_MPLAYER_FOR_THUMBS = "use_mplayer_for_video_thumbs";
	protected static final String KEY_UUID = "uuid";
	protected static final String KEY_VIDEOTRANSCODE_START_DELAY = "videotranscode_start_delay";
	protected static final String KEY_VIRTUAL_FOLDERS = "virtual_folders";
	protected static final String KEY_VIRTUAL_FOLDERS_FILE = "virtual_folders_file";
	protected static final String KEY_VLC_AUDIO_SYNC_ENABLED = "vlc_audio_sync_enabled";
	protected static final String KEY_VLC_SAMPLE_RATE = "vlc_sample_rate";
	protected static final String KEY_VLC_SAMPLE_RATE_OVERRIDE = "vlc_sample_rate_override";
	protected static final String KEY_VLC_SCALE = "vlc_scale";
	protected static final String KEY_VLC_SUBTITLE_ENABLED = "vlc_subtitle_enabled";
	protected static final String KEY_VLC_USE_EXPERIMENTAL_CODECS = "vlc_use_experimental_codecs";
	protected static final String KEY_VLC_USE_HW_ACCELERATION = "vlc_use_hw_acceleration";
	protected static final String KEY_FULLY_PLAYED_ACTION = "fully_played_action";
	protected static final String KEY_FULLY_PLAYED_OUTPUT_DIRECTORY = "fully_played_output_directory";
	protected static final String KEY_WEB_AUTHENTICATE = "web_authenticate";
	protected static final String KEY_WEB_BROWSE_LANG = "web_use_browser_lang";
	protected static final String KEY_WEB_BROWSE_SUB_LANG = "web_use_browser_sub_lang";
	protected static final String KEY_WEB_CONF_PATH = "web_conf";
	protected static final String KEY_WEB_CONT_AUDIO = "web_continue_audio";
	protected static final String KEY_WEB_CONT_IMAGE = "web_continue_image";
	protected static final String KEY_WEB_CONT_VIDEO = "web_continue_video";
	protected static final String KEY_WEB_CONTROL = "web_control";
	protected static final String KEY_WEB_ENABLE = "web_enable";
	protected static final String KEY_WEB_FLASH = "web_flash";
	protected static final String KEY_WEB_HEIGHT = "web_height";
	protected static final String KEY_WEB_IMAGE_SLIDE = "web_image_show_delay";
	protected static final String KEY_WEB_LOOP_AUDIO = "web_loop_audio";
	protected static final String KEY_WEB_LOOP_IMAGE = "web_loop_image";
	protected static final String KEY_WEB_LOOP_VIDEO = "web_loop_video";
	protected static final String KEY_WEB_LOW_SPEED = "web_low_speed";
	protected static final String KEY_WEB_MP4_TRANS = "web_mp4_trans";
	protected static final String KEY_WEB_PATH = "web_path";
	protected static final String KEY_WEB_SIZE = "web_size";
	protected static final String KEY_WEB_SUBS_TRANS = "web_subtitles_transcoded";
	protected static final String KEY_WEB_THREADS = "web_threads";
	protected static final String KEY_WEB_TRANSCODE = "web_transcode";
	protected static final String KEY_WEB_WIDTH = "web_width";
	protected static final String KEY_X264_CONSTANT_RATE_FACTOR = "x264_constant_rate_factor";

	protected static final String SHOW_INFO_ABOUT_AUTOMATIC_VIDEO_SETTING = "show_info";
	protected static final String WAS_YOUTUBE_DL_ENABLED_ONCE = "was_youtube_dl_enabled_once";

	/**
	 * Web stuff
	 */
	protected static final String KEY_NO_FOLDERS = "no_shared";
	protected static final String KEY_WEB_HTTPS = "web_https";
	protected static final String KEY_WEB_PORT = "web_port";
	protected static final int WEB_MAX_THREADS = 100;

	// The name of the subdirectory under which UMS config files are stored for this build (default: UMS).
	// See Build for more details
	protected static final String PROFILE_DIRECTORY_NAME = Build.getProfileDirectoryName();

	// The default profile name displayed on the renderer
	protected static String hostName;

	protected static String defaultAviSynthScript;
	protected static final int MAX_MAX_MEMORY_DEFAULT_SIZE = 400;
	protected static final int BUFFER_MEMORY_FACTOR = 368;
	protected static int maxMaxMemoryBufferSize = MAX_MAX_MEMORY_DEFAULT_SIZE;
	protected static final char LIST_SEPARATOR = ',';
	public final String allRenderers = "All renderers";

	// Path to default logfile directory
	protected String defaultLogFileDir = null;

	// Path to default zipped logfile directory
	protected String defaultZippedLogFileDir = null;

	public TempFolder tempFolder;
	@Nonnull
	protected final PlatformProgramPaths programPaths;
	public IpFilter filter;

	/**
	 * The set of keys defining when the media server should be restarted due to a
	 * configuration change.
	 */
	public static final Set<String> NEED_MEDIA_SERVER_RELOAD_FLAGS = new HashSet<>(
		Arrays.asList(
			KEY_CHROMECAST_EXT,
			KEY_NETWORK_INTERFACE,
			KEY_SERVER_ENGINE,
			KEY_SERVER_HOSTNAME,
			KEY_SERVER_PORT,
			KEY_UPNP_ENABLED
		)
	);

	/**
	 * The set of keys defining when the HTTP Interface server should be restarted
	 * due to a configuration change.
	 */
	public static final Set<String> NEED_INTERFACE_SERVER_RELOAD_FLAGS = new HashSet<>(
		Arrays.asList(
			KEY_WEB_ENABLE,
			KEY_WEB_HTTPS,
			KEY_WEB_PORT
		)
	);

	/**
	 * The set of keys defining when the renderers should be reloaded due to a
	 * configuration change.
	 */
	public static final Set<String> NEED_RENDERERS_RELOAD_FLAGS = new HashSet<>(
		Arrays.asList(
			KEY_RENDERER_DEFAULT,
			KEY_RENDERER_FORCE_DEFAULT,
			KEY_SELECTED_RENDERERS
		)
	);

	/**
	 * The set of keys defining when the media library has to reset due to a
	 * configuration change.
	 *
	 * It will need a renderers reload as renderers build from it.
	 */
	public static final Set<String> NEED_MEDIA_LIBRARY_RELOAD_FLAGS = new HashSet<>(
		Arrays.asList(
			KEY_FULLY_PLAYED_ACTION,
			KEY_SHOW_RECENTLY_PLAYED_FOLDER,
			KEY_USE_CACHE
		)
	);

	/**
	 * The set of keys defining when the renderers has to rebuid their root folder
	 * due to a configuration change.
	 */
	public static final Set<String> NEED_RENDERERS_ROOT_RELOAD_FLAGS = new HashSet<>(
		Arrays.asList(
			KEY_ATZ_LIMIT,
			KEY_AUDIO_THUMBNAILS_METHOD,
			KEY_CHAPTER_SUPPORT,
			KEY_DISABLE_TRANSCODE_FOR_EXTENSIONS,
			KEY_DISABLE_TRANSCODING,
			KEY_FOLDERS,
			KEY_FOLDERS_MONITORED,
			KEY_FORCE_TRANSCODE_FOR_EXTENSIONS,
			KEY_HIDE_EMPTY_FOLDERS,
			KEY_OPEN_ARCHIVES,
			KEY_PRETTIFY_FILENAMES,
			KEY_SHOW_APERTURE_LIBRARY,
			KEY_SHOW_IPHOTO_LIBRARY,
			KEY_SHOW_ITUNES_LIBRARY,
			KEY_SHOW_LIVE_SUBTITLES_FOLDER,
			KEY_SHOW_MEDIA_LIBRARY_FOLDER,
			KEY_SHOW_SERVER_SETTINGS_FOLDER,
			KEY_SHOW_TRANSCODE_FOLDER,
			KEY_SORT_METHOD
		)
	);

	/*
		The following code enables a single setting - UMS_PROFILE - to be used to
		initialize PROFILE_PATH i.e. the path to the current session's profile (AKA UMS.conf).
		It also initializes PROFILE_DIRECTORY - i.e. the directory the profile is located in -
		which is needed to detect the default WEB.conf location (anything else?).

		While this convention - and therefore PROFILE_DIRECTORY - will remain,
		adding more configurables - e.g. web_conf = ... - is on the TODO list.

		UMS_PROFILE is read (in this order) from the property ums.profile.path or the
		environment variable UMS_PROFILE. If UMS is launched with the command-line option
		"profiles" (e.g. from a shortcut), it displays a file chooser dialog that
		allows the ums.profile.path property to be set. This makes it easy to run UMS
		under multiple profiles without fiddling with environment variables, properties or
		command-line arguments.

		1) if UMS_PROFILE is not set, UMS.conf is located in:

			Windows:             %ALLUSERSPROFILE%\$build
			Mac OS X:            $HOME/Library/Application Support/$build
			Everything else:     $HOME/.config/$build

		- where $build is a subdirectory that ensures incompatible UMS builds don't target/clobber
		the same configuration files. The default value for $build is "UMS". Other builds might use e.g.
		"UMS Rendr Edition" or "ums-mlx".

		2) if a relative or absolute *directory path* is supplied (the directory must exist),
		it is used as the profile directory and the profile is located there under the default profile name (UMS.conf):

			UMS_PROFILE = /absolute/path/to/dir
			UMS_PROFILE = relative/path/to/dir # relative to the working directory

		Amongst other things, this can be used to restore the legacy behaviour of locating UMS.conf in the current
		working directory e.g.:

			UMS_PROFILE=. ./UMS.sh

		3) if a relative or absolute *file path* is supplied (the file doesn't have to exist),
		it is taken to be the profile, and its parent dir is taken to be the profile (i.e. config file) dir:

			UMS_PROFILE = UMS.conf            # profile dir = .
			UMS_PROFILE = folder/dev.conf     # profile dir = folder
			UMS_PROFILE = /path/to/some.file  # profile dir = /path/to/
	 */
	protected static final String DEFAULT_PROFILE_FILENAME = "UMS.conf";
	protected static final String ENV_PROFILE_PATH = "UMS_PROFILE";
	protected static final String DEFAULT_WEB_CONF_FILENAME = "WEB.conf";
	protected static final String DEFAULT_CREDENTIALS_FILENAME = "UMS.cred";

	// Path to directory containing UMS config files
	protected static final String PROFILE_DIRECTORY;

	// Absolute path to profile file e.g. /path/to/UMS.conf
	protected static final String PROFILE_PATH;

	// Absolute path to WEB.conf file e.g. /path/to/WEB.conf
	protected static String webConfPath;

	// Absolute path to skel (default) profile file e.g. /etc/skel/.config/universalmediaserver/UMS.conf
	// "project.skelprofile.dir" project property
	protected static final String SKEL_PROFILE_PATH;

	protected static final String PROPERTY_PROFILE_PATH = "ums.profile.path";
	protected static final String SYSTEM_PROFILE_DIRECTORY;

	static {
		// first of all, set up the path to the default system profile directory
		if (Platform.isWindows()) {
			String programData = System.getenv("ALLUSERSPROFILE");

			if (programData != null) {
				SYSTEM_PROFILE_DIRECTORY = String.format("%s\\%s", programData, PROFILE_DIRECTORY_NAME);
			} else {
				SYSTEM_PROFILE_DIRECTORY = ""; // i.e. current (working) directory
			}
		} else if (Platform.isMac()) {
			SYSTEM_PROFILE_DIRECTORY = String.format(
				"%s/%s/%s",
				System.getProperty("user.home"),
				"/Library/Application Support",
				PROFILE_DIRECTORY_NAME
			);
		} else {
			String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");

			if (xdgConfigHome == null) {
				SYSTEM_PROFILE_DIRECTORY = String.format("%s/.config/%s", System.getProperty("user.home"), PROFILE_DIRECTORY_NAME);
			} else {
				SYSTEM_PROFILE_DIRECTORY = String.format("%s/%s", xdgConfigHome, PROFILE_DIRECTORY_NAME);
			}
		}

		// ensure that the SYSTEM_PROFILE_DIRECTORY exists
		File systemProfileDirectory = new File(SYSTEM_PROFILE_DIRECTORY);
		if (!systemProfileDirectory.exists()) {
			systemProfileDirectory.mkdirs();
		}

		// now set the profile path. first: check for a custom setting.
		// try the system property, typically set via the profile chooser
		String customProfilePath = System.getProperty(PROPERTY_PROFILE_PATH);

		// failing that, try the environment variable
		if (StringUtils.isBlank(customProfilePath)) {
			customProfilePath = System.getenv(ENV_PROFILE_PATH);
		}

		// if customProfilePath is still blank, the default profile dir/filename is used
		FileLocation profileLocation = FileUtil.getFileLocation(
			customProfilePath,
			SYSTEM_PROFILE_DIRECTORY,
			DEFAULT_PROFILE_FILENAME
		);
		PROFILE_PATH = profileLocation.getFilePath();
		PROFILE_DIRECTORY = profileLocation.getDirectoryPath();

		// Set SKEL_PROFILE_PATH for Linux systems
		String skelDir = PropertiesUtil.getProjectProperties().get("project.skelprofile.dir");
		if (Platform.isLinux() && StringUtils.isNotBlank(skelDir)) {
			SKEL_PROFILE_PATH = FilenameUtils.normalize(
				new File(
					new File(
						skelDir,
						PROFILE_DIRECTORY_NAME
					).getAbsolutePath(),
					DEFAULT_PROFILE_FILENAME
				).getAbsolutePath()
			);
		} else {
			SKEL_PROFILE_PATH = null;
		}
	}

	/**
	 * Default constructor that will attempt to load the PMS configuration file
	 * from the profile path.
	 *
	 * @throws org.apache.commons.configuration.ConfigurationException
	 * @throws InterruptedException
	 */
	public PmsConfiguration() throws ConfigurationException, InterruptedException {
		this(true);
	}

	/**
	 * Constructor that will initialize the PMS configuration.
	 *
	 * @param loadFile Set to true to attempt to load the PMS configuration
	 *                 file from the profile path. Set to false to skip
	 *                 loading.
	 * @throws ConfigurationException
	 * @throws InterruptedException
	 */
	public PmsConfiguration(boolean loadFile) throws ConfigurationException, InterruptedException {
		super(0);

		if (loadFile) {
			File pmsConfFile = new File(PROFILE_PATH);

			try {
				((PropertiesConfiguration) configuration).load(pmsConfFile);
			} catch (ConfigurationException e) {
				if (Platform.isLinux() && SKEL_PROFILE_PATH != null) {
					LOGGER.debug("Failed to load {} ({}) - attempting to load skel profile", PROFILE_PATH, e.getMessage());
					File skelConfigFile = new File(SKEL_PROFILE_PATH);

					try {
						// Load defaults from skel profile, save them later to PROFILE_PATH
						((PropertiesConfiguration) configuration).load(skelConfigFile);
						LOGGER.info("Default configuration loaded from {}", SKEL_PROFILE_PATH);
					} catch (ConfigurationException ce) {
						LOGGER.warn("Can't load neither {}: {} nor {}: {}", PROFILE_PATH, e.getMessage(), SKEL_PROFILE_PATH, ce.getMessage());
					}
				} else {
					LOGGER.warn("Can't load {}: {}", PROFILE_PATH, e.getMessage());
				}
			}
		}

		((PropertiesConfiguration) configuration).setPath(PROFILE_PATH);

		tempFolder = new TempFolder(getString(KEY_TEMP_FOLDER_PATH, null));
		programPaths = new ConfigurableProgramPaths(configuration);
		filter = new IpFilter();
		PMS.setLocale(getLanguageLocale(true));
		//TODO: The line below should be removed once all calls to Locale.getDefault() is replaced with PMS.getLocale()
		Locale.setDefault(getLanguageLocale());

		// Set DEFAULT_AVI_SYNTH_SCRIPT according to language
		defaultAviSynthScript = "<movie>\n<sub>\n";

		long usableMemory = (Runtime.getRuntime().maxMemory() / 1048576) - BUFFER_MEMORY_FACTOR;
		if (usableMemory > MAX_MAX_MEMORY_DEFAULT_SIZE) {
			maxMaxMemoryBufferSize = (int) usableMemory;
		}
	}

	/**
	 * The following 2 constructors are for minimal instantiation in the context
	 * of subclasses (i.e.DeviceConfiguration) that use our getters and setters
	 * on another Configuration object.
	 * Here our main purpose is to initialize RendererConfiguration as required.
	 *
	 * @param ignored this integer is ignored
	 */
	protected PmsConfiguration(int ignored) {
		// Just instantiate
		super(0);
		tempFolder = null;
		programPaths = new ConfigurableProgramPaths(configuration);
		filter = null;
	}

	protected PmsConfiguration(File f, String uuid) throws ConfigurationException {
		// Just initialize super
		super(f, uuid);
		tempFolder = null;
		programPaths = new ConfigurableProgramPaths(configuration);
		filter = null;
	}

	@Override
	public void reset() {
		// This is just to prevent super.reset() from being invoked. Actual resetting would
		// require rebooting here, since all of the application settings are implicated.
	}

	private static String verifyLogFolder(File folder, String fallbackTo) {
		try {
			FilePermissions permissions = FileUtil.getFilePermissions(folder);
			if (LOGGER.isTraceEnabled()) {
				if (!permissions.isFolder()) {
					LOGGER.trace("getDefaultLogFileFolder: \"{}\" is not a folder, falling back to {} for logging", folder.getAbsolutePath(), fallbackTo);
				} else if (!permissions.isBrowsable()) {
					LOGGER.trace("getDefaultLogFileFolder: \"{}\" is not browsable, falling back to {} for logging", folder.getAbsolutePath(), fallbackTo);
				} else if (!permissions.isWritable()) {
					LOGGER.trace("getDefaultLogFileFolder: \"{}\" is not writable, falling back to {} for logging", folder.getAbsolutePath(), fallbackTo);
				}
			}

			if (permissions.isFolder() && permissions.isBrowsable() && permissions.isWritable()) {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Default logfile folder set to: {}", folder.getAbsolutePath());
				}

				return folder.getAbsolutePath();
			}
		} catch (FileNotFoundException e) {
			LOGGER.trace("getDefaultLogFileFolder: \"{}\" not found, falling back to {} for logging: {}", folder.getAbsolutePath(), fallbackTo, e.getMessage());
		}

		return null;
	}

	/**
	 * @return first writable folder in the following order:
	 * <p>
	 *     1. (On Linux only) path to {@code /var/log/ums/%USERNAME%/}.
	 * </p>
	 * <p>
	 *     2. Path to profile folder ({@code ~/.config/UMS/} on Linux, {@code %ALLUSERSPROFILE%\UMS} on Windows and
	 *     {@code ~/Library/Application Support/UMS/} on Mac).
	 * </p>
	 * <p>
	 *     3. Path to user-defined temporary folder specified by {@code temp_directory} parameter in UMS.conf.
	 * </p>
	 * <p>
	 *     4. Path to system temporary folder.
	 * </p>
	 * <p>
	 *     5. Path to current working directory.
	 * </p>
	 */
	public synchronized String getDefaultLogFileFolder() {
		if (defaultLogFileDir == null) {
			if (Platform.isLinux()) {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("getDefaultLogFileFolder: System is Linux, trying \"/var/log/UMS/{}/\"", System.getProperty("user.name"));
				}

				final File logDirectory = new File("/var/log/UMS/" + System.getProperty("user.name") + "/");
				if (!logDirectory.exists()) {
					if (LOGGER.isTraceEnabled()) {
						LOGGER.trace("getDefaultLogFileFolder: Trying to create: \"{}\"", logDirectory.getAbsolutePath());
					}

					try {
						FileUtils.forceMkdir(logDirectory);
						if (LOGGER.isTraceEnabled()) {
							LOGGER.trace("getDefaultLogFileFolder: \"{}\" created", logDirectory.getAbsolutePath());
						}
					} catch (IOException e) {
						LOGGER.debug("Could not create \"{}\": {}", logDirectory.getAbsolutePath(), e.getMessage());
					}
				}

				defaultLogFileDir = verifyLogFolder(logDirectory, "profile folder");
			}

			if (defaultLogFileDir == null) {
				// Log to profile directory if it is writable.
				final File profileDirectory = new File(PROFILE_DIRECTORY);
				defaultLogFileDir = verifyLogFolder(profileDirectory, "temporary folder");
			}

			if (defaultLogFileDir == null) {
				// Try user-defined temporary folder or fall back to system temporary folder.
				try {
					defaultLogFileDir = verifyLogFolder(getTempFolder(), "working folder");
				} catch (IOException e) {
					LOGGER.error("Could not determine default logfile folder, falling back to working directory: {}", e.getMessage());
					defaultLogFileDir = "";
				}
			}
		}

		return defaultLogFileDir;
	}

	public String getDefaultLogFileName() {
		String s = getString(KEY_LOGGING_LOGFILE_NAME, "debug.log");
		if (FileUtil.isValidFileName(s)) {
			return s;
		}

		return "debug.log";
	}

	public String getDefaultLogFilePath() {
		return FileUtil.appendPathSeparator(getDefaultLogFileFolder()) + getDefaultLogFileName();
	}

	/**
	 * @return Path to desktop folder ({@code ~/Desktop/UMS-log} on Linux, {@code %USERPROFILE%/Desktop/UMS-log} on Windows and
	 *     {@code ~/Desktop/UMS-log} on Mac). If desktop path is not writable then fall back to UMS log file path.
	 */
	public String getDefaultZippedLogFileFolder() {
		if (defaultZippedLogFileDir == null) {
			final File zippedLogDir = new File(
					System.getProperty("user.home") +
							File.separator + "Desktop" +
							File.separator + "UMS-log"
			);

			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("getDefaultLogFileFolder: Trying \"{}\"", zippedLogDir.getAbsolutePath());
			}

			if (!zippedLogDir.exists()) {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("getDefaultLogFileFolder: Trying to create: \"{}\"", zippedLogDir.getAbsolutePath());
				}

				try {
					FileUtils.forceMkdir(zippedLogDir);
					if (LOGGER.isTraceEnabled()) {
						LOGGER.trace("getDefaultLogFileFolder: \"{}\" created", zippedLogDir.getAbsolutePath());
					}
				} catch (IOException e) {
					LOGGER.debug("Could not create \"{}\": {}", zippedLogDir.getAbsolutePath(), e.getMessage());
				}
			}

			defaultZippedLogFileDir = verifyLogFolder(zippedLogDir, "UMS log file path");
		}

		if (defaultZippedLogFileDir == null) {
			// default to UMS log file path
			defaultZippedLogFileDir = getDefaultLogFilePath();
		}

		return defaultZippedLogFileDir;
	}

	public File getTempFolder() throws IOException {
		return tempFolder.getTempFolder();
	}

	public LogSystemInformationMode getLogSystemInformation() {
		LogSystemInformationMode defaultValue = LogSystemInformationMode.TRACE_ONLY;
		String value = getString(KEY_LOG_SYSTEM_INFO, defaultValue.toString());
		LogSystemInformationMode result = LogSystemInformationMode.typeOf(value);
		return result != null ? result : defaultValue;
	}

	public int getMencoderMaxThreads() {
		return Math.min(getInt(KEY_MENCODER_MAX_THREADS, getNumberOfCpuCores()), MENCODER_MAX_THREADS);
	}

	/**
	 * @return {@code true} if custom program paths are supported, {@code false}
	 *         otherwise.
	 */
	public boolean isCustomProgramPathsSupported() {
		return programPaths instanceof ConfigurableProgramPaths;
	}

	public boolean isAudioUpdateTag() {
		return getBoolean(KEY_AUDIO_UPDATE_RATING_TAG, false);
	}

	/**
	 * Returns the configured {@link ProgramExecutableType} for the specified
	 * {@link Player}. Note that this can be different from the
	 * {@link Player#currentExecutableType} for the same {@link Player}.
	 *
	 * @param player the {@link Player} for which to get the configured
	 *            {@link ProgramExecutableType}.
	 * @return The configured {@link ProgramExecutableType}, the default
	 *         {@link ProgramExecutableType} if none is configured or
	 *         {@code null} if there is no default.
	 *
	 * @see Player#getCurrentExecutableType()
	 */
	@Nullable
	public ProgramExecutableType getPlayerExecutableType(@Nonnull Player player) {
		if (player == null) {
			throw new IllegalArgumentException("player cannot be null");
		}

		ProgramExecutableType executableType = ProgramExecutableType.toProgramExecutableType(
			getString(player.getExecutableTypeKey(), null),
			player.getProgramInfo().getDefault()
		);

		// The default might also be null, in which case the current should be used.
		return executableType == null ? player.getCurrentExecutableType() : executableType;
	}

	/**
	 * Sets the configured {@link ProgramExecutableType} for the specified
	 * {@link Player}.
	 *
	 * @param player the {@link Player} for which to set the configured
	 *            {@link ProgramExecutableType}.
	 * @param executableType the {@link ProgramExecutableType} to set.
	 * @return {@code true} if a change was made, {@code false} otherwise.
	 */
	public boolean setPlayerExecutableType(@Nonnull Player player, @Nonnull ProgramExecutableType executableType) {
		if (player == null) {
			throw new IllegalArgumentException("player cannot be null");
		}

		if (executableType == null) {
			throw new IllegalArgumentException("executableType cannot be null");
		}

		String key = player.getExecutableTypeKey();
		if (key != null) {
			String currentValue = configuration.getString(key);
			String newValue = executableType.toRootString();
			if (newValue.equals(currentValue)) {
				return false;
			}

			configuration.setProperty(key, newValue);
			player.determineCurrentExecutableType(executableType);
			return true;
		}

		return false;
	}

	/**
	 * Gets the configured {@link Path} for the specified {@link PlayerId}. The
	 * {@link Player} must be registered. No check for existence or search in
	 * the OS path is performed.
	 *
	 * @param playerId the {@link PlayerId} for the registered {@link Player}
	 *            whose configured {@link Path} to get.
	 * @return The configured {@link Path} or {@code null} if missing, blank or
	 *         invalid.
	 */
	@Nullable
	public Path getPlayerCustomPath(@Nullable PlayerId playerId) {
		if (playerId == null) {
			return null;
		}

		return getPlayerCustomPath(PlayerFactory.getPlayer(playerId, false, false));
	}

	/**
	 * Gets the configured {@link Path} for the specified {@link Player}. No
	 * check for existence or search in the OS path is performed.
	 *
	 * @param player the {@link Player} whose configured {@link Path} to get.
	 * @return The configured {@link Path} or {@code null} if missing, blank or
	 *         invalid.
	 */
	@Nullable
	public Path getPlayerCustomPath(@Nullable Player player) {
		if (
			player == null ||
			isBlank(player.getConfigurablePathKey()) ||
			!(programPaths instanceof ConfigurableProgramPaths)
		) {
			return null;
		}

		try {
			return ((ConfigurableProgramPaths) programPaths).getCustomProgramPath(player.getConfigurablePathKey());
		} catch (ConfigurationException e) {
			LOGGER.warn(
				"An invalid executable path is configured for transcoding engine {}. The path is being ignored: {}",
				player,
				e.getMessage()
			);
			LOGGER.trace("", e);
			return null;
		}
	}

	/**
	 * Sets the custom executable {@link Path} for the specified {@link Player}
	 * in the configuration.
	 * <p>
	 * <b>Note:</b> This isn't normally what you'd want. To change the
	 * {@link Path} <b>for the {@link Player} instance</b> in the same
	 * operation, use {@link Player#setCustomExecutablePath} instead.
	 *
	 * @param player the {@link Player} whose custom executable {@link Path} to
	 *            set.
	 * @param path the {@link Path} to set or {@code null} to clear.
	 * @return {@code true} if a change was made to the configuration,
	 *         {@code false} otherwise.
	 * @throws IllegalStateException If {@code player} has no configurable path
	 *             key or custom program paths aren't supported.
	 */
	public boolean setPlayerCustomPath(@Nonnull Player player, @Nullable Path path) {
		if (player == null) {
			throw new IllegalArgumentException("player cannot be null");
		}

		if (isBlank(player.getConfigurablePathKey())) {
			throw new IllegalStateException(
				"Can't set custom executable path for player " + player + "because it has no configurable path key"
			);
		}

		if (!isCustomProgramPathsSupported()) {
			throw new IllegalStateException("The program paths aren't configurable");
		}

		return ((ConfigurableProgramPaths) programPaths).setCustomProgramPathConfiguration(
			path,
			player.getConfigurablePathKey()
		);
	}

	/**
	 * @return The {@link ExternalProgramInfo} for VLC.
	 */
	@Nullable
	public ExternalProgramInfo getVLCPaths() {
		return programPaths.getVLC();
	}

	/**
	 * @return The {@link ExternalProgramInfo} for MEncoder.
	 */
	@Nullable
	public ExternalProgramInfo getMEncoderPaths() {
		return programPaths.getMEncoder();
	}

	/**
	 * @return The {@link ExternalProgramInfo} for DCRaw.
	 */
	@Nullable
	public ExternalProgramInfo getDCRawPaths() {
		return programPaths.getDCRaw();
	}

	/**
	 * @return The {@link ExternalProgramInfo} for FFmpeg.
	 */
	@Nullable
	public ExternalProgramInfo getFFmpegPaths() {
		return programPaths.getFFmpeg();
	}

	/**
	 * @return The configured path to the FFmpeg executable.
	 */
	@Nullable
	public String getFFmpegPath() {
		Path executable = null;
		ExternalProgramInfo ffmpegPaths = getFFmpegPaths();
		if (ffmpegPaths != null) {
			executable = ffmpegPaths.getDefaultPath();
		}
		return executable == null ? null : executable.toString();
	}

	/**
	 * @return The {@link ExternalProgramInfo} for MPlayer.
	 */
	@Nullable
	public ExternalProgramInfo getMPlayerPaths() {
		return programPaths.getMPlayer();
	}

	/**
	 * @return The configured path to the MPlayer executable. If none is
	 *         configured, the default is used.
	 */
	@Nullable
	public String getMPlayerPath() {
		Path executable = null;
		ExternalProgramInfo mPlayerPaths = getMPlayerPaths();
		if (mPlayerPaths != null) {
			ProgramExecutableType executableType = ProgramExecutableType.toProgramExecutableType(
				ConfigurableProgramPaths.KEY_MPLAYER_EXECUTABLE_TYPE,
				mPlayerPaths.getDefault()
			);
			if (executableType != null) {
				executable = mPlayerPaths.getPath(executableType);
			}

			if (executable == null) {
				executable = mPlayerPaths.getDefaultPath();
			}
		}
		return executable == null ? null : executable.toString();
	}

	/**
	 * Sets a new {@link ProgramExecutableType#CUSTOM} {@link Path} for MPlayer
	 * both in {@link PmsConfiguration} and the {@link ExternalProgramInfo}.
	 *
	 * @param customPath the new {@link Path} or {@code null} to clear it.
	 */
	public void setCustomMPlayerPath(@Nullable Path customPath) {
		if (!isCustomProgramPathsSupported()) {
			throw new IllegalStateException("The program paths aren't configurable");
		}

		((ConfigurableProgramPaths) programPaths).setCustomMPlayerPath(customPath);
	}

	/**
	 * @return The {@link ExternalProgramInfo} for tsMuxeR.
	 */
	@Nullable
	public ExternalProgramInfo getTsMuxeRPaths() {
		return programPaths.getTsMuxeR();
	}

	/**
	 * @return The {@link ExternalProgramInfo} for tsMuxeRNew.
	 */
	@Nullable
	public ExternalProgramInfo getTsMuxeRNewPaths() {
		return programPaths.getTsMuxeRNew();
	}

	/**
	 * @return The configured path to the tsMuxeRNew executable. If none is
	 *         configured, the default is used.
	 */
	@Nullable
	public String getTsMuxeRNewPath() {
		Path executable = null;
		ExternalProgramInfo tsMuxeRNewPaths = getTsMuxeRNewPaths();
		if (tsMuxeRNewPaths != null) {
			ProgramExecutableType executableType = ProgramExecutableType.toProgramExecutableType(
				ConfigurableProgramPaths.KEY_TSMUXER_NEW_EXECUTABLE_TYPE,
				tsMuxeRNewPaths.getDefault()
			);
			if (executableType != null) {
				executable = tsMuxeRNewPaths.getPath(executableType);
			}

			if (executable == null) {
				executable = tsMuxeRNewPaths.getDefaultPath();
			}
		}
		return executable == null ? null : executable.toString();
	}

	/**
	 * Sets a new {@link ProgramExecutableType#CUSTOM} {@link Path} for
	 * "tsMuxeR new" both in {@link PmsConfiguration} and the
	 * {@link ExternalProgramInfo}.
	 *
	 * @param customPath the new {@link Path} or {@code null} to clear it.
	 */
	public void setCustomTsMuxeRNewPath(@Nullable Path customPath) {
		if (!isCustomProgramPathsSupported()) {
			throw new IllegalStateException("The program paths aren't configurable");
		}

		((ConfigurableProgramPaths) programPaths).setCustomTsMuxeRNewPath(customPath);
	}

	/**
	 * @return The {@link ExternalProgramInfo} for FLAC.
	 */
	@Nullable
	public ExternalProgramInfo getFLACPaths() {
		return programPaths.getFLAC();
	}

	/**
	 * @return The configured path to the FLAC executable. If none is
	 *         configured, the default is used.
	 */
	@Nullable
	public String getFLACPath() {
		Path executable = null;
		ExternalProgramInfo flacPaths = getFLACPaths();
		if (flacPaths != null) {
			ProgramExecutableType executableType = ProgramExecutableType.toProgramExecutableType(
				ConfigurableProgramPaths.KEY_FLAC_EXECUTABLE_TYPE,
				flacPaths.getDefault()
			);
			if (executableType != null) {
				executable = flacPaths.getPath(executableType);
			}

			if (executable == null) {
				executable = flacPaths.getDefaultPath();
			}
		}
		return executable == null ? null : executable.toString();
	}

	/**
	 * Sets a new {@link ProgramExecutableType#CUSTOM} {@link Path} for FLAC
	 * both in {@link PmsConfiguration} and the {@link ExternalProgramInfo}.
	 *
	 * @param customPath the new {@link Path} or {@code null} to clear it.
	 */
	public void setCustomFlacPath(@Nullable Path customPath) {
		if (!isCustomProgramPathsSupported()) {
			throw new IllegalStateException("The program paths aren't configurable");
		}

		((ConfigurableProgramPaths) programPaths).setCustomFlacPath(customPath);
	}

	/**
	 * @return The {@link ExternalProgramInfo} for Interframe.
	 */
	@Nullable
	public ExternalProgramInfo getInterFramePaths() {
		return programPaths.getInterFrame();
	}

	/**
	 * @return The configured path to the Interframe folder. If none is
	 *         configured, the default is used.
	 */
	@Nullable
	public String getInterFramePath() {
		Path executable = null;
		ExternalProgramInfo interFramePaths = getInterFramePaths();
		if (interFramePaths != null) {
			ProgramExecutableType executableType = ProgramExecutableType.toProgramExecutableType(
				ConfigurableProgramPaths.KEY_INTERFRAME_EXECUTABLE_TYPE,
				interFramePaths.getDefault()
			);
			if (executableType != null) {
				executable = interFramePaths.getPath(executableType);
			}

			if (executable == null) {
				executable = interFramePaths.getDefaultPath();
			}
		}
		return executable == null ? null : executable.toString();
	}

	/**
	 * Sets a new {@link ProgramExecutableType#CUSTOM} {@link Path} for
	 * Interframe both in {@link PmsConfiguration} and the
	 * {@link ExternalProgramInfo}.
	 *
	 * @param customPath the new {@link Path} or {@code null} to clear it.
	 */
	public void setCustomInterFramePath(@Nullable Path customPath) {
		if (!isCustomProgramPathsSupported()) {
			throw new IllegalStateException("The program paths aren't configurable");
		}

		((ConfigurableProgramPaths) programPaths).setCustomInterFramePath(customPath);
	}

	/**
	 * @return The {@link ExternalProgramInfo} for youtube-dl.
	 */
	@Nullable
	public ExternalProgramInfo getYoutubeDlPaths() {
		return programPaths.getYoutubeDl();
	}

	/**
	 * @return The configured path to the FLAC executable. If none is
	 *         configured, the default is used.
	 */
	@Nullable
	public String getYoutubeDlPath() {
		Path executable = null;
		ExternalProgramInfo youtubeDlPaths = getYoutubeDlPaths();
		if (youtubeDlPaths != null) {
			executable = youtubeDlPaths.getDefaultPath();
		}
		return executable != null ? executable.toString() : null;
	}

	/**
	 * If the framerate is not recognized correctly and the video runs too fast or too
	 * slow, tsMuxeR can be forced to parse the fps from FFmpeg. Default value is true.
	 * @return True if tsMuxeR should parse fps from FFmpeg.
	 */
	public boolean isTsmuxerForceFps() {
		return getBoolean(KEY_TSMUXER_FORCEFPS, true);
	}

	/**
	 * The bitrate for AC-3 audio transcoding.
	 *
	 * @return The user-specified AC-3 audio bitrate or 448
	 */
	public int getAudioBitrate() {
		return getInt(KEY_AUDIO_BITRATE, 448);
	}

	public String getApiKey() {
		return getString(KEY_API_KEY, "");
	}

	/**
	 * If the framerate is not recognized correctly and the video runs too fast or too
	 * slow, tsMuxeR can be forced to parse the fps from FFmpeg.
	 * @param value Set to true if tsMuxeR should parse fps from FFmpeg.
	 */
	public void setTsmuxerForceFps(boolean value) {
		configuration.setProperty(KEY_TSMUXER_FORCEFPS, value);
	}

	/**
	 * Get the MediaServer Engine version.
	 * @return the MediaServer engine version selected, or 0 for default.
	 */
	public int getServerEngine() {
		int value = getInt(KEY_SERVER_ENGINE, -1);
		if (value == -1) {
			//check old isHTTPEngineV2
			if (getBoolean(KEY_HTTP_ENGINE_V2, true)) {
				value = 0;
			} else {
				value = 1;
			}
			configuration.setProperty(KEY_SERVER_ENGINE, value);
		}
		return value;
	}

	public void setServerEngine(int value) {
		configuration.setProperty(KEY_SERVER_ENGINE, value);
	}

	/**
	 * The server port where PMS listens for TCP/IP traffic. Default value is 5001.
	 * @return The port number.
	 */
	public int getServerPort() {
		return getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
	}

	/**
	 * Set the server port where PMS must listen for TCP/IP traffic.
	 * @param value The TCP/IP port number.
	 */
	public void setServerPort(int value) {
		configuration.setProperty(KEY_SERVER_PORT, value);
	}

	/**
	 * The hostname of the server.
	 * @return The hostname if it is defined, otherwise <code>null</code>.
	 */
	public String getServerHostname() {
		return getString(KEY_SERVER_HOSTNAME, null);
	}

	/**
	 * Set the hostname of the server.
	 * @param value The hostname.
	 */
	public void setHostname(String value) {
		configuration.setProperty(KEY_SERVER_HOSTNAME, value);
	}

	public String getServerDisplayName() {
		if (isAppendProfileName()) {
			return String.format("%s [%s]", getString(KEY_SERVER_NAME, PMS.NAME), getProfileName());
		}

		return getString(KEY_SERVER_NAME, PMS.NAME);
	}

	/**
	 * The name of the server.
	 *
	 * @return The name of the server.
	 */
	public String getServerName() {
		return getString(KEY_SERVER_NAME, PMS.NAME);
	}

	/**
	 * Set the name of the server.
	 *
	 * @param value The name.
	 */
	public void setServerName(String value) {
		configuration.setProperty(KEY_SERVER_NAME, value);
	}

	/**
	 * Gets the language {@link String} as stored in the {@link PmsConfiguration}.
	 * May return <code>null</code>.
	 * @return The language {@link String}
	 */
	public String getLanguageRawString() {
		return configuration.getString(KEY_LANGUAGE);
	}

	/**
	 * Gets the {@link java.util.Locale} of the preferred language for the UMS
	 * user interface. The default is based on the default (OS) locale.
	 * @param log determines if any issues should be logged.
	 * @return The {@link java.util.Locale}.
	 */
	public final Locale getLanguageLocale(boolean log) {
		String languageCode = configuration.getString(KEY_LANGUAGE);
		Locale locale = null;
		if (languageCode != null && !languageCode.isEmpty()) {
			locale = Languages.toLocale(Locale.forLanguageTag(languageCode));
			if (log && locale == null) {
				LOGGER.error("Invalid or unsupported language tag \"{}\", defaulting to OS language.", languageCode);
			}

		} else if (log) {
			LOGGER.info("Language not specified, defaulting to OS language.");
		}

		if (locale == null) {
			locale = Languages.toLocale(Locale.getDefault());
			if (log && locale == null) {
				LOGGER.error("Unsupported language tag \"{}\", defaulting to US English.", Locale.getDefault().toLanguageTag());
			}
		}

		if (locale == null) {
			locale = Locale.forLanguageTag("en-US"); // Default
		}

		return locale;
	}

	/**
	 * Gets the {@link java.util.Locale} of the preferred language for the UMS
	 * user interface. The default is based on the default (OS) locale. Doesn't
	 * log potential issues.
	 * @return The {@link java.util.Locale}.
	 */
	public final Locale getLanguageLocale() {
		return getLanguageLocale(false);
	}

	/**
	 * Gets the {@link java.util.Locale} compatible tag of the preferred
	 * language for the UMS user interface. The default is based on the default (OS) locale.
	 * @return The <a href="https://en.wikipedia.org/wiki/IETF_language_tag">IEFT BCP 47</a> language tag.
	 */
	public String getLanguageTag() {
		return getLanguageLocale().toLanguageTag();
	}

	/**
	 * Sets the preferred language for the UMS user interface.
	 * @param locale The {@link java.net.Locale}.
	 */
	public void setLanguage(Locale locale) {
		if (locale != null) {
			if (Languages.isValid(locale)) {
				configuration.setProperty(KEY_LANGUAGE, Languages.toLanguageTag(locale));
				PMS.setLocale(Languages.toLocale(locale));
				//TODO: The line below should be removed once all calls to Locale.getDefault() is replaced with PMS.getLocale()
				Locale.setDefault(Languages.toLocale(locale));
			} else {
				LOGGER.error("setLanguage() aborted because of unsupported language tag \"{}\"", locale.toLanguageTag());
			}
		} else {
			configuration.setProperty(KEY_LANGUAGE, "");
		}
	}

	/**
	 * Sets the preferred language for the UMS user interface.
	 * @param value The <a href="https://en.wikipedia.org/wiki/IETF_language_tag">IEFT BCP 47</a> language tag.
	 */
	public void setLanguage(String value) {
		if (value != null && !value.isEmpty()) {
			setLanguage(Locale.forLanguageTag(value));
		} else {
			LOGGER.error("setLanguage() aborted because language tag is empty");
		}
	}

	/**
	 * Returns the preferred minimum size for the transcoding memory buffer in megabytes.
	 * Default value is 12.
	 * @return The minimum memory buffer size.
	 */
	public int getMinMemoryBufferSize() {
		return getInt(KEY_MIN_MEMORY_BUFFER_SIZE, 12);
	}

	/**
	 * Returns the preferred maximum size for the transcoding memory buffer in megabytes.
	 * The value returned has a top limit of {@link #MAX_MAX_MEMORY_BUFFER_SIZE}. Default
	 * value is 200.
	 *
	 * @return The maximum memory buffer size.
	 */
	public int getMaxMemoryBufferSize() {
		return Math.max(0, Math.min(maxMaxMemoryBufferSize, getInt(KEY_MAX_MEMORY_BUFFER_SIZE, 200)));
	}

	/**
	 * Set the preferred maximum for the transcoding memory buffer in megabytes. The top
	 * limit for the value is {@link #MAX_MAX_MEMORY_BUFFER_SIZE}.
	 *
	 * @param value The maximum buffer size.
	 */
	public void setMaxMemoryBufferSize(int value) {
		configuration.setProperty(KEY_MAX_MEMORY_BUFFER_SIZE, Math.max(0, Math.min(maxMaxMemoryBufferSize, value)));
	}

	/**
	 * Returns the font scale used for ASS subtitling. Default value is 1.4.
	 * @return The ASS font scale.
	 */
	public String getAssScale() {
		return getString(KEY_ASS_SCALE, "1.4");
	}

	/**
	 * Some versions of MEncoder produce garbled audio because the "ac3" codec is used
	 * instead of the "ac3_fixed" codec. Returns true if "ac3_fixed" should be used.
	 * Default is false.
	 * See https://code.google.com/p/ps3mediaserver/issues/detail?id=1092#c1
	 * @return True if "ac3_fixed" should be used.
	 */
	public boolean isMencoderAc3Fixed() {
		return getBoolean(KEY_MENCODER_AC3_FIXED, false);
	}

	/**
	 * Returns the margin used for ASS subtitling. Default value is 10.
	 * @return The ASS margin.
	 */
	public String getAssMargin() {
		return getString(KEY_ASS_MARGIN, "10");
	}

	/**
	 * Returns the outline parameter used for ASS subtitling. Default value is 1.
	 * @return The ASS outline parameter.
	 */
	public String getAssOutline() {
		return getString(KEY_ASS_OUTLINE, "1");
	}

	/**
	 * Returns the shadow parameter used for ASS subtitling. Default value is 1.
	 * @return The ASS shadow parameter.
	 */
	public String getAssShadow() {
		return getString(KEY_ASS_SHADOW, "1");
	}

	/**
	 * Returns the subfont text scale parameter used for subtitling without ASS.
	 * Default value is 3.
	 * @return The subfont text scale parameter.
	 */
	public String getMencoderNoAssScale() {
		return getString(KEY_MENCODER_NOASS_SCALE, "3");
	}

	/**
	 * Returns the subpos parameter used for subtitling without ASS.
	 * Default value is 2.
	 * @return The subpos parameter.
	 */
	public String getMencoderNoAssSubPos() {
		return getString(KEY_MENCODER_NOASS_SUBPOS, "2");
	}

	/**
	 * Returns the subfont blur parameter used for subtitling without ASS.
	 * Default value is 1.
	 * @return The subfont blur parameter.
	 */
	public String getMencoderNoAssBlur() {
		return getString(KEY_MENCODER_NOASS_BLUR, "1");
	}

	/**
	 * Returns the subfont outline parameter used for subtitling without ASS.
	 * Default value is 1.
	 * @return The subfont outline parameter.
	 */
	public String getMencoderNoAssOutline() {
		return getString(KEY_MENCODER_NOASS_OUTLINE, "1");
	}

	/**
	 * Set the subfont outline parameter used for subtitling without ASS.
	 * @param value The subfont outline parameter value to set.
	 */
	public void setMencoderNoAssOutline(String value) {
		configuration.setProperty(KEY_MENCODER_NOASS_OUTLINE, value);
	}

	/**
	 * Some versions of MEncoder produce garbled audio because the "ac3" codec is used
	 * instead of the "ac3_fixed" codec.
	 * See https://code.google.com/p/ps3mediaserver/issues/detail?id=1092#c1
	 * @param value Set to true if "ac3_fixed" should be used.
	 */
	public void setMencoderAc3Fixed(boolean value) {
		configuration.setProperty(KEY_MENCODER_AC3_FIXED, value);
	}

	/**
	 * Set the margin used for ASS subtitling.
	 * @param value The ASS margin value to set.
	 */
	public void setAssMargin(String value) {
		configuration.setProperty(KEY_ASS_MARGIN, value);
	}

	/**
	 * Set the outline parameter used for ASS subtitling.
	 * @param value The ASS outline parameter value to set.
	 */
	public void setAssOutline(String value) {
		configuration.setProperty(KEY_ASS_OUTLINE, value);
	}

	/**
	 * Set the shadow parameter used for ASS subtitling.
	 * @param value The ASS shadow parameter value to set.
	 */
	public void setAssShadow(String value) {
		configuration.setProperty(KEY_ASS_SHADOW, value);
	}

	/**
	 * Set the font scale used for ASS subtitling.
	 * @param value The ASS font scale value to set.
	 */
	public void setAssScale(String value) {
		configuration.setProperty(KEY_ASS_SCALE, value);
	}

	/**
	 * Set the subfont text scale parameter used for subtitling without ASS.
	 * @param value The subfont text scale parameter value to set.
	 */
	public void setMencoderNoAssScale(String value) {
		configuration.setProperty(KEY_MENCODER_NOASS_SCALE, value);
	}

	/**
	 * Set the subfont blur parameter used for subtitling without ASS.
	 * @param value The subfont blur parameter value to set.
	 */
	public void setMencoderNoAssBlur(String value) {
		configuration.setProperty(KEY_MENCODER_NOASS_BLUR, value);
	}

	/**
	 * Set the subpos parameter used for subtitling without ASS.
	 * @param value The subpos parameter value to set.
	 */
	public void setMencoderNoAssSubPos(String value) {
		configuration.setProperty(KEY_MENCODER_NOASS_SUBPOS, value);
	}

	/**
	 * Set the maximum number of concurrent MEncoder threads.
	 * XXX Currently unused.
	 * @param value The maximum number of concurrent threads.
	 */
	public void setMencoderMaxThreads(int value) {
		configuration.setProperty(KEY_MENCODER_MAX_THREADS, value);
	}

	/**
	 * Returns the number of seconds from the start of a video file (the seek
	 * position) where the thumbnail image for the movie should be extracted
	 * from. Default is 4 seconds.
	 *
	 * @return The seek position in seconds.
	 */
	public int getThumbnailSeekPos() {
		return getInt(KEY_THUMBNAIL_SEEK_POS, 4);
	}

	/**
	 * Sets the number of seconds from the start of a video file (the seek
	 * position) where the thumbnail image for the movie should be extracted
	 * from.
	 *
	 * @param value The seek position in seconds.
	 */
	public void setThumbnailSeekPos(int value) {
		configuration.setProperty(KEY_THUMBNAIL_SEEK_POS, value);
	}

	/**
	 * Returns whether the user wants ASS/SSA subtitle support. Default is
	 * true.
	 *
	 * @return True if MEncoder should use ASS/SSA support.
	 */
	public boolean isMencoderAss() {
		return getBoolean(KEY_MENCODER_ASS, true);
	}

	/**
	 * Returns whether or not subtitles should be disabled for all
	 * transcoding engines. Default is false, meaning subtitles should not
	 * be disabled.
	 *
	 * @return True if subtitles should be disabled, false otherwise.
	 */
	public boolean isDisableSubtitles() {
		return getBoolean(KEY_DISABLE_SUBTITLES, false);
	}

	/**
	 * Set whether or not subtitles should be disabled for
	 * all transcoding engines.
	 *
	 * @param value Set to true if subtitles should be disabled.
	 */
	public void setDisableSubtitles(boolean value) {
		configuration.setProperty(KEY_DISABLE_SUBTITLES, value);
	}

	/**
	 * Returns whether or not the Pulse Code Modulation audio format should be
	 * forced. The default is false.
	 * @return True if PCM should be forced, false otherwise.
	 */
	public boolean isAudioUsePCM() {
		return getBoolean(KEY_AUDIO_USE_PCM, false);
	}

	/**
	 * Returns whether or not the Pulse Code Modulation audio format should be
	 * used only for HQ audio codecs. The default is false.
	 * @return True if PCM should be used only for HQ audio codecs, false otherwise.
	 */
	public boolean isMencoderUsePcmForHQAudioOnly() {
		return getBoolean(KEY_MENCODER_USE_PCM_FOR_HQ_AUDIO_ONLY, false);
	}

	/**
	 * Returns the name of a TrueType font to use for subtitles.
	 * Default is <code>""</code>.
	 * @return The font name.
	 */
	public String getFont() {
		return getString(KEY_FONT, "");
	}

	/**
	 * Returns the audio language priority as a comma separated
	 * string. For example: <code>"eng,fre,jpn,ger,und"</code>, where "und"
	 * stands for "undefined".
	 * Can be a blank string.
	 * Default value is "loc,eng,fre,jpn,ger,und".
	 *
	 * @return The audio language priority string.
	 */
	public String getAudioLanguages() {
		return configurationReader.getPossiblyBlankConfigurationString(
				KEY_AUDIO_LANGUAGES,
				Messages.getString("AudioLanguages")
		);
	}

	/**
	 * Returns the subtitle language priority as a comma-separated
	 * string. For example: <code>"eng,fre,jpn,ger,und"</code>, where "und"
	 * stands for "undefined".
	 * Can be a blank string.
	 * Default value is a localized list (e.g. "eng,fre,jpn,ger,und").
	 *
	 * @return The subtitle language priority string.
	 */
	public String getSubtitlesLanguages() {
		return configurationReader.getPossiblyBlankConfigurationString(
				KEY_SUBTITLES_LANGUAGES,
				Messages.getString("SubtitlesLanguages")
		);
	}

	/**
	 * Returns the ISO 639 language code for the subtitle language that should
	 * be forced.
	 * Can be a blank string.
	 * @return The subtitle language code.
	 */
	public String getForcedSubtitleLanguage() {
		return configurationReader.getPossiblyBlankConfigurationString(
				KEY_FORCED_SUBTITLE_LANGUAGE,
				PMS.getLocale().getLanguage()
		);
	}

	/**
	 * Returns the tag string that identifies the subtitle language that
	 * should be forced.
	 * @return The tag string.
	 */
	public String getForcedSubtitleTags() {
		return getString(KEY_FORCED_SUBTITLE_TAGS, "forced");
	}

	/**
	 * Returns a string of audio language and subtitle language pairs
	 * ordered by priority to try to match. Audio language
	 * and subtitle language should be comma-separated as a pair,
	 * individual pairs should be semicolon separated. "*" can be used to
	 * match any language. Subtitle language can be defined as "off".
	 * Default value is <code>"*,*"</code>.
	 *
	 * @return The audio and subtitle languages priority string.
	 */
	public String getAudioSubLanguages() {
		return configurationReader.getPossiblyBlankConfigurationString(
				KEY_AUDIO_SUB_LANGS,
				Messages.getString("AudioSubtitlesPairs")
		);
	}

	/**
	 * Sets a string of audio language and subtitle language pairs
	 * ordered by priority to try to match. Audio language
	 * and subtitle language should be comma-separated as a pair,
	 * individual pairs should be semicolon separated. "*" can be used to
	 * match any language. Subtitle language can be defined as "off".
	 *
	 * Example: <code>"en,off;jpn,eng;*,eng;*;*"</code>.
	 *
	 * @param value The audio and subtitle languages priority string.
	 */
	public void setAudioSubLanguages(String value) {
		configuration.setProperty(KEY_AUDIO_SUB_LANGS, value);
	}

	/**
	 * Returns whether or not MEncoder should use FriBiDi mode, which
	 * is needed to display subtitles in languages that read from right to
	 * left, like Arabic, Farsi, Hebrew, Urdu, etc. Default value is false.
	 *
	 * @return True if FriBiDi mode should be used, false otherwise.
	 */
	public boolean isMencoderSubFribidi() {
		return getBoolean(KEY_MENCODER_SUB_FRIBIDI, false);
	}

	/**
	 * Returns the character encoding (or code page) that should used
	 * for displaying non-Unicode external subtitles. Default is empty string
	 * (do not force encoding with -subcp key).
	 *
	 * @return The character encoding.
	 */
	public String getSubtitlesCodepage() {
		return getString(KEY_SUBTITLES_CODEPAGE, "");
	}

	/**
	 * Whether MEncoder should use fontconfig for displaying subtitles.
	 *
	 * @return True if fontconfig should be used, false otherwise.
	 */
	public boolean isMencoderFontConfig() {
		return getBoolean(KEY_MENCODER_FONT_CONFIG, true);
	}

	/**
	 * Set to true if MEncoder should be forced to use the framerate that is
	 * parsed by FFmpeg.
	 *
	 * @param value Set to true if the framerate should be forced, false
	 *              otherwise.
	 */
	public void setMencoderForceFps(boolean value) {
		configuration.setProperty(KEY_MENCODER_FORCE_FPS, value);
	}

	/**
	 * Whether MEncoder should be forced to use the framerate that is
	 * parsed by FFmpeg.
	 *
	 * @return True if the framerate should be forced, false otherwise.
	 */
	public boolean isMencoderForceFps() {
		return getBoolean(KEY_MENCODER_FORCE_FPS, false);
	}

	/**
	 * Sets the audio language priority as a comma separated
	 * string. For example: <code>"eng,fre,jpn,ger,und"</code>, where "und"
	 * stands for "undefined".
	 * @param value The audio language priority string.
	 */
	public void setAudioLanguages(String value) {
		configuration.setProperty(KEY_AUDIO_LANGUAGES, value);
	}

	/**
	 * Sets the subtitle language priority as a comma-separated string.
	 *
	 * Example: <code>"eng,fre,jpn,ger,und"</code>, where "und" stands for
	 * "undefined".
	 *
	 * @param value The subtitle language priority string.
	 */
	public void setSubtitlesLanguages(String value) {
		configuration.setProperty(KEY_SUBTITLES_LANGUAGES, value);
	}

	/**
	 * Sets the ISO 639 language code for the subtitle language that should
	 * be forced.
	 *
	 * @param value The subtitle language code.
	 */
	public void setForcedSubtitleLanguage(String value) {
		configuration.setProperty(KEY_FORCED_SUBTITLE_LANGUAGE, value);
	}

	/**
	 * Sets the tag string that identifies the subtitle language that
	 * should be forced.
	 *
	 * @param value The tag string.
	 */
	public void setForcedSubtitleTags(String value) {
		configuration.setProperty(KEY_FORCED_SUBTITLE_TAGS, value);
	}

	/**
	 * Returns custom commandline options to pass on to MEncoder.
	 *
	 * @return The custom options string.
	 */
	public String getMencoderCustomOptions() {
		return getString(KEY_MENCODER_CUSTOM_OPTIONS, "");
	}

	/**
	 * Sets custom commandline options to pass on to MEncoder.
	 *
	 * @param value The custom options string.
	 */
	public void setMencoderCustomOptions(String value) {
		configuration.setProperty(KEY_MENCODER_CUSTOM_OPTIONS, value);
	}

	/**
	 * Sets the character encoding (or code page) that should be used
	 * for displaying non-Unicode external subtitles. Default is empty (autodetect).
	 *
	 * @param value The character encoding.
	 */
	public void setSubtitlesCodepage(String value) {
		configuration.setProperty(KEY_SUBTITLES_CODEPAGE, value);
	}

	/**
	 * Sets whether or not MEncoder should use FriBiDi mode, which
	 * is needed to display subtitles in languages that read from right to
	 * left, like Arabic, Farsi, Hebrew, Urdu, etc. Default value is false.
	 *
	 * @param value Set to true if FriBiDi mode should be used.
	 */
	public void setMencoderSubFribidi(boolean value) {
		configuration.setProperty(KEY_MENCODER_SUB_FRIBIDI, value);
	}

	/**
	 * Sets the name of a TrueType font to use for subtitles.
	 *
	 * @param value The font name.
	 */
	public void setFont(String value) {
		configuration.setProperty(KEY_FONT, value);
	}

	/**
	 * Older versions of MEncoder do not support ASS/SSA subtitles on all
	 * platforms. Set to true if MEncoder supports them. Default should be
	 * true on Windows and OS X, false otherwise.
	 * See https://code.google.com/p/ps3mediaserver/issues/detail?id=1097
	 *
	 * @param value Set to true if MEncoder supports ASS/SSA subtitles.
	 */
	public void setMencoderAss(boolean value) {
		configuration.setProperty(KEY_MENCODER_ASS, value);
	}

	/**
	 * Sets whether or not MEncoder should use fontconfig for displaying
	 * subtitles.
	 *
	 * @param value Set to true if fontconfig should be used.
	 */
	public void setMencoderFontConfig(boolean value) {
		configuration.setProperty(KEY_MENCODER_FONT_CONFIG, value);
	}

	/**
	 * Sets whether or not the Pulse Code Modulation audio format should be
	 * forced.
	 *
	 * @param value Set to true if PCM should be forced.
	 */
	public void setAudioUsePCM(boolean value) {
		configuration.setProperty(KEY_AUDIO_USE_PCM, value);
	}

	/**
	 * Sets whether or not the Pulse Code Modulation audio format should be
	 * used only for HQ audio codecs.
	 *
	 * @param value Set to true if PCM should be used only for HQ audio.
	 */
	public void setMencoderUsePcmForHQAudioOnly(boolean value) {
		configuration.setProperty(KEY_MENCODER_USE_PCM_FOR_HQ_AUDIO_ONLY, value);
	}

	/**
	 * Whether archives (e.g. .zip or .rar) should be browsable.
	 *
	 * @return True if archives should be browsable.
	 */
	public boolean isArchiveBrowsing() {
		return getBoolean(KEY_OPEN_ARCHIVES, false);
	}

	/**
	 * Sets whether archives (e.g. .zip or .rar) should be browsable.
	 *
	 * @param value Set to true if archives should be browsable.
	 */
	public void setArchiveBrowsing(boolean value) {
		configuration.setProperty(KEY_OPEN_ARCHIVES, value);
	}

	/**
	 * Returns true if MEncoder should use the deinterlace filter, false
	 * otherwise.
	 *
	 * @return True if the deinterlace filter should be used.
	 */
	public boolean isMencoderYadif() {
		return getBoolean(KEY_MENCODER_YADIF, false);
	}

	/**
	 * Set to true if MEncoder should use the deinterlace filter, false
	 * otherwise.
	 *
	 * @param value Set ot true if the deinterlace filter should be used.
	 */
	public void setMencoderYadif(boolean value) {
		configuration.setProperty(KEY_MENCODER_YADIF, value);
	}

	/**
	 * Whether MEncoder should be used to upscale the video to an
	 * optimal resolution. Default value is false, meaning the renderer will
	 * upscale the video itself.
	 *
	 * @return True if MEncoder should be used, false otherwise.
	 * @see #getMencoderScaleX()
	 * @see #getMencoderScaleY()
	 */
	public boolean isMencoderScaler() {
		return getBoolean(KEY_MENCODER_SCALER, false);
	}

	/**
	 * Set to true if MEncoder should be used to upscale the video to an
	 * optimal resolution. Set to false to leave upscaling to the renderer.
	 *
	 * @param value Set to true if MEncoder should be used to upscale.
	 * @see #setMencoderScaleX(int)
	 * @see #setMencoderScaleY(int)
	 */
	public void setMencoderScaler(boolean value) {
		configuration.setProperty(KEY_MENCODER_SCALER, value);
	}

	/**
	 * Returns the width in pixels to which a video should be scaled when
	 * {@link #isMencoderScaler()} returns true.
	 *
	 * @return The width in pixels.
	 */
	public int getMencoderScaleX() {
		return getInt(KEY_MENCODER_SCALEX, 0);
	}

	/**
	 * Sets the width in pixels to which a video should be scaled when
	 * {@link #isMencoderScaler()} returns true.
	 *
	 * @param value The width in pixels.
	 */
	public void setMencoderScaleX(int value) {
		configuration.setProperty(KEY_MENCODER_SCALEX, value);
	}

	/**
	 * Returns the height in pixels to which a video should be scaled when
	 * {@link #isMencoderScaler()} returns true.
	 *
	 * @return The height in pixels.
	 */
	public int getMencoderScaleY() {
		return getInt(KEY_MENCODER_SCALEY, 0);
	}

	/**
	 * Sets the height in pixels to which a video should be scaled when
	 * {@link #isMencoderScaler()} returns true.
	 *
	 * @param value The height in pixels.
	 */
	public void setMencoderScaleY(int value) {
		configuration.setProperty(KEY_MENCODER_SCALEY, value);
	}

	/**
	 * Returns the number of audio channels that should be used for
	 * transcoding. Default value is 6 (for 5.1 audio).
	 *
	 * @return The number of audio channels.
	 */
	public int getAudioChannelCount() {
		int valueFromUserConfig = getInt(KEY_AUDIO_CHANNEL_COUNT, 6);

		if (valueFromUserConfig != 6 && valueFromUserConfig != 2) {
			return 6;
		}

		return valueFromUserConfig;
	}

	/**
	 * Sets the number of audio channels that MEncoder should use for
	 * transcoding.
	 *
	 * @param value The number of audio channels.
	 */
	public void setAudioChannelCount(int value) {
		if (value != 6 && value != 2) {
			value = 6;
		}
		configuration.setProperty(KEY_AUDIO_CHANNEL_COUNT, value);
	}

	/**
	 * Sets the AC3 audio bitrate, which determines the quality of digital
	 * audio sound. An AV-receiver or amplifier has to be capable of playing
	 * this quality.
	 *
	 * @param value The AC3 audio bitrate.
	 */
	public void setAudioBitrate(int value) {
		configuration.setProperty(KEY_AUDIO_BITRATE, value);
	}

	/**
	 * Returns the maximum video bitrate to be used by MEncoder and FFmpeg.
	 *
	 * @return The maximum video bitrate.
	 */
	public String getMaximumBitrate() {
		String maximumBitrate = getMaximumBitrateDisplay();
		if ("0".equals(maximumBitrate)) {
			maximumBitrate = "1000";
		}

		return maximumBitrate;
	}

	/**
	 * The same as getMaximumBitrate() but this value is displayed to the user
	 * because for our own uses we turn the value "0" into the value "1000" but
	 * that can be confusing for the user.
	 *
	 * @return The maximum video bitrate to display in the GUI.
	 */
	public String getMaximumBitrateDisplay() {
		return getString(KEY_MAX_BITRATE, "90");
	}

	/**
	 * Sets the maximum video bitrate to be used by MEncoder.
	 *
	 * @param value The maximum video bitrate.
	 */
	public void setMaximumBitrate(String value) {
		configuration.setProperty(KEY_MAX_BITRATE, value);
	}

	/**
	 * @return The selected renderers as a list.
	 */
	public List<String> getSelectedRenderers() {
		return getStringList(KEY_SELECTED_RENDERERS, allRenderers);
	}

	/**
	 * @param value The comma-separated list of selected renderers.
	 * @return {@code true} if this call changed the {@link Configuration},
	 *         {@code false} otherwise.
	 */
	public boolean setSelectedRenderers(String value) {
		if (value.isEmpty()) {
			value = "None";
		}

		if (!value.equals(configuration.getString(KEY_SELECTED_RENDERERS, null))) {
			configuration.setProperty(KEY_SELECTED_RENDERERS, value);
			return true;
		}

		return false;
	}

	/**
	 * @param value a string list of renderers.
	 * @return {@code true} if this call changed the {@link Configuration},
	 *         {@code false} otherwise.
	 */
	public boolean setSelectedRenderers(List<String> value) {
		if (value == null) {
			return setSelectedRenderers("");
		}

		List<String> currentValue = getStringList(KEY_SELECTED_RENDERERS, null);
		if (currentValue == null || value.size() != currentValue.size() || !value.containsAll(currentValue)) {
			setStringList(KEY_SELECTED_RENDERERS, value);
			return true;
		}

		return false;
	}

	/**
	 * Returns true if thumbnail generation is enabled, false otherwise.
	 *
	 * @return boolean indicating whether thumbnail generation is enabled.
	 */
	public boolean isThumbnailGenerationEnabled() {
		return getBoolean(KEY_THUMBNAIL_GENERATION_ENABLED, true);
	}

	/**
	 * Sets the thumbnail generation option.
	 *
	 * @param value True if thumbnails could be generated.
	 */
	public void setThumbnailGenerationEnabled(boolean value) {
		configuration.setProperty(KEY_THUMBNAIL_GENERATION_ENABLED, value);
	}

	/**
	 * Returns true if PMS should generate thumbnails for images. Default value
	 * is true.
	 *
	 * @return True if image thumbnails should be generated.
	 */
	public boolean getImageThumbnailsEnabled() {
		return getBoolean(KEY_IMAGE_THUMBNAILS_ENABLED, true);
	}

	/**
	 * Set to true if PMS should generate thumbnails for images.
	 *
	 * @param value True if image thumbnails should be generated.
	 */
	public void setImageThumbnailsEnabled(boolean value) {
		configuration.setProperty(KEY_IMAGE_THUMBNAILS_ENABLED, value);
	}

	/**
	 * Returns the number of CPU cores that should be used for transcoding.
	 *
	 * @return The number of CPU cores.
	 */
	public int getNumberOfCpuCores() {
		int nbcores = Runtime.getRuntime().availableProcessors();
		if (nbcores < 1) {
			nbcores = 1;
		}

		return getInt(KEY_NUMBER_OF_CPU_CORES, nbcores);
	}

	/**
	 * Sets the number of CPU cores that should be used for transcoding. The
	 * maximum value depends on the physical available count of "real processor
	 * cores". That means hyperthreading virtual CPU cores do not count! If you
	 * are not sure, analyze your CPU with the free tool CPU-z on Windows
	 * systems. On Linux have a look at the virtual proc-filesystem: in the
	 * file "/proc/cpuinfo" you will find more details about your CPU. You also
	 * get much information about CPUs from AMD and Intel from their Wikipedia
	 * articles.
	 * <p>
	 * PMS will detect and set the correct amount of cores as the default value.
	 *
	 * @param value The number of CPU cores.
	 */
	public void setNumberOfCpuCores(int value) {
		configuration.setProperty(KEY_NUMBER_OF_CPU_CORES, value);
	}

	/**
	 * Whether we should start minimized, i.e. without its window opened.
	 * Always returns false on macOS since it makes it impossible(?) for the
	 * program to open.
	 *
	 * @return whether we should start minimized
	 */
	public boolean isMinimized() {
		return getBoolean(KEY_MINIMIZED, false);
	}

	/**
	 * Whether we should start minimized, i.e. without its window opened.
	 *
	 * @param value whether we should start minimized, false otherwise.
	 */
	public void setMinimized(boolean value) {
		configuration.setProperty(KEY_MINIMIZED, value);
	}

	/**
	 * Returns true if UMS should automatically start on Windows.
	 *
	 * @return True if UMS should start automatically, false otherwise.
	 */
	public boolean isAutoStart() {
		if (Platform.isWindows()) {
			File f = new File(WindowsRegistry.readRegistry("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Common Startup") + "\\Universal Media Server.lnk");

			if (f.exists()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Set to true if UMS should automatically start on Windows.
	 *
	 * @param value True if UMS should start automatically, false otherwise.
	 */
	public void setAutoStart(boolean value) {
		File sourceFile = new File(WindowsRegistry.readRegistry("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Common Programs") + "\\Universal Media Server.lnk");
		File destinationFile = new File(WindowsRegistry.readRegistry("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Common Startup") + "\\Universal Media Server.lnk");

		if (value) {
			try {
				FileUtils.copyFile(sourceFile, destinationFile);
				if (destinationFile.exists()) {
					LOGGER.info("UMS will start automatically with Windows");
				} else {
					LOGGER.info("An error occurred while trying to make UMS start automatically with Windows");
				}

			} catch (IOException e) {
				if (!FileUtil.isAdmin()) {
					try {
						GuiManager.showErrorMessage(Messages.getString("UmsMustRunAdministrator"), Messages.getString("PermissionsError"));
					} catch (NullPointerException e2) {
						// This happens on the initial program load, ignore it
					}
				} else {
					LOGGER.info("An error occurred while trying to make UMS start automatically with Windows");
				}
			}
		} else {
			if (destinationFile.delete()) {
				LOGGER.info("UMS will not start automatically with Windows");
			} else {
				LOGGER.info("An error occurred while trying to make UMS not start automatically with Windows");
			}
		}
	}

	/**
	 * Whether we should check for external subtitle files with the same
	 * name as the media (*.srt, *.sub, *.ass, etc.).
	 *
	 * Note: This will return true if either the autoload external subtitles
	 * setting is enabled or the force external subtitles setting is enabled
	 *
	 * @return Whether we should check for external subtitle files.
	 */
	public boolean isAutoloadExternalSubtitles() {
		return getBoolean(KEY_AUTOLOAD_SUBTITLES, true) || isForceExternalSubtitles();
	}

	/**
	 * Whether we should check for external subtitle files with the same
	 * name as the media (*.srt, *.sub, *.ass etc.).
	 *
	 * @param value Whether we should check for external subtitle files.
	 */
	public void setAutoloadExternalSubtitles(boolean value) {
		configuration.setProperty(KEY_AUTOLOAD_SUBTITLES, value);
	}

	/**
	 * Whether we should force external subtitles with the same name as the
	 * media (*.srt, *.sub, *.ass, etc.) to display, regardless of whether
	 * language preferences disable them.
	 *
	 * @return Whether we should force external subtitle files.
	 */
	public boolean isForceExternalSubtitles() {
		return getBoolean(KEY_FORCE_EXTERNAL_SUBTITLES, true);
	}

	/**
	 * Whether we should force external subtitles with the same name as the
	 * media (*.srt, *.sub, *.ass, etc.) to display, regardless of whether
	 * language preferences disable them.
	 *
	 * @param value Whether we should force external subtitle files.
	 */
	public void setForceExternalSubtitles(boolean value) {
		configuration.setProperty(KEY_FORCE_EXTERNAL_SUBTITLES, value);
	}

	/**
	 * Whether to show the "Server Settings" folder on the renderer.
	 *
	 * @return whether the folder is shown
	 */
	public boolean isShowServerSettingsFolder() {
		return getBoolean(KEY_SHOW_SERVER_SETTINGS_FOLDER, false);
	}

	/**
	 * Whether to show the "Server Settings" folder on the renderer.
	 *
	 * @param value whether the folder is shown
	 */
	public void setShowServerSettingsFolder(boolean value) {
		configuration.setProperty(KEY_SHOW_SERVER_SETTINGS_FOLDER, value);
	}

	/**
	 * Gets the {@link FullyPlayedAction}.
	 *
	 * @return What to do with a file after it has been fully played
	 */
	public FullyPlayedAction getFullyPlayedAction() {
		return FullyPlayedAction.typeOf(getInt(KEY_FULLY_PLAYED_ACTION, FullyPlayedAction.MARK.getValue()), FullyPlayedAction.MARK);
	}

	/**
	 * Sets the {@link FullyPlayedAction}.
	 *
	 * @param action what to do with a file after it has been fully played
	 */
	public void setFullyPlayedAction(FullyPlayedAction action) {
		configuration.setProperty(KEY_FULLY_PLAYED_ACTION, action.getValue());
	}

	/**
	 * Returns the folder to move fully played files to.
	 *
	 * @see #getFullyPlayedAction()
	 * @return The folder to move fully played files to
	 */
	public String getFullyPlayedOutputDirectory() {
		return getString(KEY_FULLY_PLAYED_OUTPUT_DIRECTORY, "");
	}

	/**
	 * Sets the folder to move fully played files to.
	 *
	 * @see #getFullyPlayedAction()
	 * @param value the folder to move fully played files to
	 */
	public void setFullyPlayedOutputDirectory(String value) {
		configuration.setProperty(KEY_FULLY_PLAYED_OUTPUT_DIRECTORY, value);
	}

	/**
	 * Returns true if PMS should cache scanned media in its internal database,
	 * speeding up later retrieval. When false is returned, PMS will not use
	 * cache and media will have to be rescanned.
	 *
	 * @return True if PMS should cache media.
	 */
	public boolean getUseCache() {
		return getBoolean(KEY_USE_CACHE, true);
	}

	/**
	 * Set to true if PMS should cache scanned media in its internal database,
	 * speeding up later retrieval.
	 *
	 * @param value True if PMS should cache media.
	 */
	public void setUseCache(boolean value) {
		configuration.setProperty(KEY_USE_CACHE, value);
	}

	/**
	 * Whether we should pass the flag "convertfps=true" to AviSynth.
	 *
	 * @param value True if we should pass the flag.
	 */
	public void setAvisynthConvertFps(boolean value) {
		configuration.setProperty(KEY_AVISYNTH_CONVERT_FPS, value);
	}

	/**
	 * Returns true if we should pass the flag "convertfps=true" to AviSynth.
	 *
	 * @return True if we should pass the flag.
	 */
	public boolean getAvisynthConvertFps() {
		return getBoolean(KEY_AVISYNTH_CONVERT_FPS, true);
	}

	public void setAvisynthInterFrame(boolean value) {
		configuration.setProperty(KEY_AVISYNTH_INTERFRAME, value);
	}

	public boolean getAvisynthInterFrame() {
		return getBoolean(KEY_AVISYNTH_INTERFRAME, false);
	}

	public void setAvisynthInterFrameGPU(boolean value) {
		configuration.setProperty(KEY_AVISYNTH_INTERFRAME_GPU, value);
	}

	public boolean getAvisynthInterFrameGPU() {
		return getBoolean(KEY_AVISYNTH_INTERFRAME_GPU, false);
	}

	public void setAvisynthMultiThreading(boolean value) {
		configuration.setProperty(KEY_AVISYNTH_MULTITHREADING, value);
	}

	public boolean getAvisynthMultiThreading() {
		return getBoolean(KEY_AVISYNTH_MULTITHREADING, false);
	}

	/**
	 * Returns the template for the AviSynth script. The script string can
	 * contain the character "\u0001", which should be treated as the newline
	 * separator character.
	 *
	 * @return The AviSynth script template.
	 */
	public String getAvisynthScript() {
		return getString(KEY_AVISYNTH_SCRIPT, defaultAviSynthScript);
	}

	/**
	 * Sets the template for the AviSynth script. The script string may contain
	 * the character "\u0001", which will be treated as newline character.
	 *
	 * @param value The AviSynth script template.
	 */
	public void setAvisynthScript(String value) {
		configuration.setProperty(KEY_AVISYNTH_SCRIPT, value);
	}

	/**
	 * Returns additional codec specific configuration options for MEncoder.
	 *
	 * @return The configuration options.
	 */
	public String getMencoderCodecSpecificConfig() {
		return getString(KEY_MENCODER_CODEC_SPECIFIC_SCRIPT, "");
	}

	/**
	 * Sets additional codec specific configuration options for MEncoder.
	 *
	 * @param value The additional configuration options.
	 */
	public void setMencoderCodecSpecificConfig(String value) {
		configuration.setProperty(KEY_MENCODER_CODEC_SPECIFIC_SCRIPT, value);
	}

	/**
	 * Returns the maximum size (in MB) that PMS should use for buffering
	 * audio.
	 *
	 * @return The maximum buffer size.
	 */
	public int getMaxAudioBuffer() {
		return getInt(KEY_MAX_AUDIO_BUFFER, 100);
	}

	/**
	 * Returns the minimum size (in MB) that PMS should use for the buffer used
	 * for streaming media.
	 *
	 * @return The minimum buffer size.
	 */
	public int getMinStreamBuffer() {
		return getInt(KEY_MIN_STREAM_BUFFER, 1);
	}

	/**
	 * Converts the getMPEG2MainSettings() from MEncoder's format to FFmpeg's.
	 *
	 * @return MPEG-2 settings formatted for FFmpeg.
	 */
	public String getMPEG2MainSettingsFFmpeg() {
		String mpegSettings = getMPEG2MainSettings();
		if (StringUtils.isBlank(mpegSettings) || mpegSettings.contains("Automatic")) {
			return mpegSettings;
		}

		return convertMencoderSettingToFFmpegFormat(mpegSettings);
	}

	public void setFFmpegLoggingLevel(String value) {
		configuration.setProperty(KEY_FFMPEG_LOGGING_LEVEL, value);
	}

	public String getFFmpegLoggingLevel() {
		return getString(KEY_FFMPEG_LOGGING_LEVEL, "fatal");
	}

	public void setFfmpegMultithreading(boolean value) {
		configuration.setProperty(KEY_FFMPEG_MULTITHREADING, value);
	}

	public boolean isFfmpegMultithreading() {
		boolean isMultiCore = getNumberOfCpuCores() > 1;
		return getBoolean(KEY_FFMPEG_MULTITHREADING, isMultiCore);
	}

	public String getFFmpegGPUDecodingAccelerationMethod() {
		return getString(KEY_FFMPEG_GPU_DECODING_ACCELERATION_METHOD, Messages.getString("None_lowercase"));
	}

	public void setFFmpegGPUDecodingAccelerationMethod(String value) {
		configuration.setProperty(KEY_FFMPEG_GPU_DECODING_ACCELERATION_METHOD, value);
	}

	public String getFFmpegGPUDecodingAccelerationThreadNumber() {
		return getString(KEY_FFMPEG_GPU_DECODING_ACCELERATION_THREAD_NUMBER, "1");
	}

	public void setFFmpegGPUDecodingAccelerationThreadNumber(String value) {
		configuration.setProperty(KEY_FFMPEG_GPU_DECODING_ACCELERATION_THREAD_NUMBER, value);
	}

	public String[] getFFmpegAvailableGPUDecodingAccelerationMethods() {
		return getString(KEY_FFMPEG_AVAILABLE_GPU_ACCELERATION_METHODS, Messages.getString("None_lowercase")).split(",");
	}

	public void setFFmpegAvailableGPUDecodingAccelerationMethods(List<String> methods) {
		configuration.setProperty(KEY_FFMPEG_AVAILABLE_GPU_ACCELERATION_METHODS, collectionToString(methods));
	}

	public void setFfmpegAviSynthMultithreading(boolean value) {
		configuration.setProperty(KEY_FFMPEG_AVISYNTH_MULTITHREADING, value);
	}

	public boolean isFfmpegAviSynthMultithreading() {
		boolean isMultiCore = getNumberOfCpuCores() > 1;
		return getBoolean(KEY_FFMPEG_AVISYNTH_MULTITHREADING, isMultiCore);
	}

	/**
	 * Whether we should pass the flag "convertfps=true" to AviSynth.
	 *
	 * @param value True if we should pass the flag.
	 */
	public void setFfmpegAvisynthConvertFps(boolean value) {
		configuration.setProperty(KEY_AVISYNTH_CONVERT_FPS, value);
	}

	/**
	 * Returns true if we should pass the flag "convertfps=true" to AviSynth.
	 *
	 * @return True if we should pass the flag.
	 */
	public boolean getFfmpegAvisynthConvertFps() {
		return getBoolean(KEY_FFMPEG_AVISYNTH_CONVERT_FPS, true);
	}

	public void setFfmpegAvisynthInterFrame(boolean value) {
		configuration.setProperty(KEY_FFMPEG_AVISYNTH_INTERFRAME, value);
	}

	public boolean getFfmpegAvisynthInterFrame() {
		return getBoolean(KEY_FFMPEG_AVISYNTH_INTERFRAME, false);
	}

	public void setFfmpegAvisynthInterFrameGPU(boolean value) {
		configuration.setProperty(KEY_FFMPEG_AVISYNTH_INTERFRAME_GPU, value);
	}

	public boolean getFfmpegAvisynthInterFrameGPU() {
		return getBoolean(KEY_FFMPEG_AVISYNTH_INTERFRAME_GPU, false);
	}

	public boolean isMencoderNoOutOfSync() {
		return getBoolean(KEY_MENCODER_NO_OUT_OF_SYNC, true);
	}

	public void setMencoderNoOutOfSync(boolean value) {
		configuration.setProperty(KEY_MENCODER_NO_OUT_OF_SYNC, value);
	}

	public boolean getTrancodeBlocksMultipleConnections() {
		return getBoolean(KEY_TRANSCODE_BLOCKS_MULTIPLE_CONNECTIONS, false);
	}

	public void setTranscodeBlocksMultipleConnections(boolean value) {
		configuration.setProperty(KEY_TRANSCODE_BLOCKS_MULTIPLE_CONNECTIONS, value);
	}

	public boolean getTrancodeKeepFirstConnections() {
		return getBoolean(KEY_TRANSCODE_KEEP_FIRST_CONNECTION, true);
	}

	public void setTrancodeKeepFirstConnections(boolean value) {
		configuration.setProperty(KEY_TRANSCODE_KEEP_FIRST_CONNECTION, value);
	}

	public boolean isMencoderIntelligentSync() {
		return getBoolean(KEY_MENCODER_INTELLIGENT_SYNC, true);
	}

	public void setMencoderIntelligentSync(boolean value) {
		configuration.setProperty(KEY_MENCODER_INTELLIGENT_SYNC, value);
	}

	public boolean getSkipLoopFilterEnabled() {
		return getBoolean(KEY_SKIP_LOOP_FILTER_ENABLED, false);
	}

	/**
	 * The list of network interfaces that should be skipped when checking
	 * for an available network interface. Entries should be comma separated
	 * and typically exclude the number at the end of the interface name.
	 * <p>
	 * Default is to skip the interfaces created by Virtualbox, OpenVPN and
	 * Parallels: "tap,vmnet,vnic,virtualbox".
	 * @return The string of network interface names to skip.
	 */
	public List<String> getSkipNetworkInterfaces() {
		return getStringList(KEY_SKIP_NETWORK_INTERFACES, "tap,vmnet,vnic,virtualbox");
	}

	public void setSkipLoopFilterEnabled(boolean value) {
		configuration.setProperty(KEY_SKIP_LOOP_FILTER_ENABLED, value);
	}

	public String getMPEG2MainSettings() {
		return getString(KEY_MPEG2_MAIN_SETTINGS, "Automatic (Wired)");
	}

	public void setMPEG2MainSettings(String value) {
		configuration.setProperty(KEY_MPEG2_MAIN_SETTINGS, value);
	}

	public String getx264ConstantRateFactor() {
		return getString(KEY_X264_CONSTANT_RATE_FACTOR, "Automatic (Wired)");
	}

	public void setx264ConstantRateFactor(String value) {
		configuration.setProperty(KEY_X264_CONSTANT_RATE_FACTOR, value);
	}

	public String getMencoderVobsubSubtitleQuality() {
		return getString(KEY_MENCODER_VOBSUB_SUBTITLE_QUALITY, "3");
	}

	public void setMencoderVobsubSubtitleQuality(String value) {
		configuration.setProperty(KEY_MENCODER_VOBSUB_SUBTITLE_QUALITY, value);
	}

	public String getMencoderOverscanCompensationWidth() {
		return getString(KEY_MENCODER_OVERSCAN_COMPENSATION_WIDTH, "0");
	}

	public void setMencoderOverscanCompensationWidth(String value) {
		if (value.trim().length() == 0) {
			value = "0";
		}

		configuration.setProperty(KEY_MENCODER_OVERSCAN_COMPENSATION_WIDTH, value);
	}

	public String getMencoderOverscanCompensationHeight() {
		return getString(KEY_MENCODER_OVERSCAN_COMPENSATION_HEIGHT, "0");
	}

	public void setMencoderOverscanCompensationHeight(String value) {
		if (value.trim().length() == 0) {
			value = "0";
		}

		configuration.setProperty(KEY_MENCODER_OVERSCAN_COMPENSATION_HEIGHT, value);
	}

	/**
	 * Lazy implementation, call before accessing {@link #enabledEngines}.
	 */
	private void buildEnabledEngines() {
		if (enabledEnginesBuilt) {
			return;
		}

		ENABLED_ENGINES_LOCK.writeLock().lock();
		try {
			// Not a bug, using double checked locking
			if (enabledEnginesBuilt) {
				return;
			}

			String engines = configuration.getString(KEY_ENGINES);
			enabledEngines = stringToPlayerIdSet(engines);
			if (isBlank(engines)) {
				configuration.setProperty(KEY_ENGINES, collectionToString(enabledEngines));
			}

			enabledEnginesBuilt = true;
		} finally {
			ENABLED_ENGINES_LOCK.writeLock().unlock();
		}
	}

	/**
	 * Gets a {@link UniqueList} of the {@link PlayerId}s in no particular
	 * order. Returns a new instance, any modifications won't affect original
	 * list.
	 *
	 * @return A copy of the {@link List} of {@link PlayerId}s.
	 */
	public List<PlayerId> getEnabledEngines() {
		buildEnabledEngines();
		ENABLED_ENGINES_LOCK.readLock().lock();
		try {
			return new ArrayList<>(enabledEngines);
		} finally {
			ENABLED_ENGINES_LOCK.readLock().unlock();
		}
	}

	/**
	 * Gets the enabled status of the specified {@link PlayerId}.
	 *
	 * @param id the {@link PlayerId} to check.
	 * @return {@code true} if the {@link Player} with {@code id} is enabled,
	 *         {@code false} otherwise.
	 */
	public boolean isEngineEnabled(PlayerId id) {
		if (id == null) {
			throw new NullPointerException("id cannot be null");
		}

		buildEnabledEngines();
		ENABLED_ENGINES_LOCK.readLock().lock();
		try {
			return enabledEngines.contains(id);
		} finally {
			ENABLED_ENGINES_LOCK.readLock().unlock();
		}
	}

	/**
	 * Gets the enabled status of the specified {@link Player}.
	 *
	 * @param player the {@link Player} to check.
	 * @return {@code true} if {@code player} is enabled, {@code false}
	 *         otherwise.
	 */
	public boolean isEngineEnabled(Player player) {
		if (player == null) {
			throw new NullPointerException("player cannot be null");
		}

		return isEngineEnabled(player.id());
	}

	/**
	 * Sets the enabled status of the specified {@link PlayerId}.
	 *
	 * @param id the {@link PlayerId} whose enabled status to set.
	 * @param enabled the enabled status to set.
	 */
	public void setEngineEnabled(PlayerId id, boolean enabled) {
		if (id == null) {
			throw new IllegalArgumentException("Unrecognized id");
		}

		ENABLED_ENGINES_LOCK.writeLock().lock();
		try {
			buildEnabledEngines();
			if (enabledEngines.contains(id)) {
				if (!enabled) {
					enabledEngines.remove(id);
				}
			} else {
				if (enabled) {
					enabledEngines.add(id);
				}
			}
			configuration.setProperty(KEY_ENGINES, collectionToString(enabledEngines));
		} finally {
			ENABLED_ENGINES_LOCK.writeLock().unlock();
		}
	}

	/**
	 * Sets the enabled status of the specified {@link Player}.
	 *
	 * @param player the {@link Player} whose enabled status to set.
	 * @param enabled the enabled status to set.
	 */
	public void setEngineEnabled(Player player, boolean enabled) {
		setEngineEnabled(player.id(), enabled);
	}

	/**
	 * This is to make sure that any incorrect capitalization in the
	 * configuration file is corrected. This should only need to be called from
	 * {@link PlayerFactory#registerPlayer(Player)}.
	 *
	 * @param player the {@link Player} for which to assure correct
	 *            capitalization.
	 */
	public void capitalizeEngineId(Player player) {
		if (player == null) {
			throw new NullPointerException("player cannot be null");
		}

		String engines = configuration.getString(KEY_ENGINES);
		if (StringUtils.isNotBlank(engines)) {
			String capitalizedEngines = StringUtil.caseReplace(engines.trim(), player.id().toString());
			if (!engines.equals(capitalizedEngines)) {
				configuration.setProperty(KEY_ENGINES, capitalizedEngines);
			}
		}

		engines = configuration.getString(KEY_ENGINES_PRIORITY);
		if (StringUtils.isNotBlank(engines)) {
			String capitalizedEngines = StringUtil.caseReplace(engines.trim(), player.id().toString());
			if (!engines.equals(capitalizedEngines)) {
				configuration.setProperty(KEY_ENGINES_PRIORITY, capitalizedEngines);
			}
		}
	}

	/**
	 * Lazy implementation, call before accessing {@link #enginesPriority}.
	 */
	private void buildEnginesPriority() {
		if (enginesPriorityBuilt) {
			return;
		}
		ENGINES_PRIORITY_LOCK.writeLock().lock();
		try {
			// Not a bug, using double checked locking
			if (enginesPriorityBuilt) {
				return;
			}

			String enginesPriorityString = configuration.getString(KEY_ENGINES_PRIORITY);
			enginesPriority = stringToPlayerIdSet(enginesPriorityString);
			if (isBlank(enginesPriorityString)) {
				configuration.setProperty(KEY_ENGINES_PRIORITY, collectionToString(enginesPriority));
			}

			enginesPriorityBuilt = true;
		} finally {
			ENGINES_PRIORITY_LOCK.writeLock().unlock();
		}
	}

	/**
	 * Returns the priority index according to the rules of {@link List#indexOf}.
	 *
	 * @param id the {@link PlayerId} whose position to return.
	 * @return The priority index of {@code id}, or {@code -1} if the priority
	 *         list doesn't contain {@code id}.
	 */
	public int getEnginePriority(PlayerId id) {
		if (id == null) {
			throw new NullPointerException("id cannot be null");
		}

		buildEnginesPriority();
		ENGINES_PRIORITY_LOCK.readLock().lock();
		try {
			int index = enginesPriority.indexOf(id);
			if (index >= 0) {
				return index;
			}
		} finally {
			ENGINES_PRIORITY_LOCK.readLock().unlock();
		}

		// The engine isn't listed, add it last
		ENGINES_PRIORITY_LOCK.writeLock().lock();
		try {
			enginesPriority.add(id);
			return enginesPriority.indexOf(id);
		} finally {
			ENGINES_PRIORITY_LOCK.writeLock().unlock();
		}
	}

	/**
	 * Returns the priority index according to the rules of {@link List#indexOf}.
	 *
	 * @param player the {@link Player} whose position to return.
	 * @return the priority index of {@code player}, or -1 if this the priority
	 *         list doesn't contain {@code player}.
	 */
	public int getEnginePriority(Player player) {
		if (player == null) {
			throw new NullPointerException("player cannot be null");
		}

		return getEnginePriority(player.id());
	}

	/**
	 * Moves or inserts a {@link Player} directly above another {@link Player}
	 * in the priority list. If {code abovePlayer} is {@code null},
	 * {@code player} will be placed first in the list. If {@code abovePlayer}
	 * isn't found, {@code player} will be placed last in the list.
	 *
	 * @param player the {@link Player} to move or insert in the priority list.
	 * @param abovePlayer the {@link Player} to place {@code player} relative
	 *            to.
	 */
	public void setEnginePriorityAbove(@Nonnull Player player, @Nullable Player abovePlayer) {
		if (player == null) {
			throw new IllegalArgumentException("player cannot be null");
		}

		setEnginePriorityAbove(player.id(), abovePlayer == null ? null : abovePlayer.id());
	}

	/**
	 * Moves or inserts a {@link PlayerId} directly above another
	 * {@link PlayerId} in the priority list. If {code aboveId} is {@code null},
	 * {@code id} will be placed first in the list. If {@code aboveId} isn't
	 * found, {@code id} will be placed last in the list.
	 *
	 * @param id the {@link PlayerId} to move or insert in the priority list.
	 * @param aboveId the {@link PlayerId} to place {@code id} relative to.
	 */
	public void setEnginePriorityAbove(PlayerId id, PlayerId aboveId) {
		if (id == null) {
			throw new IllegalArgumentException("Unrecognized id");
		}

		ENGINES_PRIORITY_LOCK.writeLock().lock();
		try {
			buildEnginesPriority();

			if (enginesPriority.indexOf(id) > -1) {
				enginesPriority.remove(id);
			}

			int newPosition;
			if (aboveId == null) {
				newPosition = 0;
			} else {
				newPosition = enginesPriority.indexOf(aboveId);
				if (newPosition < 0) {
					newPosition = enginesPriority.size();
				}
			}
			enginesPriority.add(newPosition, id);
			configuration.setProperty(KEY_ENGINES_PRIORITY, collectionToString(enginesPriority));
		} finally {
			ENGINES_PRIORITY_LOCK.writeLock().unlock();
		}

		PlayerFactory.sortPlayers();
	}

	/**
	 * Moves or inserts a {@link Player} directly below another {@link Player}
	 * in the priority list. If {code belowPlayer} is {@code null} or isn't
	 * found, {@code player} will be placed last in the list.
	 *
	 * @param player the {@link Player} to move or insert in the priority list.
	 * @param belowPlayer the {@link Player} to place {@code player} relative
	 *            to.
	 */
	public void setEnginePriorityBelow(Player player, Player belowPlayer) {
		if (player == null) {
			throw new IllegalArgumentException("player cannot be null");
		}

		setEnginePriorityBelow(player.id(), belowPlayer == null ? null : belowPlayer.id());
	}

	/**
	 * Moves or inserts a {@link PlayerId} directly below another
	 * {@link PlayerId} in the priority list. If {code belowId} is {@code null}
	 * or isn't found, {@code id} will be placed last in the list.
	 *
	 * @param id the {@link PlayerId} to move or insert in the priority list.
	 * @param belowId the {@link PlayerId} to place {@code id} relative to.
	 */
	public void setEnginePriorityBelow(PlayerId id, PlayerId belowId) {
		if (id == null) {
			throw new IllegalArgumentException("Unrecognized id");
		}

		ENGINES_PRIORITY_LOCK.writeLock().lock();
		try {
			buildEnginesPriority();

			if (enginesPriority.indexOf(id) > -1) {
				enginesPriority.remove(id);
			}

			int newPosition;
			if (belowId == null) {
				newPosition = enginesPriority.size();
			} else {
				newPosition = enginesPriority.indexOf(belowId) + 1;
				if (newPosition < 0) {
					newPosition = enginesPriority.size();
				}
			}

			enginesPriority.add(newPosition, id);
			configuration.setProperty(KEY_ENGINES_PRIORITY, collectionToString(enginesPriority));
		} finally {
			ENGINES_PRIORITY_LOCK.writeLock().unlock();
		}
		PlayerFactory.sortPlayers();
	}

	private static String collectionToString(Collection<?> list) {
		return StringUtils.join(list, LIST_SEPARATOR);
	}

	@SuppressWarnings("unused")
	private static List<String> stringToStringList(String input) {
		List<String> output = new ArrayList<>();
		Collections.addAll(output, StringUtils.split(input, LIST_SEPARATOR));
		return output;
	}

	private static UniqueList<PlayerId> stringToPlayerIdSet(String input) {
		UniqueList<PlayerId> output = new UniqueList<>();
		if (isBlank(input)) {
			output.addAll(StandardPlayerId.ALL);
			return output;
		}

		input = input.trim().toLowerCase(Locale.ROOT);
		if ("none".equals(input)) {
			return output;
		}

		for (String s : StringUtils.split(input, LIST_SEPARATOR)) {
			PlayerId playerId = StandardPlayerId.toPlayerID(s);
			if (playerId != null) {
				output.add(playerId);
			} else {
				LOGGER.warn("Unknown transcoding engine \"{}\"", s);
			}
		}

		return output;
	}

	/**
     * Save the configuration. Before this method can be called a valid file
     * name must have been set.
     *
     * @throws ConfigurationException if an error occurs or no file name has
     * been set yet
     */
	public void save() throws ConfigurationException {
		((PropertiesConfiguration) configuration).save();
		LOGGER.info("Configuration saved to \"{}\"", PROFILE_PATH);
	}

	private final Object sharedFoldersLock = new Object();

	@GuardedBy("sharedFoldersLock")
	private boolean sharedFoldersRead;

	@GuardedBy("sharedFoldersLock")
	private ArrayList<Path> sharedFolders;

	@GuardedBy("sharedFoldersLock")
	private boolean monitoredFoldersRead;

	@GuardedBy("sharedFoldersLock")
	private ArrayList<Path> monitoredFolders;

	@GuardedBy("sharedFoldersLock")
	private boolean ignoredFoldersRead;

	@GuardedBy("sharedFoldersLock")
	private ArrayList<Path> ignoredFolders;

	private ArrayList<String> ignoredFolderNames;

	/**
	 * Whether folder_names_ignored has been read.
	 */
	private boolean ignoredFolderNamesRead;

	private void readSharedFolders() {
		synchronized (sharedFoldersLock) {
			if (!sharedFoldersRead) {
				sharedFolders = getFolders(KEY_FOLDERS);
				sharedFoldersRead = true;
			}
		}
	}

	/**
	 * @return {@code true} if the configured shared folders are empty,
	 *         {@code false} otherwise.
	 */
	public boolean isSharedFoldersEmpty() {
		synchronized (sharedFoldersLock) {
			readSharedFolders();
			return sharedFolders.isEmpty();
		}
	}

	/**
	 * @return The {@link List} of {@link Path}s of shared folders.
	 */
	@Nonnull
	public List<Path> getSharedFolders() {
		synchronized (sharedFoldersLock) {
			readSharedFolders();
			return new ArrayList<>(sharedFolders);
		}
	}

	/**
	 * @return The {@link List} of {@link Path}s of monitored folders.
	 */
	@Nonnull
	public List<Path> getMonitoredFolders() {
		synchronized (sharedFoldersLock) {
			if (!monitoredFoldersRead) {
				monitoredFolders = getFolders(KEY_FOLDERS_MONITORED);
				monitoredFoldersRead = true;
			}

			return new ArrayList<>(monitoredFolders);
		}
	}

	/**
	 * @return The {@link List} of {@link Path}s of ignored folders.
	 */
	@Nonnull
	public List<Path> getIgnoredFolders() {
		synchronized (sharedFoldersLock) {
			if (!ignoredFoldersRead) {
				ignoredFolders = getFolders(KEY_FOLDERS_IGNORED);
				ignoredFoldersRead = true;
			}

			return ignoredFolders;
		}
	}

	/**
	 * @return The {@link List} of {@link Path}s of ignored folder names.
	 */
	@Nonnull
	public ArrayList<String> getIgnoredFolderNames() {
		if (!ignoredFolderNamesRead) {
			String ignoredFolderNamesString = configuration.getString(KEY_FOLDER_NAMES_IGNORED, ".unwanted");

			ArrayList<String> folders = new ArrayList<>();
			if (ignoredFolderNamesString == null || ignoredFolderNamesString.length() == 0) {
				return folders;
			}

			String[] foldersArray = ignoredFolderNamesString.trim().split("\\s*,\\s*");
			ignoredFolderNames = new ArrayList<>();

			for (String folder : foldersArray) {
				/*
				 * Unescape embedded commas. Note: Backslashing isn't safe as it
				 * conflicts with the Windows path separator.
				 */
				folder = folder.replaceAll("&comma;", ",");

				// add the path even if there are problems so that the user can update the shared folders as required.
				ignoredFolderNames.add(folder);
			}

			ignoredFolderNamesRead = true;
		}

		return ignoredFolderNames;
	}

	/**
	 * Transforms a comma-separated list of directory entries into an
	 * {@link ArrayList} of {@link Path}s. Verifies that the folder exists and
	 * is valid.
	 *
	 * @param key the {@link Configuration} key to read.
	 * @return The {@link List} of folders or {@code null}.
	 */
	@Nonnull
	protected ArrayList<Path> getFolders(String key) {
		String foldersString = configuration.getString(key, null);

		ArrayList<Path> folders = new ArrayList<>();
		if (foldersString == null || foldersString.length() == 0) {
			return folders;
		}

		String[] foldersArray = foldersString.trim().split("\\s*,\\s*");

		for (String folder : foldersArray) {
			/*
			 * Unescape embedded commas. Note: Backslashing isn't safe as it
			 * conflicts with the Windows path separator.
			 */
			folder = folder.replaceAll("&comma;", ",");

			if (KEY_FOLDERS.equals(key)) {
				LOGGER.info("Checking shared folder: \"{}\"", folder);
			}

			Path path = Paths.get(folder);
			if (Files.exists(path)) {
				if (!Files.isDirectory(path)) {
					if (KEY_FOLDERS.equals(key)) {
						LOGGER.warn(
							"The \"{}\" is not a folder! Please remove it from your shared folders " +
							"list on the \"{}\" tab or in the configuration file.",
							folder,
							Messages.getString("SharedContent")
						);
					} else {
						LOGGER.debug("The \"{}\" is not a folder - check the configuration for key \"{}\"", folder, key);
					}
				}
			} else if (KEY_FOLDERS.equals(key)) {
				LOGGER.warn(
					"\"{}\" does not exist. Please remove it from your shared folders " +
					"list on the \"{}\" tab or in the configuration file.",
					folder,
					Messages.getString("SharedContent")
				);
			} else {
				LOGGER.debug("\"{}\" does not exist - check the configuration for key \"{}\"", folder, key);
			}

			// add the path even if there are problems so that the user can update the shared folders as required.
			folders.add(path);
		}

		return folders;
	}

	/**
	 * This just preserves wizard functionality of offering the user a choice
	 * to share a directory.
	 *
	 * @param directoryPath
	 */
	public void setOnlySharedDirectory(String directoryPath) {
		synchronized (sharedFoldersLock) {
			configuration.setProperty(KEY_FOLDERS, directoryPath);
			configuration.setProperty(KEY_FOLDERS_MONITORED, directoryPath);
			ArrayList<Path> tmpSharedfolders = new ArrayList<>();
			Path folder = Paths.get(directoryPath);
			tmpSharedfolders.add(folder);
			sharedFolders = tmpSharedfolders;
			monitoredFolders = tmpSharedfolders;
			sharedFoldersRead = true;
			monitoredFoldersRead = true;
		}
	}

	static public class SharedFolder {
		public String path;
		public boolean monitored;
	}

	/**
	 * Stores the shared folders in the configuration from the specified
	 * value.
	 *
	 * @param tableSharedFolders the List of SharedFolder values to use.
	 */
	public void setSharedFolders(List<SharedFolder> tableSharedFolders) {
		if (tableSharedFolders == null || tableSharedFolders.isEmpty()) {
			synchronized (sharedFoldersLock) {
				if (!sharedFoldersRead || !sharedFolders.isEmpty()) {
					configuration.setProperty(KEY_FOLDERS, "");
					sharedFolders = new ArrayList<>();
					sharedFoldersRead = true;
				}

				if (!monitoredFoldersRead || !monitoredFolders.isEmpty()) {
					configuration.setProperty(KEY_FOLDERS_MONITORED, "");
					monitoredFolders = new ArrayList<>();
					monitoredFoldersRead = true;
				}
			}
			return;
		}
		String listSeparator = String.valueOf(LIST_SEPARATOR);
		ArrayList<Path> tmpSharedfolders = new ArrayList<>();
		ArrayList<Path> tmpMonitoredFolders = new ArrayList<>();
		for (SharedFolder rowSharedFolder : tableSharedFolders) {
			String folderPath = rowSharedFolder.path;
			/*
			 * Escape embedded commas. Note: Backslashing isn't safe as it
			 * conflicts with the Windows path separator.
			 */
			if (folderPath.contains(listSeparator)) {
				folderPath = folderPath.replace(listSeparator, "&comma;");
			}
			Path folder = Paths.get(folderPath);
			tmpSharedfolders.add(folder);
			if (rowSharedFolder.monitored) {
				tmpMonitoredFolders.add(folder);
			}
		}
		synchronized (sharedFoldersLock) {
			if (!sharedFoldersRead || !sharedFolders.equals(tmpSharedfolders)) {
				configuration.setProperty(KEY_FOLDERS, StringUtils.join(tmpSharedfolders, LIST_SEPARATOR));
				sharedFolders = tmpSharedfolders;
				sharedFoldersRead = true;
			}

			if (!monitoredFoldersRead || !monitoredFolders.equals(tmpMonitoredFolders)) {
				configuration.setProperty(KEY_FOLDERS_MONITORED, StringUtils.join(tmpMonitoredFolders, LIST_SEPARATOR));
				monitoredFolders = tmpMonitoredFolders;
				monitoredFoldersRead = true;
			}
		}
	}

	/**
	 * Sets the shared folders and the monitor folders to the platform default
	 * folders.
	 */
	public void setSharedFoldersToDefault() {
		synchronized (sharedFoldersLock) {
			sharedFolders = new ArrayList<>(RootFolder.getDefaultFolders());
			configuration.setProperty(KEY_FOLDERS, StringUtils.join(sharedFolders, LIST_SEPARATOR));
			sharedFoldersRead = true;
			monitoredFolders = new ArrayList<>(RootFolder.getDefaultFolders());
			configuration.setProperty(KEY_FOLDERS_MONITORED, StringUtils.join(monitoredFolders, LIST_SEPARATOR));
			monitoredFoldersRead = true;
		}
	}

	public String getNetworkInterface() {
		return getString(KEY_NETWORK_INTERFACE, "");
	}

	public void setNetworkInterface(String value) {
		configuration.setProperty(KEY_NETWORK_INTERFACE, value);
	}

	public boolean isHideEngineNames() {
		return getBoolean(KEY_HIDE_ENGINENAMES, true);
	}

	public void setHideEngineNames(boolean value) {
		configuration.setProperty(KEY_HIDE_ENGINENAMES, value);
	}

	/**
	 * @return {@code true} if subtitles information should be added to video
	 *         names, {@code false} otherwise.
	 */
	public SubtitlesInfoLevel getSubtitlesInfoLevel() {
		SubtitlesInfoLevel subtitlesInfoLevel = SubtitlesInfoLevel.typeOf(getString(KEY_SUBS_INFO_LEVEL, null));
		if (subtitlesInfoLevel != null) {
			return subtitlesInfoLevel;
		}

		// Check the old parameter for backwards compatibility
		Boolean value = configuration.getBoolean(KEY_HIDE_SUBS_INFO, null);
		if (value != null) {
			return value ? SubtitlesInfoLevel.NONE : SubtitlesInfoLevel.FULL;
		}

		return SubtitlesInfoLevel.BASIC; // Default
	}

	/**
	 * Sets if subtitles information should be added to video names.
	 *
	 * @param value whether or not subtitles information should be added.
	 */
	public void setSubtitlesInfoLevel(SubtitlesInfoLevel value) {
		configuration.setProperty(KEY_SUBS_INFO_LEVEL, value == null ? "" : value.toString());
	}

	public boolean isHideExtensions() {
		return getBoolean(KEY_HIDE_EXTENSIONS, true);
	}

	public void setHideExtensions(boolean value) {
		configuration.setProperty(KEY_HIDE_EXTENSIONS, value);
	}

	public String getShares() {
		return getString(KEY_SHARES, "");
	}

	public void setShares(String value) {
		configuration.setProperty(KEY_SHARES, value);
	}

	public String getDisableTranscodeForExtensions() {
		return getString(KEY_DISABLE_TRANSCODE_FOR_EXTENSIONS, "");
	}

	public void setDisableTranscodeForExtensions(String value) {
		configuration.setProperty(KEY_DISABLE_TRANSCODE_FOR_EXTENSIONS, value);
	}

	public boolean isDisableTranscoding() {
		return getBoolean(KEY_DISABLE_TRANSCODING, false);
	}

	public String getForceTranscodeForExtensions() {
		return getString(KEY_FORCE_TRANSCODE_FOR_EXTENSIONS, "");
	}

	public void setForceTranscodeForExtensions(String value) {
		configuration.setProperty(KEY_FORCE_TRANSCODE_FOR_EXTENSIONS, value);
	}

	public void setMencoderMT(boolean value) {
		configuration.setProperty(KEY_MENCODER_MT, value);
	}

	public boolean getMencoderMT() {
		boolean isMultiCore = getNumberOfCpuCores() > 1;
		return getBoolean(KEY_MENCODER_MT, isMultiCore);
	}

	public void setAudioRemuxAC3(boolean value) {
		configuration.setProperty(KEY_AUDIO_REMUX_AC3, value);
	}

	public boolean isAudioRemuxAC3() {
		return getBoolean(KEY_AUDIO_REMUX_AC3, true);
	}

	public void setFFmpegSoX(boolean value) {
		configuration.setProperty(KEY_FFMPEG_SOX, value);
	}

	public boolean isFFmpegSoX() {
		return getBoolean(KEY_FFMPEG_SOX, false);
	}

	public void setMencoderRemuxMPEG2(boolean value) {
		configuration.setProperty(KEY_MENCODER_REMUX_MPEG2, value);
	}

	public boolean isMencoderRemuxMPEG2() {
		return getBoolean(KEY_MENCODER_REMUX_MPEG2, true);
	}

	public void setDisableFakeSize(boolean value) {
		configuration.setProperty(KEY_DISABLE_FAKESIZE, value);
	}

	public boolean isDisableFakeSize() {
		return getBoolean(KEY_DISABLE_FAKESIZE, false);
	}

	/**
	 * Whether the style rules defined by styled subtitles (ASS/SSA) should
	 * be followed (true) or overridden by our style rules (false).
	 *
	 * @param value whether to use the embedded styles or ours
	 */
	public void setUseEmbeddedSubtitlesStyle(boolean value) {
		configuration.setProperty(KEY_USE_EMBEDDED_SUBTITLES_STYLE, value);
	}

	/**
	 * Whether the style rules defined by styled subtitles (ASS/SSA) should
	 * be followed (true) or overridden by our style rules (false).
	 *
	 * @return whether to use the embedded styles or ours
	 */
	public boolean isUseEmbeddedSubtitlesStyle() {
		return getBoolean(KEY_USE_EMBEDDED_SUBTITLES_STYLE, true);
	}

	public int getMEncoderOverscan() {
		return getInt(KEY_OVERSCAN, 0);
	}

	public void setMEncoderOverscan(int value) {
		configuration.setProperty(KEY_OVERSCAN, value);
	}

	/**
	 * Returns sort method to use for ordering lists of files. One of the
	 * following values is returned:
	 * <ul>
	 * <li>0: Locale-sensitive A-Z</li>
	 * <li>1: Sort by modified date, newest first</li>
	 * <li>2: Sort by modified date, oldest first</li>
	 * <li>3: Case-insensitive ASCIIbetical sort</li>
	 * <li>4: Locale-sensitive natural sort</li>
	 * <li>5: Random</li>
	 * </ul>
	 * Default value is 4.
	 * @return The sort method
	 */
	private static int findPathSort(String[] paths, String path) throws NumberFormatException {
		for (String path1 : paths) {
			String[] kv = path1.split(",");
			if (kv.length < 2) {
				continue;
			}

			if (kv[0].equals(path)) {
				return Integer.parseInt(kv[1]);
			}
		}

		return -1;
	}

	public int getSortMethod(File path) {
		int cnt = 0;
		String raw = getString(KEY_SORT_PATHS, null);
		if (StringUtils.isEmpty(raw)) {
			return getInt(KEY_SORT_METHOD, UMSUtils.SORT_LOC_NAT);
		}

		if (Platform.isWindows()) {
			// windows is crap
			raw = raw.toLowerCase();
		}

		String[] paths = raw.split(" ");

		while (path != null && (cnt++ < 100)) {
			String key = path.getAbsolutePath();
			if (Platform.isWindows()) {
				key = key.toLowerCase();
			}

			try {
				int ret = findPathSort(paths, key);
				if (ret != -1) {
					return ret;
				}
			} catch (NumberFormatException e) {
				// just ignore
			}

			path = path.getParentFile();
		}

		return getInt(KEY_SORT_METHOD, UMSUtils.SORT_LOC_NAT);
	}

	/**
	 * Set the sort method to use for ordering lists of files. The following
	 * values are recognized:
	 * <ul>
	 * <li>0: Locale-sensitive A-Z</li>
	 * <li>1: Sort by modified date, newest first</li>
	 * <li>2: Sort by modified date, oldest first</li>
	 * <li>3: Case-insensitive ASCIIbetical sort</li>
	 * <li>4: Locale-sensitive natural sort</li>
	 * <li>5: Random</li>
	 * </ul>
	 * @param value The sort method to use
	 */
	public void setSortMethod(int value) {
		configuration.setProperty(KEY_SORT_METHOD, value);
	}

	public CoverSupplier getAudioThumbnailMethod() {
		return CoverSupplier.toCoverSupplier(getInt(KEY_AUDIO_THUMBNAILS_METHOD, 1));
	}

	public void setAudioThumbnailMethod(CoverSupplier value) {
		configuration.setProperty(KEY_AUDIO_THUMBNAILS_METHOD, value.toInt());
	}

	public String getAlternateThumbFolder() {
		return getString(KEY_ALTERNATE_THUMB_FOLDER, "");
	}

	public void setAlternateThumbFolder(String value) {
		configuration.setProperty(KEY_ALTERNATE_THUMB_FOLDER, value);
	}

	public String getAlternateSubtitlesFolder() {
		return getString(KEY_ALTERNATE_SUBTITLES_FOLDER, "");
	}

	public void setAlternateSubtitlesFolder(String value) {
		configuration.setProperty(KEY_ALTERNATE_SUBTITLES_FOLDER, value);
	}

	public void setAudioEmbedDtsInPcm(boolean value) {
		configuration.setProperty(KEY_AUDIO_EMBED_DTS_IN_PCM, value);
	}

	public boolean isAudioEmbedDtsInPcm() {
		return getBoolean(KEY_AUDIO_EMBED_DTS_IN_PCM, false);
	}

	public void setEncodedAudioPassthrough(boolean value) {
		configuration.setProperty(KEY_ENCODED_AUDIO_PASSTHROUGH, value);
	}

	public boolean isEncodedAudioPassthrough() {
		return getBoolean(KEY_ENCODED_AUDIO_PASSTHROUGH, false);
	}

	public void setMencoderMuxWhenCompatible(boolean value) {
		configuration.setProperty(KEY_MENCODER_MUX_COMPATIBLE, value);
	}

	public boolean isMencoderMuxWhenCompatible() {
		return getBoolean(KEY_MENCODER_MUX_COMPATIBLE, false);
	}

	public void setMEncoderNormalizeVolume(boolean value) {
		configuration.setProperty(KEY_MENCODER_NORMALIZE_VOLUME, value);
	}

	public boolean isMEncoderNormalizeVolume() {
		return getBoolean(KEY_MENCODER_NORMALIZE_VOLUME, false);
	}

	public void setFFmpegMuxWithTsMuxerWhenCompatible(boolean value) {
		configuration.setProperty(KEY_FFMPEG_MUX_TSMUXER_COMPATIBLE, value);
	}

	public boolean isFFmpegMuxWithTsMuxerWhenCompatible() {
		return getBoolean(KEY_FFMPEG_MUX_TSMUXER_COMPATIBLE, false);
	}

	/**
	 * Whether FFmpegVideo should defer to MEncoderVideo when there are
	 * subtitles that need to be transcoded which FFmpeg will need to
	 * initially parse, which can cause timeouts.
	 *
	 * @param value
	 */
	public void setFFmpegDeferToMEncoderForProblematicSubtitles(boolean value) {
		configuration.setProperty(KEY_FFMPEG_MENCODER_PROBLEMATIC_SUBTITLES, value);
	}

	/**
	 * Whether FFmpegVideo should defer to MEncoderVideo when there are
	 * subtitles that need to be transcoded which FFmpeg will need to
	 * initially parse, which can cause timeouts.
	 *
	 * @return
	 */
	public boolean isFFmpegDeferToMEncoderForProblematicSubtitles() {
		return getBoolean(KEY_FFMPEG_MENCODER_PROBLEMATIC_SUBTITLES, true);
	}

	public void setFFmpegFontConfig(boolean value) {
		configuration.setProperty(KEY_FFMPEG_FONTCONFIG, value);
	}

	public boolean isFFmpegFontConfig() {
		return getBoolean(KEY_FFMPEG_FONTCONFIG, false);
	}

	public void setMuxAllAudioTracks(boolean value) {
		configuration.setProperty(KEY_MUX_ALLAUDIOTRACKS, value);
	}

	public boolean isMuxAllAudioTracks() {
		return getBoolean(KEY_MUX_ALLAUDIOTRACKS, false);
	}

	public void setUseMplayerForVideoThumbs(boolean value) {
		configuration.setProperty(KEY_USE_MPLAYER_FOR_THUMBS, value);
	}

	public boolean isUseMplayerForVideoThumbs() {
		return getBoolean(KEY_USE_MPLAYER_FOR_THUMBS, false);
	}

	public String getIpFilter() {
		return getString(KEY_IP_FILTER, "");
	}

	public synchronized IpFilter getIpFiltering() {
		filter.setRawFilter(getIpFilter());
		return filter;
	}

	public void setIpFilter(String value) {
		configuration.setProperty(KEY_IP_FILTER, value);
	}

	public void setPreventSleep(PreventSleepMode value) {
		if (value == null) {
			throw new NullPointerException("value cannot be null");
		}

		configuration.setProperty(KEY_PREVENT_SLEEP, value.getValue());
		SleepManager sleepManager = Services.sleepManager();
		if (sleepManager != null) {
			sleepManager.setMode(value);
		}
	}

	public PreventSleepMode getPreventSleep() {
		return PreventSleepMode.typeOf(getString(KEY_PREVENT_SLEEP, PreventSleepMode.PLAYBACK.getValue()));
	}

	public boolean isShowIphotoLibrary() {
		return getBoolean(KEY_SHOW_IPHOTO_LIBRARY, false);
	}

	public void setShowIphotoLibrary(boolean value) {
		configuration.setProperty(KEY_SHOW_IPHOTO_LIBRARY, value);
	}

	public boolean isShowApertureLibrary() {
		return getBoolean(KEY_SHOW_APERTURE_LIBRARY, false);
	}

	public void setShowApertureLibrary(boolean value) {
		configuration.setProperty(KEY_SHOW_APERTURE_LIBRARY, value);
	}

	public boolean isShowItunesLibrary() {
		return getBoolean(KEY_SHOW_ITUNES_LIBRARY, false);
	}

	public String getItunesLibraryPath() {
		return getString(KEY_ITUNES_LIBRARY_PATH, "");
	}

	public void setShowItunesLibrary(boolean value) {
		configuration.setProperty(KEY_SHOW_ITUNES_LIBRARY, value);
	}

	public boolean isHideAdvancedOptions() {
		return getBoolean(PmsConfiguration.KEY_HIDE_ADVANCED_OPTIONS, true);
	}

	public void setHideAdvancedOptions(final boolean value) {
		this.configuration.setProperty(PmsConfiguration.KEY_HIDE_ADVANCED_OPTIONS, value);
	}

	public boolean isHideEmptyFolders() {
		return getBoolean(PmsConfiguration.KEY_HIDE_EMPTY_FOLDERS, false);
	}

	public void setHideEmptyFolders(final boolean value) {
		this.configuration.setProperty(PmsConfiguration.KEY_HIDE_EMPTY_FOLDERS, value);
	}

	public boolean isUseSymlinksTargetFile() {
		return getBoolean(PmsConfiguration.KEY_USE_SYMLINKS_TARGET_FILE, false);
	}

	public void setUseSymlinksTargetFile(final boolean value) {
		this.configuration.setProperty(PmsConfiguration.KEY_USE_SYMLINKS_TARGET_FILE, value);
	}

	/**
	 * Whether to show the "Media Library" folder on the renderer.
	 *
	 * @return whether the folder is shown
	 */
	public boolean isShowMediaLibraryFolder() {
		return getBoolean(PmsConfiguration.KEY_SHOW_MEDIA_LIBRARY_FOLDER, true);
	}

	/**
	 * Whether to show the "Media Library" folder on the renderer.
	 *
	 * @param value whether the folder is shown
	 */
	public void setShowMediaLibraryFolder(final boolean value) {
		this.configuration.setProperty(PmsConfiguration.KEY_SHOW_MEDIA_LIBRARY_FOLDER, value);
	}

	/**
	 * Whether to show the "#--TRANSCODE--#" folder on the renderer.
	 *
	 * @return whether the folder is shown
	 */
	public boolean isShowTranscodeFolder() {
		return getBoolean(KEY_SHOW_TRANSCODE_FOLDER, true);
	}

	/**
	 * Whether to show the "#--TRANSCODE--#" folder on the renderer.
	 *
	 * @param value whether the folder is shown
	 */
	public void setShowTranscodeFolder(boolean value) {
		configuration.setProperty(KEY_SHOW_TRANSCODE_FOLDER, value);
	}

	public boolean isDvdIsoThumbnails() {
		return getBoolean(KEY_DVDISO_THUMBNAILS, true);
	}

	public void setDvdIsoThumbnails(boolean value) {
		configuration.setProperty(KEY_DVDISO_THUMBNAILS, value);
	}

	public Object getCustomProperty(String property) {
		return configurationReader.getCustomProperty(property);
	}

	public void setCustomProperty(String property, Object value) {
		configuration.setProperty(property, value);
	}

	public boolean isChapterSupport() {
		return getBoolean(KEY_CHAPTER_SUPPORT, false);
	}

	public void setChapterSupport(boolean value) {
		configuration.setProperty(KEY_CHAPTER_SUPPORT, value);
	}

	public int getChapterInterval() {
		return getInt(KEY_CHAPTER_INTERVAL, 5);
	}

	public void setChapterInterval(int value) {
		configuration.setProperty(KEY_CHAPTER_INTERVAL, value);
	}

	public SubtitleColor getSubsColor() {
		String colorString = getString(KEY_SUBS_COLOR, null);
		if (StringUtils.isNotBlank(colorString)) {
			try {
				return new SubtitleColor(colorString);
			} catch (InvalidArgumentException e) {
				LOGGER.error("Using default subtitle color: {}", e.getMessage());
				LOGGER.trace("", e);
			}
		}

		return new SubtitleColor(0xFF, 0xFF, 0xFF);
	}

	public void setSubsColor(SubtitleColor color) {
		if (color.getAlpha() != 0xFF) {
			configuration.setProperty(KEY_SUBS_COLOR, color.get0xRRGGBBAA());
		} else {
			configuration.setProperty(KEY_SUBS_COLOR, color.get0xRRGGBB());
		}
	}

	public boolean isFix25FPSAvMismatch() {
		return getBoolean(KEY_FIX_25FPS_AV_MISMATCH, false);
	}

	public void setFix25FPSAvMismatch(boolean value) {
		configuration.setProperty(KEY_FIX_25FPS_AV_MISMATCH, value);
	}

	public int getVideoTranscodeStartDelay() {
		return getInt(KEY_VIDEOTRANSCODE_START_DELAY, 6);
	}

	public void setVideoTranscodeStartDelay(int value) {
		configuration.setProperty(KEY_VIDEOTRANSCODE_START_DELAY, value);
	}

	public boolean isAudioResample() {
		return getBoolean(KEY_AUDIO_RESAMPLE, true);
	}

	public void setAudioResample(boolean value) {
		configuration.setProperty(KEY_AUDIO_RESAMPLE, value);
	}

	public boolean isIgnoreTheWordAandThe() {
		return getBoolean(KEY_IGNORE_THE_WORD_A_AND_THE, true);
	}

	public void setIgnoreTheWordAandThe(boolean value) {
		configuration.setProperty(KEY_IGNORE_THE_WORD_A_AND_THE, value);
	}

	public boolean isPrettifyFilenames() {
		return getBoolean(KEY_PRETTIFY_FILENAMES, false);
	}

	public void setPrettifyFilenames(boolean value) {
		configuration.setProperty(KEY_PRETTIFY_FILENAMES, value);
	}

	public boolean isUseInfoFromIMDb() {
		return getBoolean(KEY_USE_IMDB_INFO, true);
	}

	public void setUseInfoFromIMDb(boolean value) {
		configuration.setProperty(KEY_USE_IMDB_INFO, value);
	}

	public boolean isRunWizard() {
		return getBoolean(KEY_RUN_WIZARD, true);
	}

	public void setRunWizard(boolean value) {
		configuration.setProperty(KEY_RUN_WIZARD, value);
	}

	/**
	 * Whether to scan shared folders on startup.
	 *
	 * @return whether to scan shared folders on startup
	 */
	public boolean isScanSharedFoldersOnStartup() {
		return getBoolean(KEY_SCAN_SHARED_FOLDERS_ON_STARTUP, true);
	}

	/**
	 * Whether to scan shared folders on startup.
	 *
	 * @param value whether to scan shared folders on startup
	 */
	public void setScanSharedFoldersOnStartup(final boolean value) {
		this.configuration.setProperty(KEY_SCAN_SHARED_FOLDERS_ON_STARTUP, value);
	}

	/**
	 * Whether to show the "Recently Played" folder on the renderer.
	 *
	 * @return whether the folder is shown
	 */
	public boolean isShowRecentlyPlayedFolder() {
		return getBoolean(PmsConfiguration.KEY_SHOW_RECENTLY_PLAYED_FOLDER, true);
	}

	/**
	 * Whether to show the "Recently Played" folder on the renderer.
	 *
	 * @param value whether the folder is shown
	 */
	public void setShowRecentlyPlayedFolder(final boolean value) {
		this.configuration.setProperty(PmsConfiguration.KEY_SHOW_RECENTLY_PLAYED_FOLDER, value);
	}

	/**
	 * Returns the name of the renderer to fall back on when header matching
	 * fails. PMS will recognize the configured renderer instead of "Unknown
	 * renderer". Default value is "", which means PMS will return the unknown
	 * renderer when no match can be made.
	 *
	 * @return The name of the renderer PMS should fall back on when header
	 *         matching fails.
	 * @see #isRendererForceDefault()
	 */
	public String getRendererDefault() {
		return getString(KEY_RENDERER_DEFAULT, "");
	}

	/**
	 * Sets the name of the renderer to fall back on when header matching
	 * fails. PMS will recognize the configured renderer instead of "Unknown
	 * renderer". Set to "" to make PMS return the unknown renderer when no
	 * match can be made.
	 *
	 * @param value The name of the renderer to fall back on. This has to be
	 *              <code>""</code> or a case insensitive match with the name
	 *              used in any render configuration file.
	 * @see #setRendererForceDefault(boolean)
	 */
	public void setRendererDefault(String value) {
		configuration.setProperty(KEY_RENDERER_DEFAULT, value);
	}

	/**
	 * Returns true when PMS should not try to guess connecting renderers
	 * and instead force picking the defined fallback renderer. Default
	 * value is false, which means PMS will attempt to recognize connecting
	 * renderers by their headers.
	 *
	 * @return True when the fallback renderer should always be picked.
	 * @see #getRendererDefault()
	 */
	public boolean isRendererForceDefault() {
		return getBoolean(KEY_RENDERER_FORCE_DEFAULT, false);
	}

	/**
	 * Set to true when PMS should not try to guess connecting renderers
	 * and instead force picking the defined fallback renderer. Set to false
	 * to make PMS attempt to recognize connecting renderers by their headers.
	 *
	 * @param value True when the fallback renderer should always be picked.
	 * @see #setRendererDefault(String)
	 */
	public void setRendererForceDefault(boolean value) {
		configuration.setProperty(KEY_RENDERER_FORCE_DEFAULT, value);
	}

	public String getVirtualFolders() {
		return getString(KEY_VIRTUAL_FOLDERS, "");
	}

	public String getVirtualFoldersFile() {
		return getString(KEY_VIRTUAL_FOLDERS_FILE, "");
	}

	public String getProfilePath() {
		return PROFILE_PATH;
	}

	public String getProfileDirectory() {
		return PROFILE_DIRECTORY;
	}

	/**
	 * Returns the absolute path to the WEB.conf file. By default
	 * this is <pre>PROFILE_DIRECTORY + File.pathSeparator + WEB.conf</pre>,
	 * but it can be overridden via the <pre>web_conf</pre> profile option.
	 * The existence of the file is not checked.
	 *
	 * @return the path to the WEB.conf file.
	 */
	public String getWebConfPath() {
		// Initialise this here rather than in the constructor
		// or statically so that custom settings are logged
		// to the logfile/Logs tab.
		if (webConfPath == null) {
			webConfPath = FileUtil.getFileLocation(
				getString(KEY_WEB_CONF_PATH, null),
				PROFILE_DIRECTORY,
				DEFAULT_WEB_CONF_FILENAME
			).getFilePath();
		}

		return getString(KEY_WEB_CONF_PATH, webConfPath);
	}

	public String getPluginDirectory() {
		return getString(KEY_PLUGIN_DIRECTORY, "plugins");
	}

	public void setPluginDirectory(String value) {
		configuration.setProperty(KEY_PLUGIN_DIRECTORY, value);
	}

	public String getProfileName() {
		if (hostName == null) { // Initialise this lazily
			try {
				hostName = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e) {
				LOGGER.info("Can't determine hostname");
				hostName = "unknown host";
			}
		}

		return getString(KEY_PROFILE_NAME, hostName);
	}

	public boolean isAutoUpdate() {
		return Build.isUpdatable() && getBoolean(KEY_AUTO_UPDATE, false);
	}

	public void setAutoUpdate(boolean value) {
		configuration.setProperty(KEY_AUTO_UPDATE, value);
	}

	public int getUpnpPort() {
		return getInt(KEY_UPNP_PORT, 1900);
	}

	public String getUuid() {
		return getString(KEY_UUID, null);
	}

	public void setUuid(String value) {
		configuration.setProperty(KEY_UUID, value);
	}

	public void addConfigurationListener(ConfigurationListener l) {
		((PropertiesConfiguration) configuration).addConfigurationListener(l);
	}

	public void removeConfigurationListener(ConfigurationListener l) {
		((PropertiesConfiguration) configuration).removeConfigurationListener(l);
	}

	public boolean getFolderLimit() {
		return getBoolean(KEY_FOLDER_LIMIT, false);
	}

	public String getScriptDir() {
		return getString(KEY_SCRIPT_DIR, null);
	}

	public String getPluginPurgeAction() {
		return getString(KEY_PLUGIN_PURGE_ACTION, "delete");
	}

	public boolean getSearchFolder() {
		return getBoolean(KEY_SEARCH_FOLDER, false);
	}

	public boolean getSearchInFolder() {
		return getBoolean(KEY_SEARCH_IN_FOLDER, false) && getSearchFolder();
	}

	public int getSearchDepth() {
		int ret = (getBoolean(KEY_SEARCH_RECURSE, true) ? 100 : 2);
		return getInt(KEY_SEARCH_RECURSE_DEPTH, ret);
	}

	public void reload() {
		try {
			((PropertiesConfiguration) configuration).refresh();
		} catch (ConfigurationException e) {
			LOGGER.error(null, e);
		}
	}

	/**
	 * Retrieve the name of the folder used to select subtitles, audio channels, chapters, engines &amp;c.
	 * Defaults to the localized version of <pre>#--TRANSCODE--#</pre>.
	 * @return The folder name.
	 */
	public String getTranscodeFolderName() {
		return getString(KEY_TRANSCODE_FOLDER_NAME, Messages.getString("Transcode_FolderName"));
	}

	/**
	 * Set a custom name for the <pre>#--TRANSCODE--#</pre> folder.
	 * @param name The folder name.
	 */
	public void setTranscodeFolderName(String name) {
		configuration.setProperty(KEY_TRANSCODE_FOLDER_NAME, name);
	}

	/**
	 * State if the video hardware acceleration is allowed
	 * @return true if hardware acceleration is allowed, false otherwise
	 */
	public boolean isGPUAcceleration() {
		return getBoolean(KEY_GPU_ACCELERATION, false);
	}

	/**
	 * Set the video hardware acceleration enable/disable
	 * @param value true if hardware acceleration is allowed, false otherwise
	 */
	public void setGPUAcceleration(boolean value) {
		configuration.setProperty(KEY_GPU_ACCELERATION, value);
	}

	/**
	 * Get the state of the GUI log tab "Case sensitive" check box
	 * @return true if enabled, false if disabled
	 */
	public boolean getGUILogSearchCaseSensitive() {
		return getBoolean(KEY_GUI_LOG_SEARCH_CASE_SENSITIVE, false);
	}

	/**
	 * Set the state of the GUI log tab "Case sensitive" check box
	 * @param value true if enabled, false if disabled
	 */
	public void setGUILogSearchCaseSensitive(boolean value) {
		configuration.setProperty(KEY_GUI_LOG_SEARCH_CASE_SENSITIVE, value);
	}

	/**
	 * Get the state of the GUI log tab "Multiline" check box
	 * @return true if enabled, false if disabled
	 */
	public boolean getGUILogSearchMultiLine() {
		return getBoolean(KEY_GUI_LOG_SEARCH_MULTILINE, false);
	}

	/**
	 * Set the state of the GUI log tab "Multiline" check box
	 * @param value true if enabled, false if disabled
	 */
	public void setGUILogSearchMultiLine(boolean value) {
		configuration.setProperty(KEY_GUI_LOG_SEARCH_MULTILINE, value);
	}

	/**
	 * Get the state of the GUI log tab "RegEx" check box
	 * @return true if enabled, false if disabled
	 */
	public boolean getGUILogSearchRegEx() {
		return getBoolean(KEY_GUI_LOG_SEARCH_USE_REGEX, false);
	}

	/**
	 * Set the state of the GUI log tab "RegEx" check box
	 * @param value true if enabled, false if disabled
	 */
	public void setGUILogSearchRegEx(boolean value) {
		configuration.setProperty(KEY_GUI_LOG_SEARCH_USE_REGEX, value);
	}

	/* Start without external netowrk (increase startup speed) */
	public static final String KEY_EXTERNAL_NETWORK = "external_network";

	public boolean getExternalNetwork() {
		return getBoolean(KEY_EXTERNAL_NETWORK, true);
	}

	public void setExternalNetwork(boolean b) {
		configuration.setProperty(KEY_EXTERNAL_NETWORK, b);
	}

	/* Credential path handling */
	public static final String KEY_CRED_PATH = "cred.path";

	public void initCred() throws IOException {
		File credFile = getCredFile();

		if (!credFile.exists()) {
			// Create an empty file and save the path if needed
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(credFile), StandardCharsets.UTF_8))) {
				writer.write("# Add credentials to the file");
				writer.newLine();
				writer.write("# on the format tag=user,password");
				writer.newLine();
				writer.write("# For example:");
				writer.newLine();
				writer.write("# channels.xxx=name,secret");
				writer.newLine();
			}

			// Save the path if we got here
			configuration.setProperty(KEY_CRED_PATH, credFile.getAbsolutePath());
			try {
				((PropertiesConfiguration) configuration).save();
			} catch (ConfigurationException e) {
				LOGGER.warn("An error occurred while saving configuration: {}", e.getMessage());
			}
		}
	}

	public File getCredFile() {
		String path = getString(KEY_CRED_PATH, "");
		if (path != null && !path.trim().isEmpty()) {
			return new File(path);
		}

		return new File(getProfileDirectory(), DEFAULT_CREDENTIALS_FILENAME);
	}

	public int getATZLimit() {
		int tmp = getInt(KEY_ATZ_LIMIT, 10000);
		if (tmp <= 2) {
			// this is silly, ignore
			tmp = 10000;
		}

		return tmp;
	}

	public void setATZLimit(int val) {
		if (val <= 2) {
			// clear prop
			configuration.clearProperty(KEY_ATZ_LIMIT);
			return;
		}

		configuration.setProperty(KEY_ATZ_LIMIT, val);
	}

	public void setATZLimit(String str) {
		try {
			setATZLimit(Integer.parseInt(str));
		} catch (NumberFormatException e) {
			setATZLimit(0);
		}
	}

	public String getDataDir() {
		return getProfileDirectory() + File.separator + "data";
	}

	public String getDataFile(String str) {
		return getDataDir() + File.separator + str;
	}

	private static final String KEY_URL_RES_ORDER = "url_resolve_order";

	public String[] getURLResolveOrder() {
		return getString(KEY_URL_RES_ORDER, "").split(",");
	}

	/**
	 * Whether to show the "#--LIVE SUBTITLES--#" folder on the renderer.
	 *
	 * @return whether the folder is shown
	 */
	public boolean isShowLiveSubtitlesFolder() {
		return getBoolean(KEY_SHOW_LIVE_SUBTITLES_FOLDER, false);
	}

	/**
	 * Whether to show the "#--LIVE SUBTITLES--#" folder on the renderer.
	 *
	 * @param value whether the folder is shown
	 */
	public void setShowLiveSubtitlesFolder(boolean value) {
		configuration.setProperty(KEY_SHOW_LIVE_SUBTITLES_FOLDER, value);
	}

	public boolean displayAudioLikesInRootFolder() {
		return getBoolean(KEY_AUDIO_LIKES_IN_ROOT_FOLDER, false);
	}

	public int getLiveSubtitlesLimit() {
		return getInt(KEY_LIVE_SUBTITLES_LIMIT, 20);
	}

	public void setLiveSubtitlesLimit(int value) {
		if (value > 0) {
			configuration.setProperty(KEY_LIVE_SUBTITLES_LIMIT, value);
		}
	}

	public boolean isLiveSubtitlesKeep() {
		return getBoolean(KEY_LIVE_SUBTITLES_KEEP, true);
	}

	public void setLiveSubtitlesKeep(boolean value) {
		configuration.setProperty(KEY_LIVE_SUBTITLES_KEEP, value);
	}

	public boolean getLoggingBuffered() {
		return getBoolean(KEY_LOGGING_BUFFERED, false);
	}

	public void setLoggingBuffered(boolean value) {
		configuration.setProperty(KEY_LOGGING_BUFFERED, value);
	}

	public Level getLoggingFilterConsole() {
		return Level.toLevel(getString(KEY_LOGGING_FILTER_CONSOLE, "INFO"), Level.INFO);
	}

	public void setLoggingFilterConsole(Level value) {
		configuration.setProperty(KEY_LOGGING_FILTER_CONSOLE, value.levelStr);
	}

	public Level getLoggingFilterLogsTab() {
		return Level.toLevel(getString(KEY_LOGGING_FILTER_LOGS_TAB, "INFO"), Level.INFO);
	}

	public void setLoggingFilterLogsTab(Level value) {
		configuration.setProperty(KEY_LOGGING_FILTER_LOGS_TAB, value.levelStr);
	}

	public int getLoggingLogsTabLinebuffer() {
		return Math.min(Math.max(getInt(KEY_LOGGING_LOGS_TAB_LINEBUFFER, 1000), LOGGING_LOGS_TAB_LINEBUFFER_MIN), LOGGING_LOGS_TAB_LINEBUFFER_MAX);
	}

	public void setLoggingLogsTabLinebuffer(int value) {
		value = Math.min(Math.max(value, LOGGING_LOGS_TAB_LINEBUFFER_MIN), LOGGING_LOGS_TAB_LINEBUFFER_MAX);
		configuration.setProperty(KEY_LOGGING_LOGS_TAB_LINEBUFFER, value);
	}

	public String getLoggingSyslogFacility() {
		return getString(KEY_LOGGING_SYSLOG_FACILITY, "USER");
	}

	public void setLoggingSyslogFacility(String value) {
		configuration.setProperty(KEY_LOGGING_SYSLOG_FACILITY, value);
	}

	public void setLoggingSyslogFacilityDefault() {
		setLoggingSyslogFacility("USER");
	}

	public String getLoggingSyslogHost() {
		return getString(KEY_LOGGING_SYSLOG_HOST, "");
	}

	public void setLoggingSyslogHost(String value) {
		configuration.setProperty(KEY_LOGGING_SYSLOG_HOST, value);
	}

	public int getLoggingSyslogPort() {
		int i = getInt(KEY_LOGGING_SYSLOG_PORT, 514);
		if (i < 1 || i > 65535) {
			return 514;
		}

		return i;
	}

	public void setLoggingSyslogPort(int value) {
		if (value < 1 || value > 65535) {
			setLoggingSyslogPortDefault();
		} else {
			configuration.setProperty(KEY_LOGGING_SYSLOG_PORT, value);
		}
	}

	public void setLoggingSyslogPortDefault() {
		setLoggingSyslogPort(514);
	}

	public boolean getLoggingUseSyslog() {
		return getBoolean(KEY_LOGGING_USE_SYSLOG, false);
	}

	public void setLoggingUseSyslog(boolean value) {
		configuration.setProperty(KEY_LOGGING_USE_SYSLOG, value);
	}

	/**
	 * Returns whether database logging is enabled. The returned value is
	 * {@code true} if either the value is {@code true} or a command line
	 * argument has forced it to {@code true}.
	 *
	 * @return {@code true} if database logging is enabled, {@code false}
	 *         otherwise.
	 */
	public boolean getDatabaseLogging() {
		boolean dbLog = getBoolean(KEY_LOG_DATABASE, false);
		return dbLog || PMS.getLogDB();
	}

	/**
	 * Get the embedded Media database cache size.
	 * @return the cache size in Kb
	 */
	public int getDatabaseMediaCacheSize() {
		return getInt(KEY_DATABASE_MEDIA_CACHE_SIZE_KB, -1);
	}

	/**
	 * Set the embedded Media database cache size.
	 * @param value the cache size in Kb
	 */
	public void setDatabaseMediaCacheSize(int value) {
		configuration.setProperty(KEY_DATABASE_MEDIA_CACHE_SIZE_KB, value);
	}

	/**
	 * Return whether the embedded Media database table indexes should sit in memory.
	 * @return true if table indexes should sit on memory
	 */
	public boolean isDatabaseMediaUseMemoryIndexes() {
		return getBoolean(KEY_DATABASE_MEDIA_USE_MEMORY_INDEXES, false);
	}

	/**
	 * Return whether the embedded Media database use soft cache.
	 * @return true if table use soft cache
	 */
	public boolean isDatabaseMediaUseCacheSoft() {
		return getBoolean(KEY_DATABASE_MEDIA_USE_CACHE_SOFT, false);
	}

	public boolean isVlcUseHardwareAccel() {
		return getBoolean(KEY_VLC_USE_HW_ACCELERATION, false);
	}

	public void setVlcUseHardwareAccel(boolean value) {
		configuration.setProperty(KEY_VLC_USE_HW_ACCELERATION, value);
	}

	public boolean isVlcExperimentalCodecs() {
		return getBoolean(KEY_VLC_USE_EXPERIMENTAL_CODECS, false);
	}

	public void setVlcExperimentalCodecs(boolean value) {
		configuration.setProperty(KEY_VLC_USE_EXPERIMENTAL_CODECS, value);
	}

	public boolean isVlcAudioSyncEnabled() {
		return getBoolean(KEY_VLC_AUDIO_SYNC_ENABLED, false);
	}

	public void setVlcAudioSyncEnabled(boolean value) {
		configuration.setProperty(KEY_VLC_AUDIO_SYNC_ENABLED, value);
	}

	public boolean isVlcSubtitleEnabled() {
		return getBoolean(KEY_VLC_SUBTITLE_ENABLED, true);
	}

	public void setVlcSubtitleEnabled(boolean value) {
		configuration.setProperty(KEY_VLC_SUBTITLE_ENABLED, value);
	}

	public String getVlcScale() {
		return getString(KEY_VLC_SCALE, "1.0");
	}

	public void setVlcScale(String value) {
		configuration.setProperty(KEY_VLC_SCALE, value);
	}

	public boolean getVlcSampleRateOverride() {
		return getBoolean(KEY_VLC_SAMPLE_RATE_OVERRIDE, false);
	}

	public void setVlcSampleRateOverride(boolean value) {
		configuration.setProperty(KEY_VLC_SAMPLE_RATE_OVERRIDE, value);
	}

	public String getVlcSampleRate() {
		return getString(KEY_VLC_SAMPLE_RATE, "48000");
	}

	public void setVlcSampleRate(String value) {
		configuration.setProperty(KEY_VLC_SAMPLE_RATE, value);
	}

	public boolean isResumeEnabled()  {
		return getBoolean(KEY_RESUME, true);
	}

	public void setResume(boolean value) {
		configuration.setProperty(KEY_RESUME, value);
	}

	public int getMinimumWatchedPlayTime() {
		return getInt(KEY_MIN_PLAY_TIME, 30000);
	}

	public int getMinimumWatchedPlayTimeSeconds() {
		return getMinimumWatchedPlayTime() / 1000;
	}

	public int getMinPlayTimeWeb() {
		return getInt(KEY_MIN_PLAY_TIME_WEB, getMinimumWatchedPlayTime());
	}

	public int getMinPlayTimeFile() {
		return getInt(KEY_MIN_PLAY_TIME_FILE, getMinimumWatchedPlayTime());
	}

	public int getResumeRewind() {
		return getInt(KEY_RESUME_REWIND, 17000);
	}

	public double getResumeBackFactor() {
		int percent = getInt(KEY_RESUME_BACK, 92);
		if (percent > 97) {
			percent = 97;
		}

		if (percent < 10) {
			percent = 10;
		}

		return (percent / 100.0);
	}

	public int getResumeKeepTime() {
		return getInt(KEY_RESUME_KEEP_TIME, 0);
	}

	/**
	 * Whether the profile name should be appended to the server name when
	 * displayed on the renderer
	 *
	 * @return True if the profile name should be appended.
	 */
	public boolean isAppendProfileName() {
		return getBoolean(KEY_APPEND_PROFILE_NAME, false);
	}

	/**
	 * Set whether the profile name should be appended to the server name
	 * when displayed on the renderer
	 *
	 * @param value Set to true if the profile name should be appended.
	 */
	public void setAppendProfileName(boolean value) {
		configuration.setProperty(KEY_APPEND_PROFILE_NAME, value);
	}

	public int getDepth3D() {
		return getInt(KEY_3D_SUBTITLES_DEPTH, 0);
	}

	public void setDepth3D(int value) {
		configuration.setProperty(KEY_3D_SUBTITLES_DEPTH, value);
	}

	/**
	 * Set whether UMS should allow only one instance by shutting down
	 * the first one when a second one is launched.
	 *
	 * @param value whether to kill the old UMS instance
	 */
	public void setRunSingleInstance(boolean value) {
		configuration.setProperty(KEY_SINGLE, value);
	}

	/**
	 * Whether UMS should allow only one instance by shutting down
	 * the first one when a second one is launched.
	 *
	 * @return value whether to kill the old UMS instance
	 */
	public boolean isRunSingleInstance() {
		return getBoolean(KEY_SINGLE, true);
	}

	public boolean getNoFolders(String tag) {
		if (tag == null) {
			return getBoolean(KEY_NO_FOLDERS, false);
		}

		String x = (tag.toLowerCase() + ".no_shared").replaceAll(" ", "_");
		return getBoolean(x, false);
	}

	public boolean getWebHttps() {
		return getBoolean(KEY_WEB_HTTPS, false);
	}

	public File getWebPath() {
		File path = new File(getString(KEY_WEB_PATH, "web"));
		if (!path.exists()) {
			//check if we are running from sources
			File srcPath = new File("src/main/external-resources/web");
			if (!srcPath.exists()) {
				path.mkdirs();
			} else {
				path = srcPath;
			}
		}
		return path;
	}

	public File getWebFile(String file) {
		return new File(getWebPath().getAbsolutePath() + File.separator + file);
	}

	public boolean isWebAuthenticate() {
		return getBoolean(KEY_WEB_AUTHENTICATE, false);
	}

	public int getWebThreads() {
		int x = getInt(KEY_WEB_THREADS, 30);
		return (x > WEB_MAX_THREADS ? WEB_MAX_THREADS : x);
	}

	public boolean isWebMp4Trans() {
		return getBoolean(KEY_WEB_MP4_TRANS, false);
	}

	public String getBumpAddress() {
		return getString(KEY_BUMP_ADDRESS, "");
	}

	public void setBumpAddress(String value) {
		configuration.setProperty(KEY_BUMP_ADDRESS, value);
	}

	public String getBumpJS(String fallback) {
		return getString(KEY_BUMP_JS, fallback);
	}

	public String getBumpSkinDir(String fallback) {
		return getString(KEY_BUMP_SKIN_DIR, fallback);
	}

	/**
	 * Default port for the web player server.
	 * @return the port that will be used for the web player server.
	 */
	public int getWebInterfaceServerPort() {
		return getInt(KEY_WEB_PORT, 9001);
	}

	public boolean useWebInterfaceServer() {
		return getBoolean(KEY_WEB_ENABLE, true);
	}

	public boolean isAutomaticMaximumBitrate() {
		return getBoolean(KEY_AUTOMATIC_MAXIMUM_BITRATE, true);
	}

	public void setAutomaticMaximumBitrate(boolean b) {
		if (!isAutomaticMaximumBitrate() && b) {
			// get all bitrates from renderers
			RendererConfiguration.calculateAllSpeeds();
		}

		configuration.setProperty(KEY_AUTOMATIC_MAXIMUM_BITRATE, b);
	}

	public boolean isSpeedDbg() {
		return getBoolean(KEY_SPEED_DBG, false);
	}

	public boolean getAutoDiscover() {
		return getBoolean(KEY_AUTOMATIC_DISCOVER, false);
	}

	public boolean getWebAutoCont(Format f) {
		String key = KEY_WEB_CONT_VIDEO;
		boolean def = false;
		if (f.isAudio()) {
			key = KEY_WEB_CONT_AUDIO;
			def = true;
		}

		if (f.isImage()) {
			key = KEY_WEB_CONT_IMAGE;
			def = false;
		}

		return getBoolean(key, def);
	}

	public boolean getWebAutoLoop(Format f) {
		String key = KEY_WEB_LOOP_VIDEO;
		if (f.isAudio()) {
			key = KEY_WEB_LOOP_AUDIO;
		}

		if (f.isImage()) {
			key = KEY_WEB_LOOP_IMAGE;
		}

		return getBoolean(key, false);
	}

	public int getWebImgSlideDelay() {
		return getInt(KEY_WEB_IMAGE_SLIDE, 0);
	}

	public String getWebSize() {
		return getString(KEY_WEB_SIZE, "");
	}

	public int getWebHeight() {
		return getInt(KEY_WEB_HEIGHT, 0);
	}

	public int getWebWidth() {
		return getInt(KEY_WEB_WIDTH, 0);
	}

	public boolean getWebFlash() {
		return getBoolean(KEY_WEB_FLASH, false);
	}

	public boolean getWebSubs() {
		return getBoolean(KEY_WEB_SUBS_TRANS, false);
	}

	public String getBumpAllowedIps() {
		return getString(KEY_BUMP_IPS, "");
	}

	public String getWebTranscode() {
		return getString(KEY_WEB_TRANSCODE, null);
	}

	public int getWebLowSpeed() {
		return getInt(KEY_WEB_LOW_SPEED, 0);
	}

	public boolean useWebLang() {
		return getBoolean(KEY_WEB_BROWSE_LANG, false);
	}

	public boolean useWebSubLang() {
		return getBoolean(KEY_WEB_BROWSE_SUB_LANG, false);
	}

	public boolean useWebControl() {
		return getBoolean(KEY_WEB_CONTROL, true);
	}

	public boolean useCode() {
		return getBoolean(KEY_CODE_USE, true);
	}

	public int getCodeValidTmo() {
		return (getInt(KEY_CODE_TMO, 4 * 60) * 60 * 1000);
	}

	public boolean isShowCodeThumbs() {
		return getBoolean(KEY_CODE_THUMBS, true);
	}

	public int getCodeCharSet() {
		int cs = getInt(KEY_CODE_CHARS, CodeEnter.DIGITS);
		if (cs < CodeEnter.DIGITS || cs > CodeEnter.BOTH) {
			// ensure we go a legal value
			cs = CodeEnter.DIGITS;
		}

		return cs;
	}

	public boolean isSortAudioTracksByAlbumPosition() {
		return getBoolean(KEY_SORT_AUDIO_TRACKS_BY_ALBUM_POSITION, true);
	}

	public boolean isDynamicPls() {
		return getBoolean(KEY_DYNAMIC_PLS, false);
	}

	public boolean isDynamicPlsAutoSave() {
		return getBoolean(KEY_DYNAMIC_PLS_AUTO_SAVE, false);
	}

	public String getDynamicPlsSavePath() {
		String path = getString(KEY_DYNAMIC_PLS_SAVE_PATH, "");
		if (StringUtils.isEmpty(path)) {
			path = getDataFile("dynpls");
			// ensure that this path exists
			new File(path).mkdirs();
		}

		return path;
	}

	public String getDynamicPlsSaveFile(String str) {
		return getDynamicPlsSavePath() + File.separator + str;
	}

	public boolean isHideSavedPlaylistFolder() {
		return getBoolean(KEY_DYNAMIC_PLS_HIDE, false);
	}

	public boolean isAutoContinue() {
		return getBoolean(KEY_PLAYLIST_AUTO_CONT, false);
	}

	public boolean isAutoAddAll() {
		return getBoolean(KEY_PLAYLIST_AUTO_ADD_ALL, false);
	}

	public String getAutoPlay() {
		return getString(KEY_PLAYLIST_AUTO_PLAY, null);
	}

	public boolean useChromecastExt() {
		return getBoolean(KEY_CHROMECAST_EXT, false);
	}

	public boolean isChromecastDbg() {
		return getBoolean(KEY_CHROMECAST_DBG, false);
	}

	public String getManagedPlaylistFolder() {
		return getString(KEY_MANAGED_PLAYLIST_FOLDER, "");
	}

	/**
	 * Enable the automatically saving of modified properties to the disk.
	 */
	public void setAutoSave() {
		((PropertiesConfiguration) configuration).setAutoSave(true);
	}

	public boolean isUpnpEnabled() {
		return getBoolean(KEY_UPNP_ENABLED, true);
	}

	public boolean isUpnpDebug() {
		return getBoolean(KEY_UPNP_DEBUG, false);
	}

	public String getRootLogLevel() {
		String level = getString(KEY_ROOT_LOG_LEVEL, "DEBUG").toUpperCase();
		return "ALL TRACE DEBUG INFO WARN ERROR OFF".contains(level) ? level : "DEBUG";
	}

	public void setRootLogLevel(ch.qos.logback.classic.Level level) {
		configuration.setProperty(KEY_ROOT_LOG_LEVEL, level.toString());
	}

	public boolean isShowSplashScreen() {
		return getBoolean(KEY_SHOW_SPLASH_SCREEN, true);
	}

	public void setShowSplashScreen(boolean value) {
		configuration.setProperty(KEY_SHOW_SPLASH_SCREEN, value);
	}

	public boolean isInfoDbRetry() {
		return getBoolean(KEY_INFO_DB_RETRY, false);
	}

	public int getAliveDelay() {
		return getInt(KEY_ALIVE_DELAY, 0);
	}

	/**
	 * This will show the info display informing user that automatic
	 * video setting were updated and is highly recommended.
	 * @return if info will be shown
	 */
	public boolean showInfoAboutVideoAutomaticSetting() {
		return getBoolean(SHOW_INFO_ABOUT_AUTOMATIC_VIDEO_SETTING, true);
	}

	public void setShowInfoAboutVideoAutomaticSetting(boolean value) {
		configuration.setProperty(SHOW_INFO_ABOUT_AUTOMATIC_VIDEO_SETTING, value);
	}

	/**
	 * @return whether UMS has run once.
	 */
	public boolean hasRunOnce() {
		return getBoolean(WAS_YOUTUBE_DL_ENABLED_ONCE, false);
	}

	/**
	 * Records that UMS has run once.
	 */
	public void setHasRunOnce() {
		configuration.setProperty(WAS_YOUTUBE_DL_ENABLED_ONCE, true);
	}

	/**
	 * This {@code enum} represents the available "levels" for subtitles
	 * information display that is to be appended to the video name.
	 */
	public static enum SubtitlesInfoLevel {

		/** Don't show subtitles information */
		NONE,

		/** Show only basic subtitles information */
		BASIC,

		/** Show full subtitles information */
		FULL;

		@Override
		public String toString() {
			switch (this) {
				case BASIC -> {
					return "basic";
				}
				case FULL -> {
					return "full";
				}
				case NONE -> {
					return "none";
				}
				default -> throw new AssertionError("Missing implementation of SubtitlesInfoLevel \"" + name() + "\"");
			}
		}

		/**
		 * Tries to parse the specified {@link String} and return the
		 * corresponding {@link SubtitlesInfoLevel}.
		 *
		 * @param infoLevelString the {@link String} to parse.
		 * @return The corresponding {@link SubtitlesInfoLevel} or {@code null}
		 *         if the parsing failed.
		 */
		public static SubtitlesInfoLevel typeOf(String infoLevelString) {
			if (isBlank(infoLevelString)) {
				return null;
			}
			infoLevelString = infoLevelString.trim().toLowerCase(Locale.ROOT);
			return switch (infoLevelString) {
				case "off", "none", "0" -> NONE;
				case "basic", "simple", "1" -> BASIC;
				case "full", "advanced", "2" -> FULL;
				default -> null;
			};
		}
	}

	/**
	 * Whether to disable connection to external entities to prevent the XML External Entity vulnerability.
	 *
	 * @return default {@code true} whether to disable external entities.
	 */
	public boolean disableExternalEntities() {
		return getBoolean(KEY_DISABLE_EXTERNAL_ENTITIES, true);
	}

	public List<String> getWebConfigurationFileHeader() {
		return Arrays.asList(
			"##########################################################################################################",
			"#                                                                                                        #",
			"# WEB.conf: configure support for web feeds and streams                                                  #",
			"#                                                                                                        #",
			"# NOTE: This file must be placed in the profile directory to work                                        #",
			"#                                                                                                        #",
			"# Supported types:                                                                                       #",
			"#                                                                                                        #",
			"#     imagefeed, audiofeed, videofeed, audiostream, videostream                                          #",
			"#                                                                                                        #",
			"# Format for feeds:                                                                                      #",
			"#                                                                                                        #",
			"#     type.folders,separated,by,commas=URL,,,name                                                        #",
			"#                                                                                                        #",
			"# Format for streams:                                                                                    #",
			"#                                                                                                        #",
			"#     type.folders,separated,by,commas=name,URL,optional thumbnail URL                                   #",
			"#                                                                                                        #",
			"##########################################################################################################"
		);
	}

	public void writeWebConfigurationFile() {
		List<String> defaultWebConfContents = new ArrayList<>();
		defaultWebConfContents.addAll(getWebConfigurationFileHeader());
		defaultWebConfContents.addAll(Arrays.asList(
			"",
			"# image feeds",
			"imagefeed.Web,Pictures=https://api.flickr.com/services/feeds/photos_public.gne?format=rss2",
			"imagefeed.Web,Pictures=https://api.flickr.com/services/feeds/photos_public.gne?id=39453068@N05&format=rss2",
			"imagefeed.Web,Pictures=https://api.flickr.com/services/feeds/photos_public.gne?id=14362684@N08&format=rss2",
			"",
			"# audio feeds",
			"audiofeed.Web,Podcasts=https://rss.art19.com/caliphate",
			"audiofeed.Web,Podcasts=https://www.nasa.gov/rss/dyn/Gravity-Assist.rss",
			"audiofeed.Web,Podcasts=https://rss.art19.com/wolverine-the-long-night",
			"",
			"# video feeds",
			"videofeed.Web,Vodcasts=https://feeds.feedburner.com/tedtalks_video",
			"videofeed.Web,Vodcasts=https://www.nasa.gov/rss/dyn/nasax_vodcast.rss",
			"videofeed.Web,YouTube Channels=https://www.youtube.com/feeds/videos.xml?channel_id=UC0PEAMcRK7Mnn2G1bCBXOWQ",
			"videofeed.Web,YouTube Channels=https://www.youtube.com/feeds/videos.xml?channel_id=UCccjdJEay2hpb5scz61zY6Q",
			"videofeed.Web,YouTube Channels=https://www.youtube.com/feeds/videos.xml?channel_id=UCqFzWxSCi39LnW1JKFR3efg",
			"videofeed.Web,YouTube Channels=https://www.youtube.com/feeds/videos.xml?channel_id=UCfAOh2t5DpxVrgS9NQKjC7A",
			"videofeed.Web,YouTube Channels=https://www.youtube.com/feeds/videos.xml?channel_id=UCzRBkt4a2hy6HObM3cl-x7g",
			"",
			"# audio streams",
			"audiostream.Web,Radio=RNZ,http://radionz-ice.streamguys.com/national.mp3,https://www.rnz.co.nz/assets/cms_uploads/000/000/159/RNZ_logo-Te-Reo-NEG-500.png",
			"",
			"# video streams",
			"# videostream.Web,TV=France 24,mms://stream1.france24.yacast.net/f24_liveen,http://www.france24.com/en/sites/france24.com.en/themes/france24/logo-fr.png",
			"# videostream.Web,TV=BFM TV (French TV),mms://vipmms9.yacast.net/bfm_bfmtv,http://upload.wikimedia.org/wikipedia/en/6/62/BFMTV.png",
			"# videostream.Web,Webcams=View of Shanghai Harbour,mmst://www.onedir.com/cam3,http://media-cdn.tripadvisor.com/media/photo-s/00/1d/4b/d8/pudong-from-the-bund.jpg")
		);

		writeWebConfigurationFile(defaultWebConfContents);
	}

	public synchronized void writeWebConfigurationFile(List<String> fileContents) {
		List<String> contentsToWrite = new ArrayList<>();
		contentsToWrite.addAll(getWebConfigurationFileHeader());
		contentsToWrite.addAll(fileContents);

		try {
			Path webConfFilePath = Paths.get(getWebConfPath());
			Files.write(webConfFilePath, contentsToWrite, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.debug("An error occurred while writing the web config file: {}", e);
		}
	}
}
