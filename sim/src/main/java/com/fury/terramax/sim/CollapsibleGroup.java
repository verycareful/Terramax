package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * A titled section of the control column that can be folded away.
 *
 * <p>Swing has no such component. It is needed because the settings list has grown
 * past what a flat stack of sliders can carry: eight sliders in a 300-pixel column is
 * already unreadable, and the region and terrain groups are still to come. Folding
 * lets you open the one subsystem you are tuning and hide the rest.
 */
public final class CollapsibleGroup extends JPanel {
	private static final Color HEADER_TEXT = new Color(226, 230, 238);
	private static final Color HEADER_BACKGROUND = new Color(52, 58, 70);

	private final JPanel content = new JPanel();
	private final JButton header;
	private final String title;

	private boolean expanded;

	public CollapsibleGroup(final String title, final boolean initiallyExpanded) {
		this.title = title;
		this.expanded = initiallyExpanded;

		setLayout(new BorderLayout());
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 0));
		content.setVisible(expanded);

		header = new JButton(headerText());
		header.setFocusPainted(false);
		header.setHorizontalAlignment(JButton.LEFT);
		header.setBackground(HEADER_BACKGROUND);
		header.setForeground(HEADER_TEXT);
		header.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		header.addActionListener(e -> toggle());

		add(header, BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
	}

	/** Adds a control to this group. */
	public void addControl(final Component component) {
		content.add(component);
	}

	private void toggle() {
		expanded = !expanded;
		content.setVisible(expanded);
		header.setText(headerText());

		revalidate();
		repaint();
	}

	private String headerText() {
		return (expanded ? "v  " : ">  ") + title;
	}

	@Override
	public Dimension getMaximumSize() {
		// Without this a BoxLayout parent stretches every group to fill the column,
		// which spreads three collapsed headers across the whole panel.
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	private static final long serialVersionUID = 1L;
}
