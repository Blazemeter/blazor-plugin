package com.blazemeter.jmeter.blazor;

import com.blazemeter.jmeter.blazor.recorder.RecordedStep;
import com.blazemeter.jmeter.blazor.recorder.RecordingProxy;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Start a local proxy and insert Connect / Navigate / Invoke samplers into
 * the first thread group as the browser uses the Blazor app.
 */
public class BlazorRecorderGui extends AbstractJMeterGuiComponent {

    private static final long serialVersionUID = 1L;

    private static RecordingProxy running;

    private final JTextField port = new JTextField("8888", 6);
    private final JTextArea status = new JTextArea(12, 50);
    private final JButton start = new JButton("Start");
    private final JButton stop = new JButton("Stop");
    private Timer refresher;
    private String serverUrl = "";

    public BlazorRecorderGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 8));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Listen port"));
        controls.add(port);
        controls.add(start);
        controls.add(stop);
        stop.setEnabled(false);
        add(controls, BorderLayout.CENTER);

        status.setEditable(false);
        status.setText("Point the browser HTTP and SOCKS proxy at 127.0.0.1 (or localhost) and this port.\n"
                + "Samplers appear in the first thread group while you browse. HTTPS is tunneled and not recorded.");
        add(new JScrollPane(status), BorderLayout.SOUTH);

        start.addActionListener(event -> startRecording());
        stop.addActionListener(event -> stopRecording());
    }

    private void startRecording() {
        if (running != null) {
            status.setText("Already recording on port " + running.port());
            return;
        }
        int listen;
        try {
            listen = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException ex) {
            JMeterUtils.reportErrorToUser("Port must be a number", "Blazor Recorder");
            return;
        }
        serverUrl = "";
        RecordingProxy proxy = new RecordingProxy();
        proxy.setStepListener(step -> SwingUtilities.invokeLater(() -> acceptStep(step)));
        try {
            proxy.start(listen);
        } catch (Exception ex) {
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
            return;
        }
        running = proxy;
        start.setEnabled(false);
        stop.setEnabled(true);
        refresher = new Timer(400, event -> refreshStatus());
        refresher.start();
        refreshStatus();
    }

    private void acceptStep(RecordedStep step) {
        try {
            insert(List.of(step));
        } catch (Exception ex) {
            status.append("\nCould not insert " + step.label() + ": " + ex.getMessage());
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
        }
    }

    private void refreshStatus() {
        RecordingProxy proxy = running;
        if (proxy == null) {
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("Recording on 127.0.0.1:").append(proxy.port()).append('\n');
        report.append("Samplers are added to the first thread group as you use the app.\n");
        List<String> seen = proxy.seenRequests();
        report.append("Browser requests seen: ").append(seen.size()).append('\n');
        int from = Math.max(0, seen.size() - 8);
        for (int i = from; i < seen.size(); i++) {
            String line = seen.get(i);
            if (line.startsWith("CONNECT ") && line.contains(":443")) {
                continue;
            }
            report.append(line).append('\n');
        }
        for (RecordedStep step : proxy.steps()) {
            report.append("• ").append(step.label()).append('\n');
        }
        status.setText(report.toString());
    }

    private void stopRecording() {
        if (refresher != null) {
            refresher.stop();
            refresher = null;
        }
        RecordingProxy proxy = running;
        running = null;
        start.setEnabled(true);
        stop.setEnabled(false);
        if (proxy == null) {
            status.setText("Recorder was not running.");
            return;
        }
        proxy.stop();
        proxy.setStepListener(null);
        List<RecordedStep> steps = proxy.steps();
        StringBuilder report = new StringBuilder();
        report.append("Recorded ").append(steps.size()).append(" steps.\n");
        for (RecordedStep step : steps) {
            report.append(step.label());
            if (!step.arguments().isEmpty()) {
                report.append(' ').append(step.arguments());
            }
            report.append('\n');
        }
        report.append("Samplers are in the first thread group.");
        status.setText(report.toString());
    }

    private int insert(List<RecordedStep> steps) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new IllegalStateException("JMeter GUI is not available");
        }
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode threadGroup = findThreadGroup(root);
        if (threadGroup == null) {
            ThreadGroup group = new ThreadGroup();
            group.setName("Blazor Users");
            group.setNumThreads(1);
            group.setRampUp(1);
            LoopController loop = new LoopController();
            loop.setLoops(1);
            loop.setContinueForever(false);
            loop.initialize();
            group.setSamplerController(loop);
            model.addComponent(group, root);
            threadGroup = findNode(root, group);
        }
        if (threadGroup == null) {
            throw new IllegalStateException("Could not find a thread group to hold the recording");
        }
        int count = 0;
        for (RecordedStep step : steps) {
            BlazorSampler sampler = new BlazorSampler();
            sampler.setName(step.label());
            sampler.setProperty(TestElement.GUI_CLASS, BlazorSamplerGui.class.getName());
            sampler.setProperty(TestElement.TEST_CLASS, BlazorSampler.class.getName());
            sampler.setConnection("blazor");
            sampler.setTimeout("20000");
            if (step.kind() == RecordedStep.Kind.CONNECT) {
                serverUrl = step.value();
                sampler.setAction(BlazorSampler.CONNECT);
                sampler.setServerUrl(serverUrl);
            } else if (step.kind() == RecordedStep.Kind.NAVIGATE) {
                sampler.setAction(BlazorSampler.NAVIGATE);
                sampler.setServerUrl(serverUrl);
                sampler.setLocation(step.value());
            } else if (step.kind() == RecordedStep.Kind.INVOKE) {
                sampler.setAction(BlazorSampler.INVOKE);
                sampler.setServerUrl(serverUrl);
                sampler.setTarget(step.value());
                sampler.setArguments(step.arguments());
            } else {
                sampler.setAction(BlazorSampler.DISCONNECT);
                sampler.setServerUrl(serverUrl);
            }
            model.addComponent(sampler, threadGroup);
            count++;
        }
        return count;
    }

    private static JMeterTreeNode findThreadGroup(JMeterTreeNode node) {
        if (node.getTestElement() instanceof AbstractThreadGroup) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode found = findThreadGroup((JMeterTreeNode) node.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JMeterTreeNode findNode(JMeterTreeNode node, TestElement element) {
        if (node.getTestElement() == element) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode found = findNode((JMeterTreeNode) node.getChildAt(i), element);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String getLabelResource() {
        return "bzm_blazor_recorder";
    }

    @Override
    public String getStaticLabel() {
        return "bzm - Blazor Recorder";
    }

    @Override
    public Collection<String> getMenuCategories() {
        return Collections.singletonList(MenuFactory.NON_TEST_ELEMENTS);
    }

    @Override
    public JPopupMenu createPopupMenu() {
        return MenuFactory.getDefaultMenu();
    }

    @Override
    public TestElement createTestElement() {
        BlazorRecorder recorder = new BlazorRecorder();
        modifyTestElement(recorder);
        return recorder;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        if (element instanceof BlazorRecorder recorder) {
            recorder.setPort(port.getText().trim());
        }
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof BlazorRecorder recorder) {
            port.setText(recorder.getPort());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        port.setText("8888");
    }
}
