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
package net.pms.encoders;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.StringTokenizer;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.dlna.DLNAResource;
import net.pms.formats.Format;
import net.pms.formats.v2.SubtitleType;
import net.pms.util.PlayerUtil;
import net.pms.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AviSynthMEncoder extends MEncoderVideo {
	private static final Logger LOGGER = LoggerFactory.getLogger(AviSynthMEncoder.class);
	public static final PlayerId ID = StandardPlayerId.AVI_SYNTH_MENCODER;
	public static final String NAME = "AviSynth/MEncoder";

	// Not to be instantiated by anything but PlayerFactory
	AviSynthMEncoder() {
	}

	@Override
	public int purpose() {
		return VIDEO_SIMPLEFILE_PLAYER;
	}

	@Override
	public PlayerId id() {
		return ID;
	}

	@Override
	public boolean avisynth() {
		return true;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean isGPUAccelerationReady() {
		return true;
	}

	/*
	 * Generate the AviSynth script based on the user's settings
	 */
	public static File getAVSScript(String fileName, DLNAMediaSubtitle subTrack, int fromFrame, int toFrame, String frameRateRatio, String frameRateNumber, PmsConfiguration configuration) throws IOException {
		String onlyFileName = fileName.substring(1 + fileName.lastIndexOf('\\'));
		File file = new File(configuration.getTempFolder(), "pms-avs-" + onlyFileName + ".avs");
		try (PrintWriter pw = new PrintWriter(new FileOutputStream(file))) {
			String numerator;
			String denominator;

			if (frameRateRatio != null && frameRateNumber != null) {
				if (frameRateRatio.equals(frameRateNumber)) {
					// No ratio was available
					numerator = frameRateRatio;
					denominator = "1";
				} else {
					String[] frameRateNumDen = frameRateRatio.split("/");
					numerator = frameRateNumDen[0];
					denominator = "1001";
				}
			} else {
				// No framerate was given so we should try the most common one
				numerator = "24000";
				denominator = "1001";
				frameRateNumber = "23.976";
			}

			String assumeFPS = ".AssumeFPS(" + numerator + "," + denominator + ")";

			String directShowFPS = "";
			if (!"0".equals(frameRateNumber)) {
				directShowFPS = ", fps=" + frameRateNumber;
			}

			String convertfps = "";
			if (configuration.getAvisynthConvertFps()) {
				convertfps = ", convertfps=true";
			}

			File f = new File(fileName);
			if (f.exists()) {
				fileName = ProcessUtil.getShortFileNameIfWideChars(fileName);
			}

			String movieLine       = "DirectShowSource(\"" + fileName + "\"" + directShowFPS + convertfps + ")" + assumeFPS;
			String mtLine1         = "";
			String mtLine2         = "";
			String mtLine3         = "";
			String interframeLines = null;
			String interframePath  = configuration.getInterFramePath();

			int cores = 1;
			if (configuration.getAvisynthMultiThreading()) {
				cores = configuration.getNumberOfCpuCores();

				// Goes at the start of the file to initiate multithreading
				mtLine1 = "SetMemoryMax(512)\nSetMTMode(3," + cores + ")\n";

				// Goes after the input line to make multithreading more efficient
				mtLine2 = "SetMTMode(2)";

				// Goes at the end of the file to allow the multithreading to work with MEncoder
				mtLine3 = "SetMTMode(1)\nGetMTMode(false) > 0 ? distributor() : last";
			}

			// True Motion
			if (configuration.getAvisynthInterFrame()) {
				String gpu = "";
				movieLine += ".ConvertToYV12()";

				// Enable GPU to assist with CPU
				if (configuration.getAvisynthInterFrameGPU() && configuration.isGPUAcceleration()) {
					gpu = ", GPU=true";
				}

				interframeLines = "\n" +
					"PluginPath = \"" + interframePath + "\"\n" +
					"LoadPlugin(PluginPath+\"svpflow1.dll\")\n" +
					"LoadPlugin(PluginPath+\"svpflow2.dll\")\n" +
					"Import(PluginPath+\"InterFrame2.avsi\")\n" +
					"InterFrame(Cores=" + cores + gpu + ", Preset=\"Faster\")\n";
			}

			String subLine = null;
			if (
				subTrack != null &&
				subTrack.isExternal() &&
				configuration.isAutoloadExternalSubtitles() &&
				!configuration.isDisableSubtitles()
			) {
				if (subTrack.getExternalFile() != null) {
					LOGGER.info("AviSynth script: Using subtitle track: {}", subTrack);
					String function = "TextSub";
					if (subTrack.getType() == SubtitleType.VOBSUB) {
						function = "VobSub";
					}
					subLine = function + "(\"" + ProcessUtil.getShortFileNameIfWideChars(subTrack.getExternalFile()) + "\")";
				} else {
					LOGGER.error("External subtitles file \"{}\" is unavailable", subTrack.getName());
				}
			}

			ArrayList<String> lines = new ArrayList<>();

			lines.add(mtLine1);

			boolean fullyManaged = false;
			String script = configuration.getAvisynthScript();
			StringTokenizer st = new StringTokenizer(script, PMS.AVS_SEPARATOR);
			while (st.hasMoreTokens()) {
				String line = st.nextToken();
				if (line.contains("<movie") || line.contains("<sub")) {
					fullyManaged = true;
				}
				lines.add(line);
			}

			lines.add(mtLine2);

			if (configuration.getAvisynthInterFrame()) {
				lines.add(interframeLines);
			}

			lines.add(mtLine3);

			if (fullyManaged) {
				for (String s : lines) {
					if (s.contains("<moviefilename>")) {
						s = s.replace("<moviefilename>", fileName);
					}

					s = s.replace("<movie>", movieLine);
					s = s.replace("<sub>", subLine != null ? subLine : "#");
					pw.println(s);
				}
			} else {
				pw.println(movieLine);
				if (subLine != null) {
					pw.println(subLine);
				}
				pw.println("clip");

			}
		}
		file.deleteOnExit();
		return file;
	}

	@Override
	public boolean isCompatible(DLNAResource resource) {
		Format format = resource.getFormat();

		if (format != null) {
			if (format.getIdentifier() == Format.Identifier.WEB) {
				return false;
			}
		}

		DLNAMediaSubtitle subtitle = resource.getMediaSubtitle();

		// Check whether the subtitle actually has a language defined,
		// Uninitialized DLNAMediaSubtitle objects have a null language.
		if (subtitle != null && subtitle.getLang() != null) {
			// This engine only supports external subtitles
			if (subtitle.isExternal()) {
				return true;
			}

			return false;
		}

		try {
			String audioTrackName = resource.getMediaAudio().toString();
			String defaultAudioTrackName = resource.getMedia().getAudioTracksList().get(0).toString();

			if (!audioTrackName.equals(defaultAudioTrackName)) {
				// This engine only supports playback of the default audio track
				return false;
			}
		} catch (NullPointerException e) {
			LOGGER.trace("AviSynth/MEncoder cannot determine compatibility based on audio track for " + resource.getSystemName());
		} catch (IndexOutOfBoundsException e) {
			LOGGER.trace("AviSynth/MEncoder cannot determine compatibility based on default audio track for " + resource.getSystemName());
		}

		if (
			PlayerUtil.isVideo(resource, Format.Identifier.MKV) ||
			PlayerUtil.isVideo(resource, Format.Identifier.MPG) ||
			PlayerUtil.isVideo(resource, Format.Identifier.OGG)
		) {
			return true;
		}

		return false;
	}
}
