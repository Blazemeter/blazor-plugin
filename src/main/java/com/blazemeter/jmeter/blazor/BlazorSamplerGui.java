package com.blazemeter.jmeter.blazor;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

/**
 * GUI for {@link BlazorSampler}. The class name has to be the sampler class
 * name plus {@code Gui}; that is how JMeter finds it.
 */
public class BlazorSamplerGui extends AbstractSamplerGui {

    private static final long serialVersionUID = 1L;

    private final JTextField serverUrl = new JTextField(40);
    private final JTextField connection = new JTextField(20);
    private final JComboBox<String> action = new JComboBox<>(new String[] {
            BlazorSampler.CONNECT, BlazorSampler.NAVIGATE, BlazorSampler.INVOKE, BlazorSampler.DISCONNECT
    });
    private final JTextField location = new JTextField(40);
    private final JTextField target = new JTextField(30);
    private final JTextArea arguments = new JTextArea(8, 40);
    private final JTextField timeout = new JTextField(8);

    public BlazorSamplerGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 8));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints label = constraints(0, 0, 0);
        GridBagConstraints field = constraints(1, 1, 0);
        int row = 0;
        addRow(form, row++, label, field, "Server URL", serverUrl);
        addRow(form, row++, label, field, "Connection name", connection);
        addRow(form, row++, label, field, "Action", action);
        addRow(form, row++, label, field, "Location", location);
        addRow(form, row++, label, field, "Hub method", target);
        form.add(new JLabel("Arguments (JSON array)"), at(label, row));
        arguments.setLineWrap(true);
        field.gridy = row++;
        form.add(new JScrollPane(arguments), field);
        addRow(form, row, label, field, "Timeout (ms)", timeout);
        add(form, BorderLayout.CENTER);
    }

    private static void addRow(JPanel form, int row, GridBagConstraints label, GridBagConstraints field,
            String name, java.awt.Component component) {
        form.add(new JLabel(name), at(label, row));
        field.gridy = row;
        form.add(component, field);
    }

    private static GridBagConstraints at(GridBagConstraints source, int row) {
        GridBagConstraints copy = (GridBagConstraints) source.clone();
        copy.gridy = row;
        return copy;
    }

    private static GridBagConstraints constraints(int x, double weightx, int fillExtra) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.weightx = weightx;
        constraints.fill = weightx > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    @Override
    public String getLabelResource() {
        return "bzm_blazor_sampler";
    }

    @Override
    public String getStaticLabel() {
        return "bzm - Blazor Server";
    }

    @Override
    public TestElement createTestElement() {
        BlazorSampler sampler = new BlazorSampler();
        modifyTestElement(sampler);
        return sampler;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        if (!(element instanceof BlazorSampler sampler)) {
            return;
        }
        sampler.setServerUrl(serverUrl.getText().trim());
        sampler.setConnection(connection.getText().trim());
        sampler.setAction(String.valueOf(action.getSelectedItem()));
        sampler.setLocation(location.getText().trim());
        sampler.setTarget(target.getText().trim());
        sampler.setArguments(arguments.getText().trim());
        sampler.setTimeout(timeout.getText().trim());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (!(element instanceof BlazorSampler sampler)) {
            return;
        }
        serverUrl.setText(sampler.getServerUrl());
        connection.setText(sampler.getConnection());
        action.setSelectedItem(sampler.getAction());
        location.setText(sampler.getLocation());
        target.setText(sampler.getTarget());
        arguments.setText(sampler.getArguments());
        timeout.setText(sampler.getTimeout());
    }

    @Override
    public void clearGui() {
        super.clearGui();
        serverUrl.setText("");
        connection.setText("blazor");
        action.setSelectedItem(BlazorSampler.CONNECT);
        location.setText("");
        target.setText("");
        arguments.setText("");
        timeout.setText("20000");
    }
}
