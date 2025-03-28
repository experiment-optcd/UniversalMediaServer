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
package net.pms.newgui;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.io.BasicSystemUtils;
import net.pms.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AboutTab {
	private static final Logger LOGGER = LoggerFactory.getLogger(AboutTab.class);

	private ImagePanel imagePanel;

	public ImagePanel getImagePanel() {
		return imagePanel;
	}

	private Integer rowPosition = 1;

	/**
	 * Gets the current GUI row position and adds 2 to it
	 * for next time. Using it saves having to manually specify
	 * numbers which can waste time when we make changes.
	 */
	private Integer getAndIncrementRowPosition() {
		Integer currentRowPosition = rowPosition;
		rowPosition += 2;
		return currentRowPosition;
	};

	public JComponent build() {
		FormLayout layout = new FormLayout(
			"0:grow, pref, 0:grow",
			"pref, 3dlu, pref, 12dlu, pref, 12dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu, p"
		);

		PanelBuilder builder = new PanelBuilder(layout);
		builder.border(Borders.DIALOG);
		builder.opaque(true);
		CellConstraints cc = new CellConstraints();

		String projectName = PropertiesUtil.getProjectProperties().get("project.name");

		final LinkMouseListener pms3Link = new LinkMouseListener(projectName + " " + PMS.getVersion(),
			"https://www.universalmediaserver.com/");
		JLabel lPms3Link = builder.addLabel(pms3Link.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lPms3Link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lPms3Link.addMouseListener(pms3Link);

		// Create a build name from the available git properties
		String commitId = PropertiesUtil.getProjectProperties().get("git.commit.id");
		String commitTime = PropertiesUtil.getProjectProperties().get("git.commit.time");
		String commitUrl = "https://github.com/UniversalMediaServer/UniversalMediaServer/commit/" + commitId;
		String buildLabel = Messages.getString("BuildDate") + " " + commitTime;

		final LinkMouseListener commitLink = new LinkMouseListener(buildLabel, commitUrl);
		JLabel lCommitLink = builder.addLabel(commitLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lCommitLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lCommitLink.addMouseListener(commitLink);

		imagePanel = buildImagePanel();
		builder.add(imagePanel, cc.xy(2, getAndIncrementRowPosition(), "center, fill"));

		builder.addLabel(Messages.getString("RelatedLinks"), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));

		final LinkMouseListener crowdinLink = new LinkMouseListener(Messages.getString("XCrowdinProject"), PMS.CROWDIN_LINK);
		JLabel lCrowdinLink = builder.addLabel(crowdinLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lCrowdinLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lCrowdinLink.addMouseListener(crowdinLink);

		final LinkMouseListener ffmpegLink = new LinkMouseListener("FFmpeg", "https://ffmpeg.org/");
		JLabel lFfmpegLink = builder.addLabel(ffmpegLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lFfmpegLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lFfmpegLink.addMouseListener(ffmpegLink);

		final LinkMouseListener mplayerLink = new LinkMouseListener("MPlayer", "http://www.mplayerhq.hu");
		JLabel lMplayerLink = builder.addLabel(mplayerLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lMplayerLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lMplayerLink.addMouseListener(mplayerLink);

		final LinkMouseListener spirtonLink = new LinkMouseListener("MPlayer, MEncoder and InterFrame builds", "https://www.spirton.com");
		JLabel lSpirtonLink = builder.addLabel(spirtonLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lSpirtonLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lSpirtonLink.addMouseListener(spirtonLink);

		final LinkMouseListener mediaInfoLink = new LinkMouseListener("MediaInfo", "https://mediaarea.net/en/MediaInfo");
		JLabel lMediaInfoLink = builder.addLabel(mediaInfoLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lMediaInfoLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lMediaInfoLink.addMouseListener(mediaInfoLink);

		final LinkMouseListener avisynthMTLink = new LinkMouseListener("AviSynth MT", "https://forum.doom9.org/showthread.php?t=148782");
		JLabel lAvisynthMTLink = builder.addLabel(avisynthMTLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lAvisynthMTLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lAvisynthMTLink.addMouseListener(avisynthMTLink);

		final LinkMouseListener dryIconsLink = new LinkMouseListener("DryIcons", "https://dryicons.com/");
		JLabel lDryIconsLink = builder.addLabel(dryIconsLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lDryIconsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lDryIconsLink.addMouseListener(dryIconsLink);

		final LinkMouseListener jmgIconsLink = new LinkMouseListener("Jordan Michael Groll's Icons", "https://www.deviantart.com/jrdng");
		JLabel lJmgIconsLink = builder.addLabel(jmgIconsLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lJmgIconsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lJmgIconsLink.addMouseListener(jmgIconsLink);

		final LinkMouseListener svpLink = new LinkMouseListener("SVP", "https://www.svp-team.com/");
		JLabel lSVPLink = builder.addLabel(svpLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lSVPLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lSVPLink.addMouseListener(svpLink);

		final LinkMouseListener openSubtitlesLink = new LinkMouseListener("OpenSubtitles.org", "https://www.opensubtitles.org/");
		JLabel lOpenSubtitlesLink = builder.addLabel(openSubtitlesLink.getLabel(), cc.xy(2, getAndIncrementRowPosition(), "center, fill"));
		lOpenSubtitlesLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lOpenSubtitlesLink.addMouseListener(openSubtitlesLink);

		JScrollPane scrollPane = new JScrollPane(builder.getPanel());
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		return scrollPane;
	}

	private static class LinkMouseListener implements MouseListener {
		private final String name;
		private final String link;

		public LinkMouseListener(String n, String l) {
			name = n;
			link = l;
		}

		public String getLabel() {
			final StringBuilder sb = new StringBuilder();
			sb.append("<html>");
			sb.append("<a href=\"");
			sb.append(link);
			sb.append("\">");
			sb.append(name);
			sb.append("</a>");
			sb.append("</html>");
			return sb.toString();
		}

		@Override
		public void mouseClicked(MouseEvent e) {
			BasicSystemUtils.instance.browseURI(link);
		}

		@Override
		public void mouseEntered(MouseEvent e) {
		}

		@Override
		public void mouseExited(MouseEvent e) {
		}

		@Override
		public void mousePressed(MouseEvent e) {
		}

		@Override
		public void mouseReleased(MouseEvent e) {
		}
	}

	public ImagePanel buildImagePanel() {
		BufferedImage bi = null;
		try {
			bi = ImageIO.read(LooksFrame.class.getResourceAsStream("/resources/images/logo.png"));
		} catch (IOException e) {
			LOGGER.debug("Caught exception", e);
		}
		return new ImagePanel(bi);
	}
}
