package com.blazemeter.jmeter.blazor.recorder;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Small always-on-top window used while the Blazor recorder is running.
 * The name and comment fields become the Simple Controller that wraps the
 * samplers recorded since the previous group.
 */
public final class RecordingGroupWindow {

    private final JDialog dialog;
    private final Window screen;
    private final JTextField name = new JTextField("Blazor", 22);
    private final JTextArea comment = new JTextArea(3, 22);
    private final JLabel server = new JLabel("Server: waiting for the app");
    private final JLabel pending = new JLabel("Ungrouped samplers: 0");
    private final JLabel message = new JLabel(" ");
    private boolean placed;
    private boolean closing;

    public RecordingGroupWindow(JComponent anchor, Runnable onGroup, Runnable onStop) {
        screen = SwingUtilities.getWindowAncestor(anchor);
        // Unowned, so it is not ordered together with the JMeter frame.
        // Typing requires this window to become active. That also raises
        // JMeter, so the frame is sent behind the browser until Stop or close.
        dialog = new JDialog((Window) null, "Blazor recording");
        dialog.setAlwaysOnTop(true);
        dialog.setFocusableWindowState(true);
        dialog.setAutoRequestFocus(false);
        dialog.getRootPane().putClientProperty("Window.hidesOnDeactivate", Boolean.FALSE);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent event) {
                keepJMeterBehind();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                finish(onStop);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 0, 2, 0);
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridy = 0;
        form.add(server, constraints);
        constraints.gridy = 1;
        form.add(new JLabel("Controller name"), constraints);
        constraints.gridy = 2;
        form.add(name, constraints);
        constraints.gridy = 3;
        form.add(new JLabel("Comment"), constraints);
        constraints.gridy = 4;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1;
        comment.setLineWrap(true);
        comment.setWrapStyleWord(true);
        form.add(new JScrollPane(comment), constraints);

        JButton group = new JButton("Group into simple controller");
        group.addActionListener(event -> onGroup.run());
        JButton stop = new JButton("Stop recording");
        stop.addActionListener(event -> finish(onStop));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(group);
        actions.add(stop);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(actions, BorderLayout.NORTH);
        south.add(pending, BorderLayout.CENTER);
        south.add(message, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
    }

    public void show() {
        if (!placed) {
            dialog.pack();
            place();
            placed = true;
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    public void dispose() {
        dialog.dispose();
    }

    public String groupName() {
        String text = name.getText().trim();
        return text.isEmpty() ? "Blazor" : text;
    }

    public String groupComment() {
        return comment.getText().trim();
    }

    public void setServer(String variable, String origin) {
        if (origin == null || origin.isBlank() || variable == null || variable.isBlank()) {
            server.setText("Server: waiting for the app");
        } else {
            server.setText("Server ${" + variable + "} = " + origin);
        }
    }

    public void setUngrouped(int count) {
        pending.setText("Ungrouped samplers: " + count);
    }

    public void setMessage(String text) {
        message.setText(text == null || text.isBlank() ? " " : text);
    }

    private void finish(Runnable onStop) {
        closing = true;
        if (screen != null) {
            screen.setVisible(true);
            screen.toFront();
            screen.requestFocus();
        }
        onStop.run();
    }

    private void keepJMeterBehind() {
        if (closing || screen == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!closing && screen.isVisible()) {
                screen.toBack();
            }
        });
    }

    private void place() {
        GraphicsConfiguration configuration = null;
        if (screen != null) {
            configuration = screen.getGraphicsConfiguration();
        }
        if (configuration == null) {
            configuration = dialog.getGraphicsConfiguration();
        }
        if (configuration == null) {
            return;
        }
        Rectangle bounds = configuration.getBounds();
        int x = bounds.x + bounds.width - dialog.getWidth() - 24;
        int y = bounds.y + 48;
        dialog.setLocation(Math.max(bounds.x, x), y);
    }
}
