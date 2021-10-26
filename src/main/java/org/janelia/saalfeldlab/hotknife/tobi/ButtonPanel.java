package org.janelia.saalfeldlab.hotknife.tobi;

import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * A panel containing buttons, and callback lists for each of them.
 */
public class ButtonPanel extends JPanel {

	private final List<List<Runnable>> runOnButton;

	public ButtonPanel(final String... buttonLabels) {
		if (buttonLabels.length == 0)
			throw new IllegalArgumentException();

		final int numButtons = buttonLabels.length;
		runOnButton = new ArrayList<>(numButtons);

		setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
		add(Box.createHorizontalGlue());

		for (int i = 0; i < numButtons; i++) {
			final List<Runnable> runnables = new ArrayList<>();
			runOnButton.add(runnables);
			final JButton button = new JButton(buttonLabels[i]);
			button.addActionListener(e -> runnables.forEach(Runnable::run));
			add(button);
		}
	}

	public synchronized void onButton(final int index, final Runnable runnable) {
		runOnButton.get(index).add(runnable);
	}
}
